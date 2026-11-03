package chabaklog.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chabaklog.app.data.PlaceKind
import chabaklog.datasource.location.GeoPoint
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

private val PublicColor = Color(0xFF4CAF50)
private val MineColor = Color(0xFFFF9800)
private val DemoColor = Color(0xFFB39DDB)
private val VehicleColor = Color(0xFF42A5F5)

/** 남한 범위에 맞춘 등장방형 투영 (위도 cos 보정). 네트워크 없이 그리는 내장 지도. */
private class Projection(size: Size, scale: Float, offset: Offset) {
    private val lonMin = 125.8; private val lonMax = 131.0
    private val latMin = 33.0; private val latMax = 38.8
    private val k = cos(Math.toRadians((latMin + latMax) / 2))
    private val base: Float
    private val ox: Float; private val oy: Float
    private val s = scale; private val off = offset
    init {
        val w = (lonMax - lonMin) * k; val h = latMax - latMin
        base = minOf(size.width / w, size.height / h).toFloat()
        ox = ((size.width - w * base) / 2).toFloat(); oy = ((size.height - h * base) / 2).toFloat()
    }
    fun toScreen(p: GeoPoint): Offset {
        val x = ox + ((p.longitude - lonMin) * k * base).toFloat()
        val y = oy + ((latMax - p.latitude) * base).toFloat()
        return Offset(x * s + off.x, y * s + off.y)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: MainViewModel) {
    val summaries by viewModel.placeSummaries.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val forecast by viewModel.selectedForecast.collectAsStateWithLifecycle()
    val trip by viewModel.selectedTrip.collectAsStateWithLifecycle()
    val tripLoaded by viewModel.tripLoaded.collectAsStateWithLifecycle()
    val vehicle by viewModel.vehiclePoint.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val visible = summaries.filter { s ->
        val kindOk = when (s.place.kind) {
            PlaceKind.PUBLIC_CAMPSITE, PlaceKind.DEMO_SEED -> MapFilter.PUBLIC in filters
            PlaceKind.MINE -> MapFilter.MINE in filters
        }
        kindOk && (MapFilter.RECORDED !in filters || s.hasRecords)
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val outline = remember { viewModel.outline }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1.3f).fillMaxHeight()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MapFilter.entries.forEach { f ->
                    FilterChip(selected = f in filters, onClick = { viewModel.toggleFilter(f) }, label = { Text(f.label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(PublicColor to "공공 우수야영장", MineColor to "내 차박지", DemoColor to "시연용 기록", VehicleColor to "지금 차 위치")
                    .forEach { (c, label) ->
                        Text("● ", color = c, style = MaterialTheme.typography.labelSmall)
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                Text("· 지도: Natural Earth", style = MaterialTheme.typography.labelSmall)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Canvas(
                    Modifier.fillMaxSize()
                        .onSizeChanged { canvasSize = it.toSize() }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 8f)
                                offset += pan
                            }
                        }
                        .pointerInput(visible) {
                            detectTapGestures { tap ->
                                val proj = Projection(canvasSize, scale, offset)
                                val hit = visible
                                    .map { it to proj.toScreen(GeoPoint(it.place.latitude, it.place.longitude)) }
                                    .minByOrNull { (_, o) -> hypot(o.x - tap.x, o.y - tap.y) }
                                    ?.takeIf { (_, o) -> hypot(o.x - tap.x, o.y - tap.y) < 40f }
                                viewModel.select(hit?.first)
                            }
                        }
                ) {
                    val proj = Projection(size, scale, offset)
                    outline.forEach { ring ->
                        val path = Path()
                        ring.forEachIndexed { i, p ->
                            val o = proj.toScreen(p)
                            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                        }
                        path.close()
                        drawPath(path, Color(0xFF2E3440))
                        drawPath(path, Color(0xFF5E6A80), style = Stroke(width = 1.5f))
                    }
                    visible.forEach { s ->
                        val o = proj.toScreen(GeoPoint(s.place.latitude, s.place.longitude))
                        val color = when {
                            s.place.kind == PlaceKind.MINE -> MineColor
                            s.demoCount > 0 && s.realCount == 0 -> DemoColor
                            else -> PublicColor
                        }
                        val r = if (s.hasRecords) 9f else 6f
                        if (selected?.place?.id == s.place.id) drawCircle(Color.White, r + 5f, o)
                        drawCircle(color, r, o)
                    }
                    vehicle?.let {
                        val o = proj.toScreen(it)
                        drawCircle(Color.White, 12f, o)
                        drawCircle(VehicleColor, 9f, o)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("전체 보기") }
                Text("두 손가락으로 확대·이동, 점을 눌러 장소 보기", style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            val s = selected
            if (s == null) {
                Text("지도에서 장소를 눌러 주세요", style = MaterialTheme.typography.titleMedium)
                Text("표시 중 ${visible.size}곳 · 기록 있는 곳 ${visible.count { it.hasRecords }}곳")
                return@Column
            }
            PlaceCard(s, forecast, trip, tripLoaded, viewModel)
        }
    }
}

@Composable
private fun PlaceCard(
    s: PlaceSummary,
    forecast: chabaklog.app.tracking.Forecast?,
    trip: TripEstimate?,
    tripLoaded: Boolean,
    viewModel: MainViewModel,
) {
    val p = s.place
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleLarge)
            val tag = when (p.kind) {
                PlaceKind.PUBLIC_CAMPSITE -> p.note ?: "공공 우수야영장"
                PlaceKind.MINE -> "내 차박지"
                PlaceKind.DEMO_SEED -> "시연용"
            }
            Text(tag, style = MaterialTheme.typography.labelLarge)
            p.address?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Text("오늘 밤 예상 배터리 사용", style = MaterialTheme.typography.titleSmall)
            if (forecast == null) Text("계산 중…") else {
                val pr = forecast.prediction
                Text("약 ${forecast.totalSocPercent.roundToInt()}% (${pr.hours.roundToInt()}시간 기준)", style = MaterialTheme.typography.headlineSmall)
                if (forecast.v2l.appliances.isNotEmpty()) {
                    Text("차박 ${pr.socUsedPercent.roundToInt()}% + V2L ${"%.1f".format(forecast.v2l.socPercent)}% (${forecast.v2l.appliances.joinToString { it.label }})",
                        style = MaterialTheme.typography.bodySmall)
                }
                pr.batteryKwhAdjusted?.let { Text("내 차 배터리 ${it.roundToInt()}kWh 기준으로 보정", style = MaterialTheme.typography.bodySmall) }
                Text("기온 ${"%.1f".format(pr.tempC)}° · ${forecast.tempSource}", style = MaterialTheme.typography.bodySmall)
                Text("소모율 시간당 ${"%.1f".format(pr.ratePerHour)}% · ${pr.basis.label}" +
                    if (pr.sampleCount > 0) " (${pr.sampleCount}건)" else "", style = MaterialTheme.typography.bodySmall)
            }

            ArrivalPlan(trip, tripLoaded, forecast)

            Text("주차 난이도: ${s.parkingLevel ?: "기록 없음"}", style = MaterialTheme.typography.titleSmall)
            val records = buildString {
                append("기록 ${s.realCount}건")
                if (s.demoCount > 0) append(" + 시연용 ${s.demoCount}건")
                s.avgSocUsed?.let { append(" · 평균 ${it.roundToInt()}% 사용") }
            }
            Text(records, style = MaterialTheme.typography.bodyMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.setDestination(p) }) { Text("목적지로 설정") }
                OutlinedButton(onClick = { viewModel.pickHere(p) }) { Text("여기를 내 위치로") }
            }
            val toilets = viewModel.toiletsNear(p)
            if (toilets.isNotEmpty()) {
                Text("가는 길에 들를 화장실", style = MaterialTheme.typography.titleSmall)
                toilets.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${t.name} · 캠핑장에서 ${formatDistance(t.distanceM)}", style = MaterialTheme.typography.bodyMedium)
                            Text(listOf(t.kind, t.openHours).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { viewModel.addToiletWaypoint(p, t) }) { Text("경유지로") }
                    }
                }
                Text("출처: 전국공중화장실표준데이터 · 좌표 © OpenStreetMap", style = MaterialTheme.typography.labelSmall)
            } else if (p.kind == PlaceKind.PUBLIC_CAMPSITE) {
                Text("근처 화장실 정보 없음", style = MaterialTheme.typography.bodySmall)
            }
            if (p.kind == PlaceKind.PUBLIC_CAMPSITE) {
                var anyPoi = false
                chabaklog.app.data.PoiKind.entries.forEach { kind ->
                    val list = viewModel.poisNear(p, kind)
                    if (list.isEmpty()) return@forEach
                    anyPoi = true
                    Text(kind.title, style = MaterialTheme.typography.titleSmall)
                    list.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("${t.name} · 캠핑장에서 ${formatDistance(t.distanceM)}", style = MaterialTheme.typography.bodyMedium)
                                if (t.openHours.isNotBlank()) Text(t.openHours, style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(onClick = { viewModel.addToiletWaypoint(p, t) }) { Text("경유지로") }
                        }
                    }
                }
                if (anyPoi) Text("출처: © OpenStreetMap (빠진 곳이 있을 수 있어요)", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * 가는 길 + 오늘 밤 = 내일 아침 잔량.
 * 도착 시 예상 배터리는 내비(requestTrip)가 경로·교통을 반영해 계산한 값이다.
 */
@Composable
private fun ArrivalPlan(trip: TripEstimate?, tripLoaded: Boolean, forecast: chabaklog.app.tracking.Forecast?) {
    Text("가는 길과 내일 아침", style = MaterialTheme.typography.titleSmall)
    when {
        !tripLoaded -> Text("내비에서 경로 계산 중…", style = MaterialTheme.typography.bodySmall)
        trip == null -> Text("내비에서 경로 정보를 받지 못했어요", style = MaterialTheme.typography.bodySmall)
        else -> {
            val minutes = trip.etaSec / 60
            val time = if (minutes >= 60) "${minutes / 60}시간 ${minutes % 60}분" else "${minutes}분"
            Text(
                "${formatDistance(trip.distanceM)} · 약 $time · 도착 시 배터리 약 ${trip.socAtArrival.roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            forecast?.let { f ->
                val morning = trip.socAtArrival - f.totalSocPercent
                Text(
                    "도착 ${trip.socAtArrival.roundToInt()}% − 오늘 밤 ${f.totalSocPercent.roundToInt()}% → 내일 아침 약 ${morning.roundToInt().coerceAtLeast(0)}%",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (morning < MORNING_WARN_PERCENT) {
                    Text(
                        "아침에 ${MORNING_WARN_PERCENT}%보다 적게 남을 수 있어요. 가는 길에 충전을 생각해 보세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text("도착 시 배터리: 차량 내비 경로 계산 값", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private const val MORNING_WARN_PERCENT = 20

private fun formatDistance(m: Int) = if (m < 1000) "${m}m" else "%.1fkm".format(m / 1000f)
