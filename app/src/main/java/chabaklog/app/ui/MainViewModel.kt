package chabaklog.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import chabaklog.app.ChabaklogApp
import chabaklog.app.adapters.NaviPlace
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
