package chabaklog.datasource.location

import chabaklog.datasource.core.Availability
import chabaklog.datasource.core.DataSource
import chabaklog.datasource.core.FallbackChain
import chabaklog.datasource.core.Fidelity
import chabaklog.datasource.core.SourceId
import chabaklog.datasource.core.Sourced

data class GeoPoint(val latitude: Double, val longitude: Double, val accuracyMeters: Float? = null)

/**
 * 앱이 다루는 지역(대한민국, 제주·울릉·독도 포함)의 대략적인 경계.
 * 차량·내비가 준 위치가 이 밖이면 잘못된 값으로 보고 다음 경로로 넘어간다.
 * 4단계 실측: 에뮬레이터를 재시작한 직후 내비가 준비되기 전에는 AAOS 기본 GPS(미국 마운틴뷰)가 잡혀
 * 차박지가 지도 밖에 생겼다.
 */
object ServiceArea {
    const val MIN_LAT = 33.0
    const val MAX_LAT = 38.9
    const val MIN_LON = 124.5
    const val MAX_LON = 132.0
    fun contains(p: GeoPoint) = p.latitude in MIN_LAT..MAX_LAT && p.longitude in MIN_LON..MAX_LON
}

private fun GeoPoint.inServiceArea() = takeIf { ServiceArea.contains(it) }

/**
 * 1순위: NaviHelper가 알려주는 차량 현재 위치 (getCurrentLocationInfo).
 * 1단계 실측: 에뮬레이터의 AAOS 위치는 기본 GPS(미국)라 내비가 쓰는 차량 위치와 따로 논다.
 * 내비 위치가 시스템 전체가 쓰는 차량 위치이므로 1순위로 둔다.
 */
interface NaviLocationProvider {
    suspend fun availability(): Availability
    suspend fun currentLocation(): GeoPoint?
}

class NaviCurrentLocationSource(
    private val provider: NaviLocationProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) : DataSource<GeoPoint> {
    override val id = SourceId("location.navi_current", "내비 차량 위치")
    override val priority = 1
    override suspend fun availability() = provider.availability()
    override suspend fun read() =
        provider.currentLocation()?.inServiceArea()?.let { Sourced(it, id, Fidelity.MEASURED, clock()) }
}

/**
 * 2순위: AAOS 표준 위치 API (android.location.LocationManager).
 * 플랫폼 호출은 [PlatformLocationProvider]로 주입받는다. 구현은 android-adapters/AaosLocationProvider.kt.
 */
interface PlatformLocationProvider {
    suspend fun availability(): Availability
    suspend fun lastKnown(): GeoPoint?
}

class AaosLocationSource(
    private val provider: PlatformLocationProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) : DataSource<GeoPoint> {
    override val id = SourceId("location.aaos", "차량 위치(AAOS)")
    override val priority = 2
    override suspend fun availability() = provider.availability()
    override suspend fun read() =
        provider.lastKnown()?.inServiceArea()?.let { Sourced(it, id, Fidelity.MEASURED, clock()) }
}

/**
 * 3순위: NaviHelper에 설정된 목적지 좌표.
 * 차박지에 "도착한 뒤"에는 목적지 = 현재 장소라는 가정을 쓴다. 목적지가 없으면 null.
 */
interface NaviDestinationProvider {
    suspend fun availability(): Availability
    suspend fun currentDestination(): GeoPoint?
}

class NaviDestinationSource(
    private val provider: NaviDestinationProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) : DataSource<GeoPoint> {
    override val id = SourceId("location.navi_destination", "내비 목적지 좌표")
    override val priority = 3
    override suspend fun availability() = provider.availability()
    override suspend fun read() =
        provider.currentDestination()?.inServiceArea()?.let { Sourced(it, id, Fidelity.DERIVED, clock()) }
}

/**
 * 최후 대체: 사용자가 지도에서 직접 고른 위치. 항상 AVAILABLE이고,
 * 아직 고르지 않았으면 read()가 null → UI가 "위치를 지도에서 골라주세요"를 띄운다.
 */
class ManualPickSource(
    private val clock: () -> Long = System::currentTimeMillis,
) : DataSource<GeoPoint> {
    override val id = SourceId("location.manual", "지도에서 직접 선택")
    override val priority = 4

    @Volatile private var picked: GeoPoint? = null

    fun pick(point: GeoPoint) { picked = point }
    fun clear() { picked = null }

    override suspend fun availability() = Availability.AVAILABLE
    override suspend fun read() = picked?.let { Sourced(it, id, Fidelity.USER_PROVIDED, clock()) }
}

fun locationChain(
    naviLocation: NaviLocationProvider,
    platform: PlatformLocationProvider,
    navi: NaviDestinationProvider,
    manual: ManualPickSource,
) = FallbackChain(
    category = "위치",
    sources = listOf(NaviCurrentLocationSource(naviLocation), AaosLocationSource(platform), NaviDestinationSource(navi), manual),
)
