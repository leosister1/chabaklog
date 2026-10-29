package chabaklog.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import chabaklog.app.ChabaklogApp
import chabaklog.app.adapters.NaviPlace
import chabaklog.app.data.NearbyToilet
import chabaklog.app.data.PlaceEntity
import chabaklog.app.data.PlaceKind
import chabaklog.app.data.SessionEntity
import chabaklog.app.tracking.Forecast
import chabaklog.app.tracking.LiveState
import chabaklog.datasource.location.GeoPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 지도·장소 카드에 쓰는 장소별 요약 */
data class PlaceSummary(
    val place: PlaceEntity,
    val realCount: Int,
    val demoCount: Int,
    /** 가장 자주 나온 주차 난이도 */
    val parkingLevel: String?,
    /** 실제 기록 기준 평균 배터리 사용량(%) */
    val avgSocUsed: Float?,
) {
    val hasRecords get() = realCount + demoCount > 0
}

enum class MapFilter(val label: String) { PUBLIC("공공 캠핑장"), MINE("내 차박지"), RECORDED("기록 있는 곳만") }

class MainViewModel(private val app: ChabaklogApp) : ViewModel() {
    private val db = app.database

    val live: StateFlow<LiveState> = app.tracker.state
    val demoMode: StateFlow<Boolean> = app.demoMode
    val outline get() = app.outline.rings

    val sessions: StateFlow<List<SessionEntity>> =
        db.sessions().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val latest: StateFlow<SessionEntity?> =
        db.sessions().observeLatestEnded().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val placeSummaries: StateFlow<List<PlaceSummary>> =
        combine(db.places().observeAll(), db.sessions().observeAll()) { places, sessions ->
            val byPlace = sessions.groupBy { it.placeId }
            places.map { p ->
                val list = byPlace[p.id].orEmpty()
                val real = list.filter { !it.isDemo }
                val used = real.mapNotNull { s -> if (s.socStart != null && s.socEnd != null) s.socStart - s.socEnd else null }
                PlaceSummary(
                    place = p,
                    realCount = real.size,
                    demoCount = list.size - real.size,
                    parkingLevel = list.mapNotNull { it.parkingLevel }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
                    avgSocUsed = if (used.isEmpty()) null else used.average().toFloat(),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---------- 지도 ----------
    val filters = MutableStateFlow(setOf(MapFilter.PUBLIC, MapFilter.MINE))
    val selected = MutableStateFlow<PlaceSummary?>(null)
    val selectedForecast = MutableStateFlow<Forecast?>(null)
    val vehiclePoint = MutableStateFlow<GeoPoint?>(null)
    val message = MutableStateFlow<String?>(null)

    fun toggleFilter(f: MapFilter) { filters.value = filters.value.let { if (f in it) it - f else it + f } }

    fun select(summary: PlaceSummary?) {
        selected.value = summary
        selectedForecast.value = null
        summary ?: return
        viewModelScope.launch {
            selectedForecast.value = app.forecaster.forPlace(GeoPoint(summary.place.latitude, summary.place.longitude))
        }
    }

    fun setDestination(p: PlaceEntity) {
        app.navi.requestRoute(NaviPlace(p.name, p.latitude, p.longitude, p.address ?: ""))
        message.value = "내비 목적지로 보냈어요: ${p.name}"
    }

    /** 공공 캠핑장일 때만 근처 화장실 후보가 있다 */
    fun toiletsNear(p: PlaceEntity): List<NearbyToilet> =
        if (p.kind == PlaceKind.PUBLIC_CAMPSITE) app.toilets.near(p.name) else emptyList()

    /**
     * 화장실을 경유지로 넣는다. 경유지는 경로가 있어야 붙으므로, 내비 목적지가 이 캠핑장이 아니면 먼저 목적지로 보낸다.
     * NaviHelper 경유지는 최대 2개 → 늘 FIRST 자리를 쓴다(다시 누르면 교체).
     */
    fun addToiletWaypoint(camp: PlaceEntity, t: NearbyToilet) {
        viewModelScope.launch {
            val navi = app.navi
            val toilet = NaviPlace(t.name, t.point.latitude, t.point.longitude, t.address)
            fun matches(name: String) = name.isNotBlank() && (name.contains(t.name) || t.name.contains(name))

            suspend fun routedHere(): Boolean {
                val dest = navi.destination() ?: return false
                return dest.latitude != 0.0 &&
                    chabaklog.app.data.distanceMeters(GeoPoint(dest.latitude, dest.longitude), GeoPoint(camp.latitude, camp.longitude)) < 300
            }

            /** 이 캠핑장으로 가는 경로를 잡는다. 에뮬레이터 내비는 계산 완료 알림을 주지 않아 목적지가 바뀐 것을 확인하고 여유를 둔다 */
            suspend fun ensureRoute() {
                if (routedHere()) return
                navi.requestRoute(NaviPlace(camp.name, camp.latitude, camp.longitude, camp.address ?: ""))
                withTimeoutOrNull(10_000) { while (!routedHere()) delay(1_000) }
                delay(2_500)
            }

            /** 경유지를 넣고 실제로 붙었는지 확인. 결과와 그 사이 내비 알림을 돌려준다 */
            suspend fun tryAdd(): Pair<Boolean, List<String>> {
                val seen = mutableListOf<String>()
                val skip = navi.events.replayCache.size
                val watcher = launch { navi.events.drop(skip).collect { seen += it } }
                navi.addWaypoint(toilet)
                // 경유지를 넣으면 내비가 기존 경로를 취소(onRouteCancelled)하고 경유지를 포함해 다시 계산한다.
                // → 취소 알림에서 멈추지 말고, 경유지 목록에 나타날 때까지 1.5초 간격으로 확인 (최대 12초)
                val ok = withTimeoutOrNull(12_000) {
                    delay(1_500)
                    while (navi.waypoints().orEmpty().none { matches(it.name) }) delay(1_500)
                    true
                } ?: false
                watcher.cancel()
                return ok to seen.filterNot { it.startsWith("onCurrentLocationInfo") || it.startsWith("onWayPointInfo") || it.startsWith("onDestinationInfo") }
                    .map { it.substringBefore(" ").removePrefix("on") }
            }

            message.value = "${t.name} 경유지 넣는 중…"
            suspend fun waitForToilet(timeoutMs: Long) = withTimeoutOrNull(timeoutMs) {
                delay(1_500)
                while (navi.waypoints().orEmpty().none { matches(it.name) }) delay(1_500)
                true
            } ?: false

            var ok = false
            var notes: List<String> = emptyList()
            if (!routedHere()) {
                // 아직 이 캠핑장으로 가는 경로가 없으면 목적지와 화장실을 한 번에 요청한다 (4단계)
                navi.requestRouteWithWaypoints(
                    NaviPlace(camp.name, camp.latitude, camp.longitude, camp.address ?: ""), listOf(toilet),
                )
                ok = waitForToilet(10_000)
                if (ok) { message.value = "경유지에 넣었어요: ${t.name} → ${camp.name}"; return@launch }
                notes = listOf("한번에요청실패")
            }
            // 이미 경로가 있거나 한 번에 요청이 안 될 때: 경로를 확인하고 경유지를 덧붙인다
            ensureRoute()
            val existing = navi.waypoints().orEmpty()
            if (existing.any { matches(it.name) }) {
                message.value = "이미 경유지에 있어요: ${t.name} → ${camp.name}"; return@launch
            }
            if (existing.size >= 2) {
                message.value = "내비 경유지가 이미 2곳이라 더 넣을 수 없어요. 내비에서 경유지를 지운 뒤 다시 눌러 주세요"; return@launch
            }
            tryAdd().let { ok = it.first; notes = notes + it.second }
            if (!ok) {
                // 경로 계산 중에 넣으면 내비가 경로를 취소한다(10/29 확인) → 경로를 다시 잡고 한 번 더
                message.value = "${t.name} 경유지 다시 시도 중…"
                ensureRoute()
                tryAdd().let { ok = it.first; notes = notes + listOf("재시도") + it.second }
            }
            message.value = if (ok) "경유지에 넣었어요: ${t.name} → ${camp.name}"
            else "내비가 경유지를 받지 않았어요: ${t.name} (내비 알림: ${notes.joinToString(",").ifEmpty { "없음" }})"
        }
    }

    fun pickHere(p: PlaceEntity) {
        app.tracker.pickLocationManually(GeoPoint(p.latitude, p.longitude))
        message.value = "위치를 직접 정했어요: ${p.name} (내비·GPS 위치를 못 받을 때 사용)"
    }

    // ---------- 홈: 오늘 밤 예상 ----------
    val homeForecast = MutableStateFlow<Forecast?>(null)

    init {
        viewModelScope.launch {
            // 앱이 막 켜졌을 때는 차량 외기온이 아직 없다. 잠시 기다렸다가 계산해야 홈(지금 차 위치)에 센서 값을 쓴다.
            withTimeoutOrNull(15_000) { live.first { it.outsideTempC != null } }
            while (true) {
                val point = app.navi.currentLocation()
                vehiclePoint.value = point
                if (point != null) {
                    homeForecast.value = app.forecaster.forPlace(point, vehicleTempC = live.value.outsideTempC)
                }
                delay(5 * 60_000L)
            }
        }
    }

    // ---------- 시연용 데이터 ----------
    fun insertDemo() = viewModelScope.launch {
        app.places.insertDemoSessions(System.currentTimeMillis())
        message.value = "시연용 기록 3건을 넣었어요 (화면에 '시연용'으로 표시)"
    }
    fun clearDemo() = viewModelScope.launch {
        app.places.clearDemo()
        message.value = "시연용 기록을 지웠어요"
    }

    fun setDemoMode(on: Boolean) = app.setDemoMode(on)
    fun manualStart() = app.tracker.manualStart()
    fun manualEnd() = app.tracker.manualEnd()
    fun refreshSources() = app.tracker.refreshSourcesAsync()

    companion object {
        fun factory(app: ChabaklogApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(app) }
        }
    }
}
