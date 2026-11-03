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
import chabaklog.app.tracking.Appliance
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

/** 내비 requestTrip 결과: 거리(m), 소요 시간(초), 도착 시 예상 배터리(%), 예상 사용 에너지(Wh) */
data class TripEstimate(val distanceM: Int, val etaSec: Int, val socAtArrival: Float, val energyWh: Double)

/**
 * 데이터 출처 화면에서 따로 확인하는 값 (LiveState에 없는 것).
 * null = 읽을 수 없음. 화면에서 대체값과 함께 보여준다.
 */
data class SourceExtras(
    val batteryKwh: Float? = null,
    val odometerKm: Float? = null,
    /** true 연결됨, false 연결 안 됨, null 확인 중 */
    val naviConnected: Boolean? = null,
    /** 날씨 예보(Open-Meteo)에 닿는지. null 확인 중 */
    val internet: Boolean? = null,
    /** 예측에 쓸 수 있는 내 차박 기록 수 (시연용 제외, 1시간 이상, 배터리·기온 있음) */
    val usableNights: Int = 0,
    val campsites: Int = 0,
    val toilets: Int = 0,
    val convenience: Int = 0,
    val supermarkets: Int = 0,
    val chargers: Int = 0,
    val climateStations: Int = 0,
    val checked: Boolean = false,
)

enum class MapFilter(val label: String) { PUBLIC("공공 캠핑장"), MINE("내 차박지"), RECORDED("기록 있는 곳만") }

class MainViewModel(private val app: ChabaklogApp) : ViewModel() {
    private val db = app.database

    val live: StateFlow<LiveState> = app.tracker.state
    val demoMode: StateFlow<Boolean> = app.demoMode
    val v2l: StateFlow<Set<Appliance>> = app.v2l
    val outline get() = app.outline.rings

    val sessions: StateFlow<List<SessionEntity>> =
        db.sessions().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val latest: StateFlow<SessionEntity?> =
        db.sessions().observeLatestEnded().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val placeSummaries: StateFlow<List<PlaceSummary>> =
        combine(db.places().observeAll(), db.sessions().observeAll()) { places, sessions ->
            val byPlace = sessions.groupBy { it.placeId }
            places.map { p ->
                // 지도에는 차박으로 확정된 기록만 센다 (휴식·확인 전·진행 중 제외)
                val list = byPlace[p.id].orEmpty().filter { it.isCamping }
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
                // 휴식으로 끝난 곳에 만들어진 "내 차박지"는 지도에 띄우지 않는다
                .filter { it.place.kind != PlaceKind.MINE || it.hasRecords }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---------- 지도 ----------
    val filters = MutableStateFlow(setOf(MapFilter.PUBLIC, MapFilter.MINE))
    val selected = MutableStateFlow<PlaceSummary?>(null)
    val selectedForecast = MutableStateFlow<Forecast?>(null)
    /** 고른 장소까지의 경로 정보. null = 계산 중 또는 받지 못함 (tripLoaded로 구분) */
    val selectedTrip = MutableStateFlow<TripEstimate?>(null)
    val tripLoaded = MutableStateFlow(false)
    val vehiclePoint = MutableStateFlow<GeoPoint?>(null)
    val message = MutableStateFlow<String?>(null)

    fun toggleFilter(f: MapFilter) { filters.value = filters.value.let { if (f in it) it - f else it + f } }

    fun select(summary: PlaceSummary?) {
        selected.value = summary
        selectedForecast.value = null
        selectedTrip.value = null
        tripLoaded.value = false
        summary ?: return
        viewModelScope.launch {
            selectedForecast.value = app.forecaster.forPlace(GeoPoint(summary.place.latitude, summary.place.longitude))
        }
        viewModelScope.launch {
            val p = summary.place
            val trip = app.navi.trip(NaviPlace(p.name, p.latitude, p.longitude, p.address ?: ""))
            // 다른 장소를 그 사이 골랐으면 버린다
            if (selected.value?.place?.id == p.id) {
                selectedTrip.value = trip?.let {
                    TripEstimate(it.distance, it.eta, it.estimatedSocAtDest.toFloat(), it.energyConsumption)
                }
                tripLoaded.value = true
            }
        }
    }

    fun setDestination(p: PlaceEntity) {
        app.navi.requestRoute(NaviPlace(p.name, p.latitude, p.longitude, p.address ?: ""))
        message.value = "내비 목적지로 보냈어요: ${p.name}"
    }

    /** 공공 캠핑장일 때만 근처 화장실 후보가 있다 */
    fun toiletsNear(p: PlaceEntity): List<NearbyToilet> =
        if (p.kind == PlaceKind.PUBLIC_CAMPSITE) app.toilets.near(p.name) else emptyList()

    /** 공공 캠핑장 근처 편의점·마트·충전소 (OpenStreetMap 내장) */
    fun poisNear(p: PlaceEntity, kind: chabaklog.app.data.PoiKind): List<NearbyToilet> =
        if (p.kind == PlaceKind.PUBLIC_CAMPSITE) app.pois.near(p.name, kind) else emptyList()

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

    /** V2L 가전을 고르거나 빼면 홈·장소 카드 예측을 바로 다시 계산한다 */
    fun toggleAppliance(a: Appliance) {
        app.toggleAppliance(a)
        viewModelScope.launch {
            vehiclePoint.value?.let { homeForecast.value = app.forecaster.forPlace(it, vehicleTempC = live.value.outsideTempC) }
            selected.value?.let { s ->
                selectedForecast.value = app.forecaster.forPlace(GeoPoint(s.place.latitude, s.place.longitude))
            }
        }
    }

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
    /** 홈의 차박 모드 스위치 */
    fun setCampMode(on: Boolean) = if (on) app.tracker.campModeOn() else app.tracker.campModeOff()

    /** 아침 요약의 "어젯밤 차박이었나요?" 답 */
    fun answerVerdict(sessionId: Long, wasCamping: Boolean) = viewModelScope.launch {
        db.sessions().setVerdict(sessionId, if (wasCamping) "CAMPING" else "REST")
        message.value = if (wasCamping) "차박으로 기록했어요" else "휴식으로 바꿨어요. 예측에는 쓰지 않아요"
    }
    val sourceExtras = MutableStateFlow(SourceExtras())

    /** 데이터 출처 화면: 위치·감지 경로를 다시 고르고, 차량·내비·인터넷·내장 데이터를 다시 확인한다 */
    fun refreshSources() {
        app.tracker.refreshSourcesAsync()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val usable = db.sessions().realFinished().count {
                val end = it.endedAtMs
                end != null && it.socStart != null && it.socEnd != null && it.outsideTempC != null &&
                    end - it.startedAtMs >= 3_600_000L
            }
            sourceExtras.value = SourceExtras(
                batteryKwh = app.vehicle.batteryCapacityKwh(),
                odometerKm = app.vehicle.odometerKm(),
                naviConnected = app.navi.isConnected,
                internet = runCatching { app.weather.isReachable() }.getOrDefault(false),
                usableNights = usable,
                campsites = runCatching { chabaklog.app.data.loadPublicCampsites(app).size }.getOrDefault(0),
                toilets = app.toilets.count,
                convenience = app.pois.count(chabaklog.app.data.PoiKind.CONVENIENCE),
                supermarkets = app.pois.count(chabaklog.app.data.PoiKind.SUPERMARKET),
                chargers = app.pois.count(chabaklog.app.data.PoiKind.CHARGING),
                climateStations = app.climate.stationCount,
                checked = true,
            )
        }
    }

    companion object {
        fun factory(app: ChabaklogApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(app) }
        }
    }
}
