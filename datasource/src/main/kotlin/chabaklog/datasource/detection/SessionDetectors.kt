package chabaklog.datasource.detection

import chabaklog.datasource.core.SourceId
import chabaklog.datasource.strategy.SignalStrategy
import chabaklog.datasource.strategy.StrategySelector
import chabaklog.datasource.vehicle.Gear
import chabaklog.datasource.vehicle.VehicleEvent
import chabaklog.datasource.vehicle.VehicleSignal

enum class Confidence { HIGH, MEDIUM, USER_DECLARED }

sealed interface SessionDecision {
    data object None : SessionDecision
    data class Started(val atMs: Long, val confidence: Confidence) : SessionDecision
    data class Ended(val atMs: Long) : SessionDecision
}

/**
 * 이벤트를 받아 차박 세션 시작/종료를 판단한다.
 * 시간 경과만으로 시작이 결정되는 경우가 있으므로 onTick()을 주기적으로(예: 1분) 호출한다.
 */
interface SessionDetector : SignalStrategy {
    fun onEvent(event: VehicleEvent): SessionDecision
    fun onTick(nowMs: Long): SessionDecision
}

data class DetectionConfig(
    /** P단 정차가 이만큼 이어지면 차박으로 본다 */
    val dwellMs: Long = 30 * 60_000L,
)

/** 공통: P단 유지 시간과 세션 상태를 관리하고, 추가 조건만 하위 클래스가 정한다. */
abstract class DwellBasedDetector(protected val config: DetectionConfig) : SessionDetector {
    private var parkedSinceMs: Long? = null
    private var inSession = false

    /** 추가 조건이 지금 만족되는지, 만족되기 시작한 시각 */
    protected abstract fun conditionSince(): Long?
    protected abstract fun handleExtra(event: VehicleEvent)
    protected abstract val confidence: Confidence

    final override fun onEvent(event: VehicleEvent): SessionDecision {
        if (event is VehicleEvent.GearChanged) {
            if (event.gear == Gear.PARK) {
                if (parkedSinceMs == null) parkedSinceMs = event.timestampMs
            } else {
                parkedSinceMs = null
                if (inSession) {
                    inSession = false
                    return SessionDecision.Ended(event.timestampMs)
                }
            }
        }
        handleExtra(event)
        return check(event.timestampMs)
    }

    final override fun onTick(nowMs: Long) = check(nowMs)

    private fun check(nowMs: Long): SessionDecision {
        if (inSession) return SessionDecision.None
        val parked = parkedSinceMs ?: return SessionDecision.None
        val cond = conditionSince() ?: return SessionDecision.None
        val since = maxOf(parked, cond)
        if (nowMs - since >= config.dwellMs) {
            inSession = true
            return SessionDecision.Started(since, confidence)
        }
        return SessionDecision.None
    }
}

/**
 * 2순위: P단 + 좌석 점유가 dwell 이상 유지.
 * 1단계 실측에서 에뮬레이터의 좌석 점유 값이 반대로 나와(앱 어댑터에서 보정) 문 신호가 없을 때만 쓴다.
 */
class SeatOccupancyDetector(config: DetectionConfig = DetectionConfig()) : DwellBasedDetector(config) {
    override val id = SourceId("detect.seat", "기어 P + 좌석 점유")
    override val priority = 2
    override val requiredSignals = setOf(VehicleSignal.GEAR, VehicleSignal.SEAT_OCCUPANCY)
    override val confidence = Confidence.HIGH

    private var occupiedSinceMs: Long? = null
    override fun conditionSince() = occupiedSinceMs
    override fun handleExtra(event: VehicleEvent) {
        if (event is VehicleEvent.SeatOccupancyChanged) {
            occupiedSinceMs = if (event.anyOccupied) occupiedSinceMs ?: event.timestampMs else null
        }
    }
}

/**
 * 1순위: P단 + 마지막으로 문이 닫힌 뒤 dwell 동안 문이 닫혀 있음.
 * "사람이 타고 문을 닫은 채 오래 머문다"는 추정이라 신뢰도는 MEDIUM.
 * 문이 한 번도 열리고 닫힌 적이 없으면(주차 후 내리지 않음) P단 진입 시각을 기준으로 한다.
 */
class DoorPatternDetector(config: DetectionConfig = DetectionConfig()) : DwellBasedDetector(config) {
    override val id = SourceId("detect.door", "기어 P + 문 닫힘 유지")
    override val priority = 1
    override val requiredSignals = setOf(VehicleSignal.GEAR, VehicleSignal.DOOR_OPEN)
    override val confidence = Confidence.MEDIUM

    private var doorOpen = false
    private var closedSinceMs: Long? = 0L // 0 = 처음부터 닫혀 있었음 → P단 시각이 기준이 됨
    override fun conditionSince() = if (doorOpen) null else closedSinceMs
    override fun handleExtra(event: VehicleEvent) {
        if (event !is VehicleEvent.DoorChanged) return
        // 문 4개의 알림이 따로 오므로 "닫힘" 알림이 반복될 수 있다. 열림 → 닫힘으로 바뀔 때만 기준 시각을 갱신한다.
        if (event.anyOpen) {
            doorOpen = true
            closedSinceMs = null
        } else if (doorOpen) {
            doorOpen = false
            closedSinceMs = event.timestampMs
        }
    }
}

/** 최후 대체: 사용자가 버튼으로 시작/종료. 차량 신호가 필요 없다. */
class ManualSessionDetector : SessionDetector {
    override val id = SourceId("detect.manual", "직접 시작 버튼")
    override val priority = 3
    override val requiredSignals = emptySet<VehicleSignal>()

    private var pending: SessionDecision = SessionDecision.None

    fun userStarted(atMs: Long) { pending = SessionDecision.Started(atMs, Confidence.USER_DECLARED) }
    fun userEnded(atMs: Long) { pending = SessionDecision.Ended(atMs) }

    override fun onEvent(event: VehicleEvent) = consume()
    override fun onTick(nowMs: Long) = consume()
    private fun consume() = pending.also { pending = SessionDecision.None }
}

fun sessionDetectorSelector(config: DetectionConfig = DetectionConfig()) = StrategySelector(
    category = "차박 감지",
    strategies = listOf(DoorPatternDetector(config), SeatOccupancyDetector(config), ManualSessionDetector()),
)
