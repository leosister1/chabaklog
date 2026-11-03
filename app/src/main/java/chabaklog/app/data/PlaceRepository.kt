package chabaklog.app.data

import android.content.Context
import chabaklog.datasource.location.GeoPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 장소 관리.
 * - 첫 실행 때 공공 우수야영장 55곳을 넣는다.
 * - 차박 세션이 시작되면 가까운 장소(500 m 안)에 연결하고, 없으면 "내 차박지"를 새로 만든다.
 * - 시연용 기록은 isDemo로 표시해 넣고 지울 수 있다.
 */
class PlaceRepository(private val context: Context, private val db: ChabaklogDatabase) {

    suspend fun seedIfEmpty() {
        val existing = db.places().all().mapNotNull { it.externalId }.toSet()
        val toAdd = loadPublicCampsites(context).filter { it.externalId !in existing }
        if (toAdd.isNotEmpty()) db.places().upsertAll(toAdd)
    }

    suspend fun matchOrCreate(point: GeoPoint, nowMs: Long): PlaceEntity {
        val near = db.places().all()
            .map { it to distanceMeters(point, GeoPoint(it.latitude, it.longitude)) }
            .filter { it.second <= MATCH_RADIUS_M }
            .minByOrNull { it.second }
        if (near != null) return near.first
        val name = "내 차박지 " + SimpleDateFormat("M/d", Locale.KOREA).format(Date(nowMs))
        val place = PlaceEntity(name = name, latitude = point.latitude, longitude = point.longitude, kind = PlaceKind.MINE)
        return place.copy(id = db.places().insert(place))
    }

    /**
     * 시연 영상용 예시 기록. 실제 측정값이 아니며 화면에 "시연용"으로 표시된다.
     * 공공 우수야영장 세 곳에 기온·주차 조건이 다른 밤을 하나씩 넣는다.
     */
    suspend fun insertDemoSessions(nowMs: Long) {
        db.sessions().deleteDemo()
        val places = db.places().all().associateBy { it.name }
        val day = 24 * 60 * 60_000L
        val samples = listOf(
            Demo("자라섬 캠핑장", 6, -3f, 31f, 1, "쉬움", 45),
            Demo("망상오토캠핑리조트", 13, 4f, 19f, 4, "어려움", 20),
            Demo("국립화천숲속야영장", 20, -8f, 38f, 2, "보통", 70),
        )
        samples.forEach { d ->
            val place = places[d.placeName] ?: return@forEach
            val start = nowMs - d.daysAgo * day - 12 * 60 * 60_000L
            db.sessions().insert(
                SessionEntity(
                    startedAtMs = start,
                    endedAtMs = start + 10 * 60 * 60_000L,
                    detectorId = "demo",
                    confidence = "DEMO",
                    placeId = place.id,
                    latitude = place.latitude,
                    longitude = place.longitude,
                    locationSource = "시연용",
                    socStart = 80f,
                    socEnd = 80f - d.socUsed,
                    outsideTempC = d.tempC,
                    outsideTempSource = "시연용",
                    hvacOnMinutes = d.hvacMinutes * 10,
                    doorOpenCount = 2,
                    reverseCount = d.reverses,
                    parkingLevel = d.level,
                    isDemo = true,
                    verdict = "CAMPING",
                )
            )
        }
    }

    suspend fun clearDemo() = db.sessions().deleteDemo()

    private data class Demo(
        val placeName: String, val daysAgo: Int, val tempC: Float, val socUsed: Float,
        val reverses: Int, val level: String, val hvacMinutes: Int,
    )

    private companion object { const val MATCH_RADIUS_M = 500.0 }
}
