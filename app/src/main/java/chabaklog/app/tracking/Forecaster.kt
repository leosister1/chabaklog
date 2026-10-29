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

/** 예측 결과 + 기온을 어디서 가져왔는지 */
data class Forecast(val prediction: Prediction, val tempSource: String)

/**
 * "이 장소에서 오늘 밤 배터리를 얼마나 쓸까"
 * 기온: 날씨 API(오늘 밤 최저) → 내장 월평균 최저기온표. 지금 차가 있는 곳이면 차량 외기온을 먼저 쓴다.
 * 소모율: 내 실제 기록(시연용 제외)으로 ConsumptionPredictor가 정한다.
 */
class Forecaster(
    private val db: ChabaklogDatabase,
    private val weather: WeatherClient,
    private val climate: AssetClimateTable,
    private val predictor: ConsumptionPredictor = ConsumptionPredictor(),
) {
    suspend fun forPlace(point: GeoPoint, vehicleTempC: Float? = null, useWeatherApi: Boolean = true): Forecast {
        val (temp, source) = when {
            vehicleTempC != null -> vehicleTempC to "차량 외기온 센서(지금)"
            useWeatherApi && weather.isReachable() -> weather.tonightLow(point)?.let { it to "날씨 예보(오늘 밤 최저)" }
                ?: climateTemp(point)
            else -> climateTemp(point)
        }
        return Forecast(predictor.predict(history(), temp), source)
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
