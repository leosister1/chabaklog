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
)

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
