package chabaklog.app.adapters

import chabaklog.datasource.location.GeoPoint
import chabaklog.datasource.temperature.ClimateTable
import chabaklog.datasource.temperature.WeatherClient
import java.time.Month

/** 날씨 API는 3단계에서 고른다. 그 전까지는 "닿지 않음"으로 보고 체인이 다음 순위로 넘어간다. */
object PendingWeatherClient : WeatherClient {
    override suspend fun isReachable() = false
    override suspend fun tonightLow(at: GeoPoint): Float? = null
}

/**
 * 내장 월별 평균 최저기온표는 3단계에서 기상청 평년값으로 채운다.
 * 값을 지어내지 않기 위해 그 전까지는 NaN을 돌려주고, 기록할 때 NaN은 "값 없음"으로 저장한다.
 * (1단계 실측상 에뮬레이터는 차량 외기온을 주므로 이 표까지 내려오는 일은 드물다.)
 */
object PendingClimateTable : ClimateTable {
    override fun monthlyLow(at: GeoPoint?, month: Month): Float = Float.NaN
}
