package chabaklog.datasource

import chabaklog.datasource.core.FallbackChain
import chabaklog.datasource.core.SourceId
import chabaklog.datasource.detection.DetectionConfig
import chabaklog.datasource.detection.SessionDetector
import chabaklog.datasource.detection.sessionDetectorSelector
import chabaklog.datasource.location.GeoPoint
import chabaklog.datasource.location.ManualPickSource
import chabaklog.datasource.location.NaviDestinationProvider
import chabaklog.datasource.location.NaviLocationProvider
import chabaklog.datasource.location.PlatformLocationProvider
import chabaklog.datasource.location.locationChain
import chabaklog.datasource.parking.ParkingConfig
import chabaklog.datasource.parking.ParkingEstimator
import chabaklog.datasource.parking.parkingEstimatorSelector
import chabaklog.datasource.temperature.Celsius
import chabaklog.datasource.temperature.ClimateTable
import chabaklog.datasource.temperature.WeatherClient
import chabaklog.datasource.temperature.temperatureChain
import chabaklog.datasource.vehicle.VehicleGateway
import java.time.LocalDate
import java.time.Month

/** 설정 > 데이터 출처 화면 한 줄 */
data class SourceReportRow(val category: String, val active: SourceId?, val note: String?)

/**
 * 앱 시작 시 한 번 만들어 쓰는 진입점.
 * 네 가지 카테고리의 체인·전략을 조립하고, 지금 무엇을 쓰고 있는지 보고한다.
 */
class DataSourceRegistry(
    private val vehicle: VehicleGateway,
    naviLocation: NaviLocationProvider,
    platformLocation: PlatformLocationProvider,
    navi: NaviDestinationProvider,
    weather: WeatherClient,
    climate: ClimateTable,
    detectionConfig: DetectionConfig = DetectionConfig(),
    parkingConfig: ParkingConfig = ParkingConfig(),
    month: () -> Month = { LocalDate.now().month },
) {
    val manualLocation = ManualPickSource()

    val location: FallbackChain<GeoPoint> = locationChain(naviLocation, platformLocation, navi, manualLocation)

    val temperature: FallbackChain<Celsius> = temperatureChain(
        vehicle = vehicle,
        weather = weather,
        table = climate,
        location = { location.resolve().result?.value },
        month = month,
    )

    private val detectorSelector = sessionDetectorSelector(detectionConfig)
    private val parkingSelector = parkingEstimatorSelector(parkingConfig)

    lateinit var detector: SessionDetector
        private set
    lateinit var parking: ParkingEstimator
        private set

    /** 차량 연결 직후 호출. 지원 신호를 보고 감지기·추정기를 고른다. */
    suspend fun selectStrategies() {
        detector = detectorSelector.select(vehicle)
        parking = parkingSelector.select(vehicle)
    }

    suspend fun report(): List<SourceReportRow> {
        val loc = location.lastResolution ?: location.resolve()
        val temp = temperature.lastResolution ?: temperature.resolve()
        return listOf(
            SourceReportRow("위치", loc.usedSource, if (loc.result == null) "지도에서 위치를 골라주세요" else null),
            SourceReportRow("외기 온도", temp.usedSource, null),
            SourceReportRow("차박 감지", detector.id, null),
            SourceReportRow("주차 난이도", parking.id, null),
        )
    }
}
