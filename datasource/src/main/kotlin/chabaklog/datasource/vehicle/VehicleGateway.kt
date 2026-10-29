package chabaklog.datasource.vehicle

import chabaklog.datasource.core.Availability

/** 앱이 쓰는 차량 신호. Pleos Vehicle SDK의 도메인/속성에 하나씩 대응시킨다. */
enum class VehicleSignal {
    GEAR,
    SEAT_OCCUPANCY,
    DOOR_OPEN,
    BATTERY_SOC,
    HVAC_POWER,
    STEERING_ANGLE,
    OUTSIDE_TEMPERATURE,
}

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** 차량에서 들어오는 이벤트. 감지기·추정기는 이 타입만 알고 SDK 타입은 모른다. */
sealed interface VehicleEvent {
    val timestampMs: Long

    data class GearChanged(val gear: Gear, override val timestampMs: Long) : VehicleEvent
    /** 좌석 하나라도 점유되어 있으면 true */
    data class SeatOccupancyChanged(val anyOccupied: Boolean, override val timestampMs: Long) : VehicleEvent
    /** 문 하나라도 열려 있으면 true */
    data class DoorChanged(val anyOpen: Boolean, override val timestampMs: Long) : VehicleEvent
    data class BatterySocChanged(val percent: Float, override val timestampMs: Long) : VehicleEvent
    data class HvacPowerChanged(val on: Boolean, override val timestampMs: Long) : VehicleEvent
    data class SteeringAngleChanged(val degrees: Float, override val timestampMs: Long) : VehicleEvent
    data class OutsideTemperatureChanged(val celsius: Float, override val timestampMs: Long) : VehicleEvent
}

fun interface Subscription {
    /** 등록 때와 같은 Listener·Area로 해제해야 한다(Pleos 규칙). 구현체가 그 짝을 기억한다. */
    fun cancel()
}

/**
 * 차량 접근의 유일한 창구. Pleos 어댑터(android-adapters/)와 테스트용 가짜가 이것을 구현한다.
 * Sample App의 흐름(지원 확인 → 조회 → 구독 → 해제)을 그대로 옮긴 모양이다.
 */
interface VehicleGateway {
    /** checkXXXCapabilities 결과 + 권한 확인을 Availability로 정리 */
    suspend fun availability(signal: VehicleSignal): Availability

    /** getCurrentXXX. 값을 받지 못하면 null (null/unavailable 응답 포함) */
    suspend fun readCurrent(signal: VehicleSignal): VehicleEvent?

    /** registerXXX. 실제 알림이 오기 전까지는 구독 상태일 뿐 값이 들어온 것은 아니다. */
    fun subscribe(signal: VehicleSignal, listener: (VehicleEvent) -> Unit): Subscription
}

/** 여러 신호의 지원 여부를 한 번에 확인 (감지기 선택에 사용) */
suspend fun VehicleGateway.supportsAll(signals: Set<VehicleSignal>): Boolean =
    signals.all { availability(it) == Availability.AVAILABLE }
