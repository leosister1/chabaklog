package chabaklog.app.adapters

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import chabaklog.datasource.core.Availability
import chabaklog.datasource.location.GeoPoint
import chabaklog.datasource.location.NaviDestinationProvider
import chabaklog.datasource.location.NaviLocationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeoutOrNull

/** 경유지 보급(편의점·화장실)에 쓸 장소 */
data class NaviPlace(val name: String, val latitude: Double, val longitude: Double, val address: String, val poiId: String = "")

/**
 * NaviHelper SDK 3.0.5 래퍼.
 * - 정보 조회 API는 값을 바로 돌려주지 않고 NaviHelperEventListener 콜백으로 준다 → 다음 콜백을 기다려 suspend로 바꾼다.
 * - requestRoute() 성공 = 경로 탐색 완료가 아니다. onRoutingCompleted / onError로 최종 결과를 본다.
 * - 경유지는 최대 2개 (WaypointIndex.FIRST / SECOND).
 */
class NaviHelperGateway(context: Context) : NaviDestinationProvider, NaviLocationProvider {
    private val tag = "CHABAK_NAVI"
    private val navi = NaviHelper(context.applicationContext)

    @Volatile private var connected: Boolean? = null   // null = 연결 시도 중
    private val connectedSignal = CompletableDeferred<Boolean>()

    private var pendingLocation: CompletableDeferred<CurrentLocationInfo>? = null
    private var pendingDestination: CompletableDeferred<DestinationInfo>? = null
    private var pendingWaypoints: CompletableDeferred<List<WaypointInfo>>? = null

    private val _events = MutableSharedFlow<String>(replay = 50, extraBufferCapacity = 50)
    /** 화면에 보여줄 이벤트 기록 */
    val events: SharedFlow<String> = _events

    private fun emit(msg: String) { Log.i(tag, msg); _events.tryEmit(msg) }

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            emit("onCurrentLocationInfo ${currentLocationInfo.latitude}, ${currentLocationInfo.longitude} ${currentLocationInfo.address}")
            pendingLocation?.complete(currentLocationInfo)
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            emit("onDestinationInfo ${destinationInfo.name} ${destinationInfo.latitude}, ${destinationInfo.longitude}")
            pendingDestination?.complete(destinationInfo)
        }
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            emit("onWayPointInfo ${waypointInfoList.size}개 ${waypointInfoList.joinToString { it.name }}")
            pendingWaypoints?.complete(waypointInfoList)
        }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = emit("onRouteStarted")
        override fun onRoutingCompleted() = emit("onRoutingCompleted")
        override fun onRouteCancelled() = emit("onRouteCancelled")
        override fun onDestinationChanged() = emit("onDestinationChanged")
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = emit("onWaypointChanged $waypointChangedInfo")
        override fun onError(errorCode: NaviErrorCode) = emit("onError $errorCode")
    }

    fun connect() {
        try {
            navi.initialize(
                onConnected = {
                    connected = true
                    navi.addListener(listener)
                    emit("NaviHelper 연결됨")
                    connectedSignal.complete(true)
                },
                onFailed = {
                    connected = false
                    emit("NaviHelper 연결 실패: ${it.message}")
                    connectedSignal.complete(false)
                },
            )
        } catch (e: Exception) {
            connected = false
            emit("NaviHelper initialize 예외: ${e.message}")
            connectedSignal.complete(false)
        }
    }

    fun release() {
        try { navi.removeListener(listener); navi.release() } catch (_: Exception) {}
    }

    private suspend fun awaitConnected(): Boolean =
        connected ?: (withTimeoutOrNull(5_000) { connectedSignal.await() } ?: false)

    private suspend fun <T> request(timeoutMs: Long, set: (CompletableDeferred<T>) -> Unit, call: () -> Unit): T? {
        if (!awaitConnected()) return null
        val deferred = CompletableDeferred<T>().also(set)
        return try {
            call()
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            emit("요청 예외: ${e.javaClass.simpleName} ${e.message}"); null
        }
    }

    // ---------- 조회 ----------

    /** 위치 1순위 (NaviLocationProvider) */
    override suspend fun currentLocation(): GeoPoint? =
        request<CurrentLocationInfo>(3_000, { pendingLocation = it }) { navi.getCurrentLocationInfo() }
            ?.let { GeoPoint(it.latitude, it.longitude) }

    suspend fun destination(): DestinationInfo? =
        request<DestinationInfo>(3_000, { pendingDestination = it }) { navi.getDestinationInfo() }

    suspend fun waypoints(): List<WaypointInfo>? =
        request<List<WaypointInfo>>(3_000, { pendingWaypoints = it }) { navi.getWaypointInfo() }

    // ---------- NaviDestinationProvider (위치 2순위) ----------

    override suspend fun availability(): Availability = when (awaitConnected()) {
        true -> Availability.AVAILABLE
        false -> Availability.TEMPORARILY_UNAVAILABLE
    }

    override suspend fun currentDestination(): GeoPoint? =
        destination()?.let { GeoPoint(it.latitude, it.longitude) }

    // ---------- 경로·경유지 ----------

    fun requestRoute(place: NaviPlace) = guarded("requestRoute") {
        navi.requestRoute(
            RouteInfo(
                latitude = place.latitude, longitude = place.longitude,
                poiName = place.name, poiId = place.poiId, address = place.address, poiSubId = "0",
            )
        )
    }

    /** 목적지와 경유지를 한 번에 요청한다 (경로 계산 중 addWaypoint가 경로를 취소하는 문제를 피함) */
    fun requestRouteWithWaypoints(destination: NaviPlace, waypoints: List<NaviPlace>) = guarded("requestRouteWithWaypoints") {
        navi.requestRouteWithWaypoints(
            RouteWithWaypointsRequest(
                destination = RouteInfo(
                    latitude = destination.latitude, longitude = destination.longitude,
                    poiName = destination.name, poiId = destination.poiId, address = destination.address, poiSubId = "0",
                ),
                waypoints = waypoints.mapIndexed { i, w ->
                    RequestWaypointInfo(
                        latitude = w.latitude, longitude = w.longitude, poiName = w.name,
                        waypointIndex = if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                        poiId = w.poiId, address = w.address, poiSubId = "0",
                    )
                },
            )
        )
    }

    fun addWaypoint(place: NaviPlace, index: WaypointIndex = WaypointIndex.FIRST) = guarded("addWaypoint") {
        navi.addWaypoint(
            RequestWaypointInfo(
                latitude = place.latitude, longitude = place.longitude, poiName = place.name,
                waypointIndex = index, poiId = place.poiId, address = place.address, poiSubId = "0",
            )
        )
    }

    fun removeAllWaypoints() = guarded("removeWaypoint(ALL)") { navi.removeWaypoint(WaypointIndex.ALL) }

    fun cancelRoute() = guarded("cancelRoute") { navi.cancelRoute() }

    private fun guarded(name: String, call: () -> Unit) {
        if (connected != true) { emit("$name: NaviHelper 미연결"); return }
        try { call(); emit("$name 요청 보냄") } catch (e: Exception) { emit("$name 예외: ${e.javaClass.simpleName} ${e.message}") }
    }
}
