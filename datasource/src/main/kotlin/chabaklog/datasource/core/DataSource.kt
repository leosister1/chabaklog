package chabaklog.datasource.core

/**
 * 한 데이터 소스가 지금 쓸 수 있는 상태인지.
 * Pleos의 checkXXXCapabilities 결과와 권한·연결 상태를 이 값 하나로 정리한다.
 */
enum class Availability {
    AVAILABLE,
    /** 차종·AVD가 해당 속성(또는 Area)을 지원하지 않음 */
    UNSUPPORTED,
    /** Manifest 미선언 또는 승인 범위 밖 */
    PERMISSION_DENIED,
    /** 일시적 실패(서비스 미연결, 네트워크 없음 등). 나중에 다시 시도할 가치가 있음 */
    TEMPORARILY_UNAVAILABLE,
}

/** 설정 > 데이터 출처 화면에 보여줄 소스 식별자 */
data class SourceId(
    val key: String,
    /** 사용자에게 보여줄 이름 (예: "차량 외기온 센서") */
    val label: String,
)

/** 측정값이 실제 측정인지, 추정·대체값인지 구분한다. 예측 화면에서 신뢰도 표시에 쓴다. */
enum class Fidelity { MEASURED, DERIVED, ESTIMATED, USER_PROVIDED }

data class Sourced<T>(
    val value: T,
    val source: SourceId,
    val fidelity: Fidelity,
    val timestampMs: Long,
)

/**
 * 값을 한 번 읽어오는 소스. 위치·외기온처럼 "지금 값"이 필요한 항목에 쓴다.
 * priority가 낮을수록 먼저 시도한다 (1 = 1순위).
 */
interface DataSource<T> {
    val id: SourceId
    val priority: Int

    suspend fun availability(): Availability

    /** 값을 읽지 못하면 null. 예외는 던지지 않고 null + availability로 표현한다. */
    suspend fun read(): Sourced<T>?
}
