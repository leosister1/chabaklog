package chabaklog.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import chabaklog.app.ChabaklogApp
import chabaklog.app.adapters.AaosLocationProvider
import chabaklog.app.adapters.NaviHelperGateway
import chabaklog.app.adapters.NaviPlace
import chabaklog.datasource.core.Availability
import chabaklog.datasource.location.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 1단계 "위치 경로 3가지"와 "NaviHelper 목적지·경유지" 확인용.
 * 데이터 출처 화면 아래에 붙여 두고, 2단계에서 DataSourceRegistry 보고로 바꾼다.
 */
class ConnectionTestViewModel(app: ChabaklogApp) : ViewModel() {
    private val aaos = AaosLocationProvider(app)
    val navi = NaviHelperGateway(app).also { it.connect() }

    data class LocationRow(val path: String, val result: String)

    private val _locations = MutableStateFlow<List<LocationRow>>(emptyList())
    val locations: StateFlow<List<LocationRow>> = _locations
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log
    private val _manual = MutableStateFlow<GeoPoint?>(null)

    init {
        viewModelScope.launch { navi.events.collect { e -> _log.update { (listOf(e) + it).take(30) } } }
    }

    fun checkLocations() = viewModelScope.launch {
        val rows = mutableListOf<LocationRow>()
        val avail = aaos.availability()
        rows += LocationRow(
            "1. AAOS LocationManager",
            if (avail == Availability.AVAILABLE) aaos.lastKnown()?.fmt() ?: "켜져 있지만 마지막 위치 없음" else avail.name,
        )
        rows += LocationRow("2a. NaviHelper 현재 위치", navi.currentLocation()?.fmt() ?: "응답 없음")
        rows += LocationRow("2b. NaviHelper 목적지", navi.currentDestination()?.fmt() ?: "목적지 없음 또는 응답 없음")
        rows += LocationRow("3. 지도에서 직접 선택", _manual.value?.fmt() ?: "아직 선택 안 함 (아래 버튼으로 시험)")
        _locations.value = rows
    }

    /** 지도 화면이 생기기 전까지 직접 선택 경로를 시험하는 자리 표시. */
    fun pickManually() { _manual.value = TEST_DESTINATION.let { GeoPoint(it.latitude, it.longitude) }; checkLocations() }

    fun setDestination() = navi.requestRoute(TEST_DESTINATION)
    fun addWaypoint() = navi.addWaypoint(TEST_WAYPOINT)
    fun readWaypoints() = viewModelScope.launch { navi.waypoints() }
    fun removeWaypoints() = navi.removeAllWaypoints()
    fun cancelRoute() = navi.cancelRoute()

    override fun onCleared() { navi.release(); super.onCleared() }

    private fun GeoPoint.fmt() = "%.5f, %.5f".format(latitude, longitude)

    companion object {
        // Pleos NaviHelper 문서 예제 좌표
        val TEST_DESTINATION = NaviPlace("42dot", 37.48544722, 127.03636666, "서울시 강남구 남부순환로 2621", "36khp37hh8gqcm5nqhe5")
        val TEST_WAYPOINT = NaviPlace("소프트웨어드림센터", 37.41275833, 127.09481944, "경기도 성남시 수정구 창업로40번길 6", "36khp2hdj2cfcm5nqhe3")

        fun factory(app: ChabaklogApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConnectionTestViewModel(app) }
        }
    }
}
