package chabaklog.datasource.detection

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 기록이 끝났을 때 내리는 최종 판단.
 * - CAMPING: 차박. 아침 요약·지도·예측에 쓴다.
 * - REST: 짧은 휴식(충전 대기, 휴게소 낮잠 등). 기록에만 남기고 예측에는 쓰지 않는다.
 * - ASK: 애매함. 아침 요약에서 사용자에게 한 번 묻는다. 답하기 전에는 예측에 쓰지 않는다.
 * - PENDING: 아직 진행 중(끝나지 않음).
 */
enum class SessionVerdict { PENDING, CAMPING, REST, ASK }

data class ClassificationConfig(
    /** 이 시간 이상 머물러야 차박으로 본다 */
    val minDurationMs: Long = 3 * 60 * 60_000L,
    /** 밤 시간대 (시작 시각 ~ 다음 날 끝 시각). 이 시간대에 조금이라도 걸쳐야 차박으로 본다 */
    val nightStart: LocalTime = LocalTime.of(22, 0),
    val nightEnd: LocalTime = LocalTime.of(6, 0),
    /** 시연 모드: 시간·밤 조건을 끄고 끝난 기록을 모두 차박으로 본다 */
    val demo: Boolean = false,
)

/**
 * 자동 감지는 "30분 머묾"으로 넓게 시작하고, 차박인지는 끝날 때 정한다.
 *
 * | 길이(3시간 이상) | 밤에 걸침 | 판단 |
 * | O | O | CAMPING |
 * | X | X | REST |
 * | 둘 중 하나만 | | ASK |
 *
 * 사용자가 차박 모드로 직접 기록한 밤은 조건 없이 CAMPING.
 * 끝난 곳이 공공 캠핑장이거나 이미 차박으로 확정된 적 있는 내 차박지면 애매한 기록(ASK)을 CAMPING으로 올린다.
 * 자동 기록 중에 충전이 감지됐으면 그다음 한 단계 낮춘다(CAMPING → ASK, ASK → REST):
 * 충전 대기는 차박에서 빼고, 충전기가 있는 캠핑장에서 보낸 밤은 아침에 사용자에게 묻는다.
 */
class SessionClassifier(
    private val config: ClassificationConfig = ClassificationConfig(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun classify(
        startMs: Long,
        endMs: Long,
        userDeclared: Boolean,
        charging: Boolean = false,
        atKnownSpot: Boolean = false,
    ): SessionVerdict {
        if (userDeclared) return SessionVerdict.CAMPING
        val raw = if (config.demo) SessionVerdict.CAMPING else {
            val long = endMs - startMs >= config.minDurationMs
            val night = overlapsNight(startMs, endMs)
            when {
                long && night -> SessionVerdict.CAMPING
                !long && !night -> SessionVerdict.REST
                else -> SessionVerdict.ASK
            }
        }
        val base = if (atKnownSpot && raw == SessionVerdict.ASK) SessionVerdict.CAMPING else raw
        if (!charging) return base
        return when (base) {
            SessionVerdict.CAMPING -> SessionVerdict.ASK
            else -> SessionVerdict.REST
        }
    }

    /** [startMs, endMs)가 어느 날이든 밤 시간대와 겹치는지 */
    fun overlapsNight(startMs: Long, endMs: Long): Boolean {
        if (endMs <= startMs) return false
        val firstDay = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate().minusDays(1)
        val lastDay = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate()
        var day = firstDay
        while (!day.isAfter(lastDay)) {
            val nightStartMs = day.atTime(config.nightStart).atZone(zone).toInstant().toEpochMilli()
            val endDay = if (config.nightEnd <= config.nightStart) day.plus(1, ChronoUnit.DAYS) else day
            val nightEndMs = endDay.atTime(config.nightEnd).atZone(zone).toInstant().toEpochMilli()
            if (startMs < nightEndMs && endMs > nightStartMs) return true
            day = day.plusDays(1)
        }
        return false
    }
}
