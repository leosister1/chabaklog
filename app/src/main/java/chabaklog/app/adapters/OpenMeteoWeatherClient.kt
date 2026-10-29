package chabaklog.app.adapters

import android.util.Log
import chabaklog.datasource.location.GeoPoint
import chabaklog.datasource.temperature.WeatherClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 날씨 API: Open-Meteo (키 없이 쓰는 무료 예보 API, 비상업적 이용 무료).
 * "오늘 밤 최저기온" = 내일 날짜의 일 최저기온 (새벽에 가장 춥다).
 * 차량 네트워크가 없을 수 있으므로 짧은 제한 시간 + 실패 시 10분간 다시 시도하지 않음 → 체인이 내장표로 넘어간다.
 */
class OpenMeteoWeatherClient : WeatherClient {
    @Volatile private var lastFailureMs = 0L
    private val cache = mutableMapOf<String, Pair<Long, Float>>()

    override suspend fun isReachable(): Boolean =
        System.currentTimeMillis() - lastFailureMs > RETRY_AFTER_MS

    override suspend fun tonightLow(at: GeoPoint): Float? = withContext(Dispatchers.IO) {
        val key = "%.2f,%.2f".format(at.latitude, at.longitude)
        cache[key]?.let { (t, v) -> if (System.currentTimeMillis() - t < CACHE_MS) return@withContext v }
        try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=${at.latitude}&longitude=${at.longitude}" +
                    "&daily=temperature_2m_min&timezone=Asia%2FSeoul&forecast_days=2"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000; readTimeout = 3_000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val mins = JSONObject(body).getJSONObject("daily").getJSONArray("temperature_2m_min")
            val v = mins.getDouble(if (mins.length() > 1) 1 else 0).toFloat()
            cache[key] = System.currentTimeMillis() to v
            v
        } catch (e: Exception) {
            Log.w("CHABAK_WEATHER", "날씨 API 실패: ${e.javaClass.simpleName} ${e.message}")
            lastFailureMs = System.currentTimeMillis()
            null
        }
    }

    private companion object {
        const val RETRY_AFTER_MS = 10 * 60_000L
        const val CACHE_MS = 60 * 60_000L
    }
}
