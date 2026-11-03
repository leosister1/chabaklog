package chabaklog.datasource

import chabaklog.datasource.prediction.ConsumptionPredictor
import chabaklog.datasource.prediction.NightRecord
import chabaklog.datasource.prediction.PredictionBasis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PredictionTest {
    private val p = ConsumptionPredictor()

    @Test fun `기록이 없으면 기본값, 추울수록 더 많이 쓴다`() {
        val warm = p.predict(emptyList(), tonightTempC = 20f)
        val cold = p.predict(emptyList(), tonightTempC = -2f)
        assertEquals(PredictionBasis.DEFAULT, warm.basis)
        assertEquals(6f, warm.socUsedPercent, "20°C면 기본 소모 0.6%/h × 10h")
        assertTrue(cold.socUsedPercent > warm.socUsedPercent)
        assertEquals(0, cold.sampleCount)
    }

    @Test fun `기록이 적으면 기본 곡선을 내 차 수준으로 보정한다`() {
        // 18°C 이상에서 10시간 12% → 시간당 1.2%, 기본의 2배
        val r = p.predict(listOf(NightRecord(10f, 12f, 20f)), tonightTempC = 20f)
        assertEquals(PredictionBasis.MY_RECORDS_SCALED, r.basis)
        assertEquals(12f, r.socUsedPercent)
    }

    @Test fun `기온 폭이 있는 기록 3개 이상이면 직접 맞춘다`() {
        // 소모율 = 1.0 + 0.2·(18−T)
        val history = listOf(
            NightRecord(10f, 10f, 18f),   // 1.0%/h
            NightRecord(10f, 30f, 8f),    // 3.0%/h
            NightRecord(5f, 25f, -2f),    // 5.0%/h
        )
        val r = p.predict(history, tonightTempC = 3f, hours = 10f)
        assertEquals(PredictionBasis.MY_RECORDS_FITTED, r.basis)
        assertEquals(3, r.sampleCount)
        assertTrue(kotlin.math.abs(r.ratePerHour - 4.0f) < 0.01f, "1.0 + 0.2×15 = 4.0, 실제 ${r.ratePerHour}")
    }

    @Test fun `짧은 기록과 기온 없는 기록은 쓰지 않는다`() {
        val r = p.predict(listOf(NightRecord(0.2f, 5f, 10f), NightRecord(8f, 8f, null)), tonightTempC = 18f)
        assertEquals(PredictionBasis.DEFAULT, r.basis)
    }

    @Test fun `기록이 없을 때 배터리가 작은 차는 같은 밤에 더 많이 쓴다`() {
        val big = p.predict(emptyList(), tonightTempC = 20f, batteryKwh = 77.4f)
        val small = p.predict(emptyList(), tonightTempC = 20f, batteryKwh = 38.7f)
        assertEquals(6f, big.socUsedPercent)
        assertEquals(12f, small.socUsedPercent, "용량 절반이면 % 소모는 두 배")
        assertEquals(38.7f, small.batteryKwhAdjusted)
    }

    @Test fun `내 기록이 있으면 배터리 용량 보정을 하지 않는다`() {
        val records = listOf(NightRecord(10f, 8f, 10f))
        val a = p.predict(records, tonightTempC = 10f)
        val b = p.predict(records, tonightTempC = 10f, batteryKwh = 38.7f)
        assertEquals(a.socUsedPercent, b.socUsedPercent)
        assertEquals(null, b.batteryKwhAdjusted)
    }
}
