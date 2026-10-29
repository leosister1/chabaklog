package chabaklog.datasource.temperature

import chabaklog.datasource.core.Availability
import chabaklog.datasource.core.DataSource
import chabaklog.datasource.core.FallbackChain
import chabaklog.datasource.core.Fidelity
import chabaklog.datasource.core.SourceId
import chabaklog.datasource.core.Sourced
import chabaklog.datasource.location.GeoPoint
import chabaklog.datasource.vehicle.VehicleEvent
import chabaklog.datasource.vehicle.VehicleGateway
import chabaklog.datasource.vehicle.VehicleSignal
import java.time.Month

/** 외기 온도(섭씨) */
@JvmInline value class Celsius(val value: Float)

/** 1순위: 차량 외기온 속성 */
class VehicleOutsideTempSource(
    private val vehicle: VehicleGateway,
) : DataSource<Celsius> {
    override val id = SourceId("temp.vehicle", "차량 외기온 센서")
    override val priority = 1
    override suspend fun availability() = vehicle.availability(VehicleSignal.OUTSIDE_TEMPERATURE)
    override suspend fun read(): Sourced<Celsius>? {
        val event = vehicle.readCurrent(VehicleSignal.OUTSIDE_TEMPERATURE)
            as? VehicleEvent.OutsideTemperatureChanged ?: return null
        return Sourced(Celsius(event.celsius), id, Fidelity.MEASURED, event.timestampMs)
    }
}

/** 날씨 API 클라이언트. 구현은 앱 모듈(네트워크 라이브러리 선택은 앱에서). */
interface WeatherClient {
    suspend fun isReachable(): Boolean
    /** 오늘 밤 최저기온. 차박 예측에는 현재 기온보다 이 값이 맞다. */
    suspend fun tonightLow(at: GeoPoint): Float?
}

/** 2순위: 날씨 API. 위치가 필요하므로 위치 체인 결과를 함께 받는다. */
class WeatherApiTempSource(
    private val weather: WeatherClient,
    private val location: suspend () -> GeoPoint?,
    private val clock: () -> Long = System::currentTimeMillis,
) : DataSource<Celsius> {
    override val id = SourceId("temp.weather_api", "날씨 API 예보")
    override val priority = 2
    override suspend fun availability(): Availability = when {
        !weather.isReachable() -> Availability.TEMPORARILY_UNAVAILABLE
        location() == null -> Availability.TEMPORARILY_UNAVAILABLE
        else -> Availability.AVAILABLE
    }
    override suspend fun read(): Sourced<Celsius>? {
        val point = location() ?: return null
        val low = weather.tonightLow(point) ?: return null
        return Sourced(Celsius(low), id, Fidelity.ESTIMATED, clock())
    }
}

/**
 * 앱에 내장한 지역·월별 평균 최저기온표.
 * 값은 기상청 평년값으로 채운다(assets/climate_monthly.json). 표 내용은 이 클래스가 결정하지 않는다.
 */
interface ClimateTable {
    /** 가장 가까운 관측 지점의 해당 월 평균 최저기온. 위치를 모르면 전국 평균. */
    fun monthlyLow(at: GeoPoint?, month: Month): Float
}

/** 최후 대체: 내장 표. 항상 값을 돌려준다. */
class ClimateTableTempSource(
    private val table: ClimateTable,
    private val location: suspend () -> GeoPoint?,
    private val month: () -> Month,
    private val clock: () -> Long = System::currentTimeMillis,
) : DataSource<Celsius> {
    override val id = SourceId("temp.climate_table", "지역·월별 평균 기온(내장)")
    override val priority = 3
    override suspend fun availability() = Availability.AVAILABLE
    override suspend fun read() =
        Sourced(Celsius(table.monthlyLow(location(), month())), id, Fidelity.ESTIMATED, clock())
}

fun temperatureChain(
    vehicle: VehicleGateway,
    weather: WeatherClient,
    table: ClimateTable,
    location: suspend () -> GeoPoint?,
    month: () -> Month,
) = FallbackChain(
    category = "외기 온도",
    sources = listOf(
        VehicleOutsideTempSource(vehicle),
        WeatherApiTempSource(weather, location),
        ClimateTableTempSource(table, location, month),
    ),
)
