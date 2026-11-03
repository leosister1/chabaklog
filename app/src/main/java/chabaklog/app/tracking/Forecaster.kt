package chabaklog.app.tracking

import chabaklog.app.data.AssetClimateTable
import chabaklog.app.data.ChabaklogDatabase
import chabaklog.app.data.SessionEntity
import chabaklog.datasource.location.GeoPoint
import chabaklog.datasource.prediction.ConsumptionPredictor
import chabaklog.datasource.prediction.NightRecord
import chabaklog.datasource.prediction.Prediction
import chabaklog.datasource.temperature.WeatherClient
import java.time.LocalDate

/**
 * 예측 결과 + 기온을 어디서 가져왔는지 + 오늘 밤 V2L 계획.
 * totalSocPercent = 차박(공조 등) 예상 + V2L 가전 사용.
 */
data class Forecast(val prediction: Prediction, val tempSource: String, val v2l: V2lUsage) {
    val totalSocPercent get() = (prediction.socUsedPercent + v2l.socPercent).coerceAtMost(100f)
}

/**
 * "이 장소에서 오늘 밤 배터리를 얼마나 쓸까"
 * 기온: 날씨 API(오늘 밤 최저) → 내장 월평균 최저기온표. 지금 차가 있는 곳이면 차량 외기온을 먼저 쓴다.
 * 소모율: 내 실제 기록(시연용 제외)으로 ConsumptionPredictor가 정한다. 기록이 없으면 내 차 배터리 용량으로 기본값을 보정한다.
 * V2L: 사용자가 고른 가전의 Wh를 배터리 용량으로 %로 바꿔 더한다.
 */
class Forecaster(
    private val db: ChabaklogDatabase,
    private val weather: WeatherClient,
    private val climate: AssetClimateTable,
    /** 차량 배터리 용량(kWh). 읽을 수 없으면 null */
    private val batteryKwh: () -> Float?,
    private val v2lPlan: () -> Set<Appliance>,
    private val predictor: ConsumptionPredictor = ConsumptionPredictor(),
) {
    private val referenceKwh = ConsumptionPredictor.Config().referenceBatteryKwh

    suspend fun forPlace(point: GeoPoint, vehicleTempC: Float? = null, useWeatherApi: Boolean = true): Forecast {
        val (temp, source) = when {
            vehicleTempC != null -> vehicleTempC to "차량 외기온 센서(지금)"
            useWeatherApi && weather.isReachable() -> weather.tonightLow(point)?.let { it to "날씨 예보(오늘 밤 최저)" }
                ?: climateTemp(point)
            else -> climateTemp(point)
        }
        val capacity = batteryKwh()
        return Forecast(
            predictor.predict(history(), temp, batteryKwh = capacity),
            source,
            V2lUsage.of(v2lPlan(), capacity, referenceKwh),
        )
    }

    private fun climateTemp(point: GeoPoint) =
        climate.monthlyLow(point, LocalDate.now().month) to "${climate.nearestStationName(point)} 평년 최저기온"

    private suspend fun history(): List<NightRecord> = db.sessions().realFinished().mapNotNull { it.toNight() }

    private fun SessionEntity.toNight(): NightRecord? {
        val end = endedAtMs ?: return null
        val start = socStart ?: return null
        val finish = socEnd ?: return null
        return NightRecord((end - startedAtMs) / 3_600_000f, start - finish, outsideTempC)
    }
}
