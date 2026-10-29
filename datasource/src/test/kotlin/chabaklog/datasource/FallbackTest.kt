package chabaklog.datasource

import chabaklog.datasource.core.Availability
import chabaklog.datasource.core.Fidelity
import chabaklog.datasource.detection.Confidence
import chabaklog.datasource.detection.DetectionConfig
import chabaklog.datasource.detection.DoorPatternDetector
import chabaklog.datasource.detection.ManualSessionDetector
import chabaklog.datasource.detection.SeatOccupancyDetector
import chabaklog.datasource.detection.SessionDecision
import chabaklog.datasource.location.GeoPoint
import chabaklog.datasource.location.NaviDestinationProvider
import chabaklog.datasource.location.NaviLocationProvider
import chabaklog.datasource.location.PlatformLocationProvider
import chabaklog.datasource.parking.DisabledParkingEstimator
import chabaklog.datasource.parking.ParkingDifficulty
import chabaklog.datasource.parking.ReverseAndSteeringEstimator
import chabaklog.datasource.parking.ReverseOnlyEstimator
import chabaklog.datasource.temperature.ClimateTable
import chabaklog.datasource.temperature.WeatherClient
import chabaklog.datasource.vehicle.Gear
import chabaklog.datasource.vehicle.VehicleEvent.DoorChanged
import chabaklog.datasource.vehicle.VehicleEvent.GearChanged
import chabaklog.datasource.vehicle.VehicleEvent.OutsideTemperatureChanged
import chabaklog.datasource.vehicle.VehicleEvent.SeatOccupancyChanged
import chabaklog.datasource.vehicle.VehicleEvent.SteeringAngleChanged
import chabaklog.datasource.vehicle.VehicleSignal
import kotlinx.coroutines.test.runTest
import java.time.Month
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private val CAMPSITE = GeoPoint(37.0, 128.0)
private const val MIN = 60_000L

private class FakeLocation(var avail: Availability, var point: GeoPoint?) : PlatformLocationProvider {
    var calls = 0
    override suspend fun availability() = avail.also { calls++ }
    override suspend fun lastKnown() = point
}

private class FakeNavi(var avail: Availability, var dest: GeoPoint?) : NaviDestinationProvider {
    override suspend fun availability() = avail
    override suspend fun currentDestination() = dest
}

private class FakeNaviLocation(var avail: Availability, var point: GeoPoint?) : NaviLocationProvider {
    override suspend fun availability() = avail
    override suspend fun currentLocation() = point
}

private class FakeWeather(var reachable: Boolean, var low: Float?) : WeatherClient {
    override suspend fun isReachable() = reachable
    override suspend fun tonightLow(at: GeoPoint) = low
}

/** 테스트용 값. 실제 표는 기상청 평년값으로 채운다. */
private object FakeClimate : ClimateTable {
    override fun monthlyLow(at: GeoPoint?, month: Month) = -3f
}

private fun registry(
    vehicle: FakeVehicleGateway,
    loc: FakeLocation = FakeLocation(Availability.AVAILABLE, CAMPSITE),
    navi: FakeNavi = FakeNavi(Availability.AVAILABLE, null),
    weather: FakeWeather = FakeWeather(true, 1f),
    naviLoc: FakeNaviLocation = FakeNaviLocation(Availability.UNSUPPORTED, null),
) = DataSourceRegistry(
    vehicle, naviLoc, loc, navi, weather, FakeClimate,
    detectionConfig = DetectionConfig(dwellMs = 30 * MIN),
    month = { Month.NOVEMBER },
)

class LocationChainTest {
    @Test fun `내비 차량 위치가 되면 AAOS보다 먼저 쓴다`() = runTest {
        val naviLoc = FakeNaviLocation(Availability.AVAILABLE, GeoPoint(37.41, 127.09))
        val reg = registry(FakeVehicleGateway(), naviLoc = naviLoc)
        val r = reg.location.resolve()
        assertEquals("location.navi_current", r.usedSource?.key)
        assertEquals(37.41, r.result?.value?.latitude)

        naviLoc.point = null // 내비가 위치를 못 주면 AAOS로
        assertEquals("location.aaos", reg.location.resolve().usedSource?.key)
    }

    @Test fun `한국 밖 좌표는 버리고 다음 경로로 넘어간다`() = runTest {
        // 에뮬레이터 재시작 직후: 내비는 아직 응답 없음, AAOS는 기본 GPS(미국 마운틴뷰)
        val naviLoc = FakeNaviLocation(Availability.AVAILABLE, null)
        val loc = FakeLocation(Availability.AVAILABLE, GeoPoint(37.4220, -122.0841))
        val navi = FakeNavi(Availability.AVAILABLE, GeoPoint(37.8, 127.5)) // 자라섬 근처 목적지
        val reg = registry(FakeVehicleGateway(), loc, navi, naviLoc = naviLoc)
        val r = reg.location.resolve()
        assertEquals("location.navi_destination", r.usedSource?.key)

        navi.dest = GeoPoint(0.0, 0.0) // 경로 취소 직후 내비가 주는 빈 목적지도 버린다
        assertNull(reg.location.resolve().result)

        naviLoc.point = GeoPoint(37.41, 127.09) // 내비가 준비되면 1순위
        assertEquals("location.navi_current", reg.location.resolve().usedSource?.key)
    }

    @Test fun `AAOS 위치가 되면 1순위를 쓴다`() = runTest {
        val r = registry(FakeVehicleGateway()).location.resolve()
        assertEquals("location.aaos", r.usedSource?.key)
        assertEquals(Fidelity.MEASURED, r.result?.fidelity)
    }

    @Test fun `AAOS 미지원이면 내비 목적지, 그것도 없으면 직접 선택`() = runTest {
        val loc = FakeLocation(Availability.UNSUPPORTED, null)
        val navi = FakeNavi(Availability.AVAILABLE, GeoPoint(36.5, 127.5))
        val reg = registry(FakeVehicleGateway(), loc, navi)

        assertEquals("location.navi_destination", reg.location.resolve().usedSource?.key)

        navi.dest = null
        val none = reg.location.resolve()
        assertNull(none.result) // 아직 고르지 않음 → UI가 지도 선택을 요청

        reg.manualLocation.pick(CAMPSITE)
        assertEquals("location.manual", reg.location.resolve().usedSource?.key)
    }

    @Test fun `영구 실패는 기억해서 다시 묻지 않고, 일시 실패는 매번 다시 시도한다`() = runTest {
        val unsupported = FakeLocation(Availability.UNSUPPORTED, null)
        val reg = registry(FakeVehicleGateway(), unsupported, FakeNavi(Availability.AVAILABLE, CAMPSITE))
        repeat(3) { reg.location.resolve() }
        assertEquals(1, unsupported.calls)

        val flaky = FakeLocation(Availability.TEMPORARILY_UNAVAILABLE, null)
        val reg2 = registry(FakeVehicleGateway(), flaky, FakeNavi(Availability.AVAILABLE, CAMPSITE))
        reg2.location.resolve()
        flaky.avail = Availability.AVAILABLE; flaky.point = CAMPSITE
        assertEquals("location.aaos", reg2.location.resolve().usedSource?.key)
    }
}

class TemperatureChainTest {
    @Test fun `차량 외기온 → 날씨 API → 내장표 순서`() = runTest {
        val vehicle = FakeVehicleGateway.supporting(VehicleSignal.OUTSIDE_TEMPERATURE)
        vehicle.current[VehicleSignal.OUTSIDE_TEMPERATURE] = OutsideTemperatureChanged(4f, 0)
        assertEquals("temp.vehicle", registry(vehicle).temperature.resolve().usedSource?.key)

        assertEquals("temp.weather_api", registry(FakeVehicleGateway()).temperature.resolve().usedSource?.key)

        val offline = registry(FakeVehicleGateway(), weather = FakeWeather(false, null))
        val r = offline.temperature.resolve()
        assertEquals("temp.climate_table", r.usedSource?.key)
        assertEquals(-3f, r.result?.value?.value)
    }
}

class StrategySelectionTest {
    @Test fun `지원 신호에 따라 감지기와 주차 추정기를 고른다`() = runTest {
        val full = registry(FakeVehicleGateway.supporting(
            VehicleSignal.GEAR, VehicleSignal.SEAT_OCCUPANCY, VehicleSignal.DOOR_OPEN, VehicleSignal.STEERING_ANGLE,
        )).apply { selectStrategies() }
        assertIs<DoorPatternDetector>(full.detector) // 1단계 결정: 문 패턴이 1순위
        assertIs<ReverseAndSteeringEstimator>(full.parking)

        val seatOnly = registry(FakeVehicleGateway.supporting(VehicleSignal.GEAR, VehicleSignal.SEAT_OCCUPANCY))
            .apply { selectStrategies() }
        assertIs<SeatOccupancyDetector>(seatOnly.detector)

        val partial = registry(FakeVehicleGateway.supporting(VehicleSignal.GEAR, VehicleSignal.DOOR_OPEN))
            .apply { selectStrategies() }
        assertIs<DoorPatternDetector>(partial.detector)
        assertIs<ReverseOnlyEstimator>(partial.parking)

        val nothing = registry(FakeVehicleGateway()).apply { selectStrategies() }
        assertIs<ManualSessionDetector>(nothing.detector)
        assertIs<DisabledParkingEstimator>(nothing.parking)
    }

    @Test fun `데이터 출처 보고`() = runTest {
        val reg = registry(FakeVehicleGateway.supporting(VehicleSignal.GEAR)).apply { selectStrategies() }
        val rows = reg.report().associate { it.category to it.active?.key }
        assertEquals("location.aaos", rows["위치"])
        assertEquals("temp.weather_api", rows["외기 온도"])
        assertEquals("detect.manual", rows["차박 감지"])
        assertEquals("parking.reverse_only", rows["주차 난이도"])
    }
}

class SessionDetectorTest {
    private val cfg = DetectionConfig(dwellMs = 30 * MIN)

    @Test fun `좌석 감지 - P와 점유가 30분 유지되면 시작, 기어를 빼면 종료`() {
        val d = SeatOccupancyDetector(cfg)
        assertEquals(SessionDecision.None, d.onEvent(GearChanged(Gear.PARK, 0)))
        d.onEvent(SeatOccupancyChanged(true, 5 * MIN))
        assertEquals(SessionDecision.None, d.onTick(30 * MIN)) // 점유 후 25분
        val started = d.onTick(35 * MIN)
        assertEquals(SessionDecision.Started(5 * MIN, Confidence.HIGH), started)
        assertEquals(SessionDecision.None, d.onTick(60 * MIN)) // 중복 시작 없음
        assertEquals(SessionDecision.Ended(8 * 60 * MIN), d.onEvent(GearChanged(Gear.DRIVE, 8 * 60 * MIN)))
    }

    @Test fun `좌석 감지 - 사람이 내리면 시작하지 않는다`() {
        val d = SeatOccupancyDetector(cfg)
        d.onEvent(GearChanged(Gear.PARK, 0))
        d.onEvent(SeatOccupancyChanged(true, 0))
        d.onEvent(SeatOccupancyChanged(false, 10 * MIN))
        assertEquals(SessionDecision.None, d.onTick(60 * MIN))
    }

    @Test fun `문 패턴 감지 - 마지막으로 문이 닫힌 뒤 30분`() {
        val d = DoorPatternDetector(cfg)
        d.onEvent(GearChanged(Gear.PARK, 0))
        d.onEvent(DoorChanged(true, 10 * MIN))   // 짐 정리
        d.onEvent(DoorChanged(false, 20 * MIN))
        assertEquals(SessionDecision.None, d.onTick(45 * MIN))
        assertEquals(SessionDecision.Started(20 * MIN, Confidence.MEDIUM), d.onTick(50 * MIN))
    }

    @Test fun `문 패턴 감지 - 닫힘 알림이 반복돼도 기준 시각이 밀리지 않는다`() {
        val d = DoorPatternDetector(cfg)
        d.onEvent(GearChanged(Gear.PARK, 0))
        d.onEvent(DoorChanged(false, 5 * MIN))  // 다른 문의 닫힘 알림
        d.onEvent(DoorChanged(false, 25 * MIN))
        assertEquals(SessionDecision.Started(0, Confidence.MEDIUM), d.onTick(30 * MIN))
    }

    @Test fun `수동 감지 - 버튼 입력을 한 번만 전달`() {
        val d = ManualSessionDetector()
        d.userStarted(100)
        assertIs<SessionDecision.Started>(d.onTick(200))
        assertEquals(SessionDecision.None, d.onTick(300))
    }
}

class ParkingEstimatorTest {
    @Test fun `한 번에 후진 주차하면 쉬움`() {
        val e = ReverseOnlyEstimator()
        e.onEvent(GearChanged(Gear.DRIVE, 0))
        e.onEvent(GearChanged(Gear.REVERSE, 1000))
        val r = e.onEvent(GearChanged(Gear.PARK, 31_000))!!
        assertEquals(ParkingDifficulty.EASY, r.difficulty)
        assertEquals(1, r.reverseCount)
        assertNull(r.fullLockCount)
        assertEquals(30_000, r.durationMs)
    }

    @Test fun `후진 여러 번에 조향을 끝까지 돌리면 어려움`() {
        val e = ReverseAndSteeringEstimator()
        var t = 0L
        repeat(3) {
            e.onEvent(GearChanged(Gear.REVERSE, t++))
            e.onEvent(SteeringAngleChanged(480f, t++))
            e.onEvent(SteeringAngleChanged(0f, t++))
            e.onEvent(GearChanged(Gear.DRIVE, t++))
        }
        val r = e.onEvent(GearChanged(Gear.PARK, t))!!
        assertEquals(3, r.reverseCount)
        assertEquals(3, r.fullLockCount)
        assertEquals(ParkingDifficulty.HARD, r.difficulty) // 3 + 3/2 = 4
    }

    @Test fun `주차가 끝나면 다음 주차를 위해 초기화`() {
        val e = ReverseOnlyEstimator()
        repeat(5) { e.onEvent(GearChanged(Gear.REVERSE, it * 2L)); e.onEvent(GearChanged(Gear.DRIVE, it * 2L + 1)) }
        assertEquals(ParkingDifficulty.HARD, e.onEvent(GearChanged(Gear.PARK, 20))!!.difficulty)
        e.onEvent(GearChanged(Gear.REVERSE, 30))
        assertEquals(ParkingDifficulty.EASY, e.onEvent(GearChanged(Gear.PARK, 40))!!.difficulty)
    }
}
