package chabaklog.app.data

import android.content.Context
import chabaklog.datasource.location.GeoPoint
import chabaklog.datasource.temperature.ClimateTable
import org.json.JSONObject
import java.time.Month
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 두 지점 사이 거리(m) */
fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

private fun Context.readAsset(name: String) = assets.open(name).bufferedReader().use { it.readText() }

/**
 * 내장 월평균 최저기온표 (assets/climate_monthly_low.json, 기상청 1991–2020 평년값 13개 지점).
 * 가장 가까운 지점 값을 쓰고, 위치를 모르면 13개 지점 평균을 쓴다.
 */
class AssetClimateTable(context: Context) : ClimateTable {
    private data class Station(val name: String, val point: GeoPoint, val minC: FloatArray)

    private val stations: List<Station> = JSONObject(context.readAsset("climate_monthly_low.json"))
        .getJSONArray("stations").let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val m = o.getJSONArray("minC")
                Station(o.getString("name"), GeoPoint(o.getDouble("lat"), o.getDouble("lon")),
                    FloatArray(12) { m.getDouble(it).toFloat() })
            }
        }

    override fun monthlyLow(at: GeoPoint?, month: Month): Float {
        val idx = month.value - 1
        if (at == null) return stations.map { it.minC[idx] }.average().toFloat()
        return stations.minBy { distanceMeters(it.point, at) }.minC[idx]
    }

    fun nearestStationName(at: GeoPoint): String = stations.minBy { distanceMeters(it.point, at) }.name
}

/** 지도 윤곽 (Natural Earth 1:50m, 남한). [경도, 위도] 링 목록 */
class KoreaOutline(context: Context) {
    val rings: List<List<GeoPoint>> = JSONObject(context.readAsset("korea_outline.json"))
        .getJSONArray("rings").let { arr ->
            (0 until arr.length()).map { i ->
                val ring = arr.getJSONArray(i)
                (0 until ring.length()).map { j ->
                    val p = ring.getJSONArray(j)
                    GeoPoint(p.getDouble(1), p.getDouble(0))
                }
            }
        }
}

/** 공공 우수야영장 55곳 (assets/public_campsites.json) */
fun loadPublicCampsites(context: Context): List<PlaceEntity> {
    val arr = JSONObject(context.readAsset("public_campsites.json")).getJSONArray("campsites")
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        PlaceEntity(
            name = o.getString("name"),
            latitude = o.getDouble("lat"),
            longitude = o.getDouble("lon"),
            kind = PlaceKind.PUBLIC_CAMPSITE,
            note = "${o.getInt("selectedYear")} 공공 우수야영장",
            address = o.getString("address"),
            externalId = "gocamping:${o.getInt("gocampingNo")}",
        )
    }
}
