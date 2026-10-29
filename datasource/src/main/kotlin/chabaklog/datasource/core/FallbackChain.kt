package chabaklog.datasource.core

/** 체인이 마지막으로 어떤 소스를 썼는지, 앞선 소스는 왜 건너뛰었는지 기록한다. */
data class Resolution<T>(
    val result: Sourced<T>?,
    val skipped: List<Pair<SourceId, Availability>>,
) {
    val usedSource: SourceId? get() = result?.source
}

/**
 * 우선순위대로 소스를 시도해 처음 성공한 값을 돌려준다.
 *
 * 규칙
 * 1. priority 오름차순으로 availability()를 확인한다.
 * 2. AVAILABLE이면 read()를 시도하고, null이면 TEMPORARILY_UNAVAILABLE로 보고 다음으로 넘어간다.
 * 3. UNSUPPORTED·PERMISSION_DENIED는 앱 실행 동안 다시 바뀌지 않으므로 기억해 두고 이후 호출에서 건너뛴다.
 *    TEMPORARILY_UNAVAILABLE은 매번 다시 시도한다(네트워크가 돌아올 수 있음).
 * 4. 모든 소스가 실패하면 result = null. 각 카테고리는 항상 성공하는 마지막 대체(내장표·사용자 입력)를 두는 것을 원칙으로 한다.
 */
class FallbackChain<T>(
    val category: String,
    sources: List<DataSource<T>>,
) {
    private val ordered = sources.sortedBy { it.priority }
    private val permanentlySkipped = mutableMapOf<SourceId, Availability>()

    var lastResolution: Resolution<T>? = null
        private set

    init {
        require(ordered.isNotEmpty()) { "$category: 소스가 하나 이상 필요합니다" }
        require(ordered.map { it.priority }.toSet().size == ordered.size) {
            "$category: priority가 중복되었습니다"
        }
    }

    suspend fun resolve(): Resolution<T> {
        val skipped = mutableListOf<Pair<SourceId, Availability>>()
        for (source in ordered) {
            val remembered = permanentlySkipped[source.id]
            if (remembered != null) {
                skipped += source.id to remembered
                continue
            }
            val availability = source.availability()
            if (availability != Availability.AVAILABLE) {
                if (availability.isPermanent()) permanentlySkipped[source.id] = availability
                skipped += source.id to availability
                continue
            }
            val value = source.read()
            if (value == null) {
                skipped += source.id to Availability.TEMPORARILY_UNAVAILABLE
                continue
            }
            return Resolution(value, skipped).also { lastResolution = it }
        }
        return Resolution<T>(null, skipped).also { lastResolution = it }
    }

    /** 설정 화면용: 기억해 둔 영구 실패를 지우고 다음 resolve()에서 처음부터 다시 확인한다. */
    fun resetProbes() = permanentlySkipped.clear()

    private fun Availability.isPermanent() =
        this == Availability.UNSUPPORTED || this == Availability.PERMISSION_DENIED
}
