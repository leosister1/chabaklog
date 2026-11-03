package chabaklog.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 차박 세션 한 번(어젯밤 기록). 값마다 출처를 함께 저장해 "데이터 출처" 화면과 신뢰도 표시에 쓴다.
 * 시작 시각은 감지 조건이 맞기 시작한 시각(30분 뒤가 아님).
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    /** 감지 전략 id와 신뢰도 (예: detect.p_door / MEDIUM) */
    val detectorId: String,
    val confidence: String,
    val placeId: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationSource: String? = null,
    val socStart: Float? = null,
    val socEnd: Float? = null,
    val outsideTempC: Float? = null,
    val outsideTempSource: String? = null,
    val hvacOnMinutes: Int = 0,
    val doorOpenCount: Int = 0,
    /** 주차 난이도: 후진 횟수, 조향 끝까지 돌린 횟수, 점수와 등급 */
    val reverseCount: Int = 0,
    val fullLockCount: Int = 0,
    val parkingScore: Float? = null,
    val parkingLevel: String? = null,
    /** 시연용으로 넣은 기록. 화면에 "시연용"으로 표시하고 예측에는 쓰지 않는다. */
    val isDemo: Boolean = false,
    /**
     * 끝날 때 내린 최종 판단 (SessionVerdict 이름): PENDING(진행 중) / CAMPING / REST(휴식) / ASK(사용자 확인 필요).
     * 지도·예측에는 CAMPING만 쓴다.
     */
    val verdict: String = "PENDING",
    /** 사용자가 차박 모드로 직접 기록했는지 (자동 감지가 시작한 뒤 켠 경우도 포함) */
    val manual: Boolean = false,
    /** 기록 중 마지막으로 값을 저장한 시각 (30초마다). 앱이 기록 도중 꺼졌을 때 끝 시각을 정하는 데 쓴다. */
    val lastSeenAtMs: Long? = null,
    /** 여정: 출발(P에서 벗어남)부터 이 기록이 시작될 때까지 달린 거리(km, 누적 주행거리 차이) */
    val tripDistanceKm: Float? = null,
    /** 여정: 출발 시각 */
    val tripStartedAtMs: Long? = null,
    /** 여정: 가는 길에 내비가 도착을 알린 경유지·목적지 이름 ("|"로 구분) */
    val tripStops: String? = null,
) {
    val isCamping get() = verdict == "CAMPING"
}

enum class PlaceKind { PUBLIC_CAMPSITE, DEMO_SEED, MINE }

/** 지도에 표시하는 장소. 시드 데이터는 시연용임을 kind로 구분해 화면에 명시한다. */
@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val kind: PlaceKind,
    val note: String? = null,
    val address: String? = null,
    /** 외부 데이터 식별자 (예: 고캠핑 번호) — 시드 중복 방지 */
    val externalId: String? = null,
)
