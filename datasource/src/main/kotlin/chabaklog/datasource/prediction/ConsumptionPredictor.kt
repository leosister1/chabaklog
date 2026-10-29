package chabaklog.datasource.prediction

import kotlin.math.max
import kotlin.math.min

/** 지난 차박 한 번의 요약 (세션 기록에서 만든다) */
data class NightRecord(
    val durationHours: Float,
    val socUsedPercent: Float,
    val outsideTempC: Float?,
)

enum class PredictionBasis(val label: String) {
    /** 내 기록이 여러 기온에 걸쳐 있어 기온-소모 관계를 직접 맞춤 */
    MY_RECORDS_FITTED("내 기록 (기온별)"),
    /** 기본 곡선을 내 기록 수준으로 보정 */
    MY_RECORDS_SCALED("내 기록으로 보정"),
    /** 기록이 없어 기본값 사용 */
    DEFAULT("기본값 (기록이 쌓이면 바뀜)"),
}

data class Prediction(
    /** 오늘 밤 예상 배터리 사용량(%) */
    val socUsedPercent: Float,
    /** 시간당 소모율(%/h) */
    val ratePerHour: Float,
    val tempC: Float,
    val hours: Float,
    val basis: PredictionBasis,
    /** 근거가 된 내 기록 수 */
    val sampleCount: Int,
)

/**
 * 오늘 밤 배터리 소모 예측.
 *
 * 모델: 시간당 소모율 = 기본 소모 + 기울기 × max(0, 쾌적온도 − 외기온)
 *  - 외기온이 낮을수록 난방(공조)에 쓰는 전력이 늘어난다는 가정 하나만 둔다.
 *  - 기본 곡선의 숫자는 추정값이다. 그래서 화면에 "기본값"임을 표시하고, 내 기록이 생기면 바로 내 기록 쪽으로 바꾼다.
 *
 * 내 기록 활용
 *  1) 기온 폭이 충분한 기록이 [minFitRecords]개 이상 → 최소제곱으로 기본 소모·기울기를 직접 맞춘다.
 *  2) 그보다 적으면 → 기본 곡선 모양은 두고 높이만 내 기록 평균에 맞춘다(배율 0.3~3배).
 */
class ConsumptionPredictor(private val config: Config = Config()) {

    data class Config(
        val comfortC: Float = 18f,
        val defaultBasePerHour: Float = 0.6f,
        val defaultSlopePerHourPerC: Float = 0.12f,
        val minFitRecords: Int = 3,
        val minFitTempSpreadC: Float = 3f,
        val minRecordHours: Float = 1f,
    )

    fun predict(history: List<NightRecord>, tonightTempC: Float, hours: Float = 10f): Prediction {
        val usable = history.filter {
            it.durationHours >= config.minRecordHours && it.socUsedPercent >= 0f && it.outsideTempC != null
        }
        val x = cold(tonightTempC)

        val (rate, basis) = when {
            canFit(usable) -> fit(usable).let { (a, b) -> (a + b * x) to PredictionBasis.MY_RECORDS_FITTED }
            usable.isNotEmpty() -> {
                val factor = usable.map { observedRate(it) / defaultRate(it.outsideTempC!!) }.average().toFloat()
                (defaultRate(tonightTempC) * factor.coerceIn(0.3f, 3f)) to PredictionBasis.MY_RECORDS_SCALED
            }
            else -> defaultRate(tonightTempC) to PredictionBasis.DEFAULT
        }
        val safeRate = max(0f, rate)
        return Prediction(
            socUsedPercent = min(100f, safeRate * hours),
            ratePerHour = safeRate,
            tempC = tonightTempC,
            hours = hours,
            basis = basis,
            sampleCount = if (basis == PredictionBasis.DEFAULT) 0 else usable.size,
        )
    }

    private fun cold(tempC: Float) = max(0f, config.comfortC - tempC)
    private fun defaultRate(tempC: Float) = config.defaultBasePerHour + config.defaultSlopePerHourPerC * cold(tempC)
    private fun observedRate(r: NightRecord) = r.socUsedPercent / r.durationHours

    private fun canFit(records: List<NightRecord>): Boolean {
        if (records.size < config.minFitRecords) return false
        val temps = records.map { it.outsideTempC!! }
        return temps.max() - temps.min() >= config.minFitTempSpreadC
    }

    /** y = a + b·x 최소제곱. 기울기가 음수로 나오면(기록 잡음) 0으로 두고 평균만 쓴다. */
    private fun fit(records: List<NightRecord>): Pair<Float, Float> {
        val xs = records.map { cold(it.outsideTempC!!).toDouble() }
        val ys = records.map { observedRate(it).toDouble() }
        val mx = xs.average(); val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
        val b = if (sxx > 1e-9) max(0.0, sxy / sxx) else 0.0
        val a = my - b * mx
        return a.toFloat() to b.toFloat()
    }
}
