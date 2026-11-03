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

    /** 데이터 출처 화면용 지점 수 */
    val stationCount get() = stations.size
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

/** 캠핑장 근처 공중화장실 (assets/toilets_near_campsites.json, 캠핑장당 최대 3곳, 번지까지 일치한 좌표만) */
data class NearbyToilet(
    val name: String,
    val kind: String,
    val address: String,
    val openHours: String,
    val point: GeoPoint,
    val distanceM: Int,
)

class ToiletIndex(context: Context) {
    private val byCamp: Map<String, List<NearbyToilet>> = run {
        val arr = JSONObject(context.readAsset("toilets_near_campsites.json")).getJSONArray("toilets")
        (0 until arr.length()).map { arr.getJSONObject(it) }.groupBy(
            keySelector = { it.getString("camp") },
            valueTransform = {
                NearbyToilet(
                    name = it.getString("name"),
                    kind = it.getString("kind"),
                    address = it.getString("address"),
                    openHours = it.optString("open"),
                    point = GeoPoint(it.getDouble("lat"), it.getDouble("lon")),
                    distanceM = it.getInt("distanceM"),
                )
            },
        ).mapValues { (_, v) -> v.sortedBy { it.distanceM } }
    }

    fun near(campName: String): List<NearbyToilet> = byCamp[campName].orEmpty()

    /** 데이터 출처 화면용 전체 개수 */
    val count get() = byCamp.values.sumOf { it.size }
}

/** 경유지 후보 종류 (assets/pois_near_campsites.json의 kind) */
enum class PoiKind(val key: String, val label: String, val title: String) {
    CONVENIENCE("convenience", "편의점", "가는 길 편의점"),
    SUPERMARKET("supermarket", "마트", "장보기 마트"),
    CHARGING("charging", "전기차 충전소", "근처 전기차 충전소"),
}

/**
 * 캠핑장 근처 편의점·마트·전기차 충전소 (OpenStreetMap, 캠핑장 반경 5 km, 편의점 3·마트 2·충전소 2곳).
 * 화장실과 같은 NearbyToilet 모양으로 돌려줘 경유지 넣기 흐름을 그대로 쓴다.
 */
class PoiIndex(context: Context) {
    private val byCampKind: Map<Pair<String, String>, List<NearbyToilet>> = run {
        val arr = JSONObject(context.readAsset("pois_near_campsites.json")).getJSONArray("pois")
        (0 until arr.length()).map { arr.getJSONObject(it) }.groupBy(
            keySelector = { it.getString("camp") to it.getString("kind") },
            valueTransform = {
                val kind = PoiKind.entries.firstOrNull { k -> k.key == it.getString("kind") }
                NearbyToilet(
                    name = it.getString("name"),
                    kind = kind?.label ?: it.getString("kind"),
                    address = "",
                    openHours = it.optString("open").let { o -> if (o == "24/7") "24시간" else o },
                    point = GeoPoint(it.getDouble("lat"), it.getDouble("lon")),
                    distanceM = it.getInt("distanceM"),
                )
            },
        ).mapValues { (_, v) -> v.sortedBy { it.distanceM } }
    }

    fun near(campName: String, kind: PoiKind): List<NearbyToilet> = byCampKind[campName to kind.key].orEmpty()

    /** 데이터 출처 화면용 종류별 개수 */
    fun count(kind: PoiKind): Int = byCampKind.filterKeys { it.second == kind.key }.values.sumOf { it.size }
}
