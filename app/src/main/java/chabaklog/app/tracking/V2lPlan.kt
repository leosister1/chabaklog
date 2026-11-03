package chabaklog.app.tracking

/**
 * 오늘 밤 V2L(차 배터리로 쓰는 가전) 계획.
 * 차량 SDK에는 V2L 사용량을 읽는 API가 없어, 사용자가 고른 가전의 소비 전력 × 사용 시간으로 계산한다.
 * 소비 전력은 흔한 캠핑용 제품 기준의 대략값이다(화면에 "대략"으로 표시).
 */
enum class Appliance(val label: String, val watts: Int, val hours: Float) {
    HEAT_MAT("전기장판", 150, 8f),
    ELECTRIC_BLANKET("전기요", 70, 8f),
    LIGHT("캠핑 조명", 15, 4f),
    LAPTOP("노트북 충전", 60, 2f),
    KETTLE("전기 포트", 1500, 0.2f),
    ;

    val wattHours get() = watts * hours
}

/** 고른 가전의 총 사용 전력(Wh)과 배터리 %. 배터리 용량을 모르면 기본 곡선과 같은 기준 용량으로 계산한다. */
data class V2lUsage(val appliances: Set<Appliance>, val wattHours: Float, val socPercent: Float, val capacityKnown: Boolean) {
    companion object {
        fun of(appliances: Set<Appliance>, batteryKwh: Float?, referenceKwh: Float): V2lUsage {
            val wh = appliances.sumOf { it.wattHours.toDouble() }.toFloat()
            val capacity = batteryKwh ?: referenceKwh
            return V2lUsage(appliances, wh, wh / (capacity * 1000f) * 100f, batteryKwh != null)
        }
    }
}
