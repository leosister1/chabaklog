package chabaklog.datasource.parking

import chabaklog.datasource.core.SourceId
import chabaklog.datasource.strategy.SignalStrategy
import chabaklog.datasource.strategy.StrategySelector
import chabaklog.datasource.vehicle.Gear
import chabaklog.datasource.vehicle.VehicleEvent
import chabaklog.datasource.vehicle.VehicleSignal
import kotlin.math.abs

enum class ParkingDifficulty { EASY, MODERATE, HARD }

data class ParkingResult(
    val difficulty: ParkingDifficulty,
    val reverseCount: Int,
    /** 조향을 끝까지 돌린 횟수. 조향각을 읽지 못하는 전략에서는 null */
    val fullLockCount: Int?,
    val durationMs: Long,
)

/**
 * 주차 과정(주행 중 처음 R에 들어간 순간 ~ P 진입)을 관찰해 난이도를 낸다.
 * P에 들어간 이벤트에서 결과를 돌려주고, 그 외에는 null.
 */
interface ParkingEstimator : SignalStrategy {
    fun onEvent(event: VehicleEvent): ParkingResult?
}

data class ParkingConfig(
    /** 이 각도 이상이면 "끝까지 돌림"으로 센다 (차종마다 다르므로 조정 대상) */
    val fullLockDegrees: Float = 450f,
    /** 이 횟수 이하이면 EASY, hardReverseCount 이상이면 HARD */
    val easyReverseCount: Int = 1,
    val hardReverseCount: Int = 4,
)

/** 공통: R 전환 횟수와 주차 시간 추적 */
abstract class ReverseCountingEstimator(protected val config: ParkingConfig) : ParkingEstimator {
    private var maneuverStartMs: Long? = null
    private var reverseCount = 0
    private var lastGear = Gear.UNKNOWN

    protected abstract fun onManeuverEvent(event: VehicleEvent)
    protected abstract fun fullLockCount(): Int?
    protected abstract fun resetExtra()

    final override fun onEvent(event: VehicleEvent): ParkingResult? {
        if (event !is VehicleEvent.GearChanged) {
            if (maneuverStartMs != null) onManeuverEvent(event)
            return null
        }
        val gear = event.gear
        if (gear == Gear.REVERSE && lastGear != Gear.REVERSE) {
            if (maneuverStartMs == null) maneuverStartMs = event.timestampMs
            reverseCount++
        }
        lastGear = gear
        if (gear != Gear.PARK) return null

        val start = maneuverStartMs
        val result = ParkingResult(
            difficulty = score(reverseCount, fullLockCount()),
            reverseCount = reverseCount,
            fullLockCount = fullLockCount(),
            durationMs = if (start == null) 0 else event.timestampMs - start,
        )
        maneuverStartMs = null
        reverseCount = 0
        resetExtra()
        return result
    }

    private fun score(reverses: Int, fullLocks: Int?): ParkingDifficulty {
        val lockPenalty = (fullLocks ?: 0) / 2
        val effective = reverses + lockPenalty
        return when {
            effective <= config.easyReverseCount -> ParkingDifficulty.EASY
            effective >= config.hardReverseCount -> ParkingDifficulty.HARD
            else -> ParkingDifficulty.MODERATE
        }
    }
}

/** 1순위: R 전환 횟수 + 조향 끝까지 돌린 횟수 */
class ReverseAndSteeringEstimator(config: ParkingConfig = ParkingConfig()) : ReverseCountingEstimator(config) {
    override val id = SourceId("parking.reverse_steering", "후진 횟수 + 조향각")
    override val priority = 1
    override val requiredSignals = setOf(VehicleSignal.GEAR, VehicleSignal.STEERING_ANGLE)

    private var fullLocks = 0
    private var atLock = false

    override fun onManeuverEvent(event: VehicleEvent) {
        if (event !is VehicleEvent.SteeringAngleChanged) return
        val nowAtLock = abs(event.degrees) >= config.fullLockDegrees
        if (nowAtLock && !atLock) fullLocks++
        atLock = nowAtLock
    }
    override fun fullLockCount() = fullLocks
    override fun resetExtra() { fullLocks = 0; atLock = false }
}

/** 2순위: R 전환 횟수만 */
class ReverseOnlyEstimator(config: ParkingConfig = ParkingConfig()) : ReverseCountingEstimator(config) {
    override val id = SourceId("parking.reverse_only", "후진 횟수")
    override val priority = 2
    override val requiredSignals = setOf(VehicleSignal.GEAR)
    override fun onManeuverEvent(event: VehicleEvent) = Unit
    override fun fullLockCount(): Int? = null
    override fun resetExtra() = Unit
}

/** 최후 대체: 난이도를 내지 않음 (UI는 "주차 난이도" 항목을 숨긴다) */
class DisabledParkingEstimator : ParkingEstimator {
    override val id = SourceId("parking.disabled", "표시 안 함")
    override val priority = 3
    override val requiredSignals = emptySet<VehicleSignal>()
    override fun onEvent(event: VehicleEvent): ParkingResult? = null
}

fun parkingEstimatorSelector(config: ParkingConfig = ParkingConfig()) = StrategySelector(
    category = "주차 난이도",
    strategies = listOf(ReverseAndSteeringEstimator(config), ReverseOnlyEstimator(config), DisabledParkingEstimator()),
)
