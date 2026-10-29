package chabaklog.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chabaklog.app.data.SessionEntity
import chabaklog.app.tracking.LiveState
import chabaklog.app.tracking.Phase
import chabaklog.datasource.vehicle.Gear
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 화면 구조
 * - 홈: 지금 상태(단계·차량 값), 차박 세션 진행, 시연 모드
 * - 지도: 공공 캠핑장 + 시드(시연용 명시) + 내 기록 (3단계)
 * - 기록: 지난 차박 세션
 * - 아침 요약: 어젯밤 소모량·주차 난이도
 * - 데이터 출처: 지금 어떤 경로로 데이터를 받는지 + 1단계 연결 시험
 * 주행 중(CarUxRestrictions)에는 간소화 화면만 보여 준다.
 */
enum class Tab(val label: String) { HOME("홈"), MAP("지도"), LOG("기록"), MORNING("아침 요약"), SOURCES("데이터 출처") }

/** 2560×1440 차량 화면에서 글씨가 작아 전체 배율을 키운다 (1단계 확인) */
private const val CAR_SCALE = 1.6f

@Composable
fun ChabaklogRoot(viewModel: MainViewModel, testViewModel: ConnectionTestViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val live by viewModel.live.collectAsStateWithLifecycle()
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density * CAR_SCALE, base.fontScale)) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                if (live.restricted) {
                    DrivingScreen(live)
                    return@Surface
                }
                Row(Modifier.fillMaxSize()) {
                    NavigationRail(Modifier.width(110.dp)) {
                        Tab.entries.forEach { t ->
                            NavigationRailItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = {},
                                label = { Text(t.label, fontSize = 15.sp) },
                            )
                        }
                    }
                    Column(Modifier.fillMaxSize().padding(24.dp)) {
                        when (tab) {
                            Tab.HOME -> HomeScreen(viewModel, live)
                            Tab.MAP -> MapScreen(viewModel)
                            Tab.LOG -> LogScreen(viewModel)
                            Tab.MORNING -> MorningScreen(viewModel)
                            Tab.SOURCES -> SourcesScreen(viewModel, live, testViewModel)
                        }
                    }
                }
            }
        }
    }
}

// ---------- 공통 ----------

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.padding(6.dp))
}

@Composable
private fun InfoCard(title: String, body: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    Card(modifier.padding(vertical = 6.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Card(modifier.padding(4.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

private val timeFormat = SimpleDateFormat("M/d HH:mm", Locale.KOREA)
private fun Long.time() = timeFormat.format(Date(this))
private fun Float.pct() = "${roundToInt()}%"
private fun Gear?.korean() = when (this) {
    Gear.PARK -> "P"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; Gear.DRIVE -> "D"; else -> "-"
}
private fun minutes(ms: Long) = (ms / 60_000).let { if (it >= 60) "${it / 60}시간 ${it % 60}분" else "${it}분" }

// ---------- 주행 중 간소화 ----------

@Composable
private fun DrivingScreen(live: LiveState) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("주행 중", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.padding(12.dp))
        Text("차박로그가 주차를 기다리고 있어요", style = MaterialTheme.typography.headlineSmall)
        live.socPercent?.let {
            Spacer(Modifier.padding(12.dp))
            Text("배터리 ${it.pct()}", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

// ---------- 홈 ----------

@Composable
private fun HomeScreen(viewModel: MainViewModel, live: LiveState) {
    val demo by viewModel.demoMode.collectAsStateWithLifecycle()
    // 정차 경과 시간을 화면에서 갱신하기 위한 시계
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(10_000); value = System.currentTimeMillis() }
    }
    LazyColumn {
        item {
            Column {
                Title("차박로그")
                Text(live.phase.label, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.padding(6.dp))
                val sub = when {
                    live.sessionStartedMs != null -> "${live.sessionStartedMs.time()}부터 기록 중"
                    live.parkedSinceMs != null ->
                        "정차 ${minutes(now - live.parkedSinceMs)} · ${minutes(live.dwellMs)} 머무르면 차박으로 기록"
                    live.phase == Phase.PARKING_MANEUVER -> "후진 ${live.reverseCountLive}회"
                    else -> ""
                }
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.padding(8.dp))
            }
        }
        item {
            Row(Modifier.fillMaxWidth()) {
                val w = Modifier.weight(1f)
                Stat("기어", live.gear.korean(), w)
                Stat("배터리", live.socPercent?.pct() ?: "-", w)
                Stat("문", when (live.doorOpen) { true -> "열림"; false -> "닫힘"; null -> "-" }, w)
                Stat("공조", when (live.hvacOn) { true -> "켜짐"; false -> "꺼짐"; null -> "-" }, w)
                Stat("외기", live.outsideTempC?.let { "${"%.1f".format(it)}°" } ?: "-", w)
            }
        }
        item { TonightCard(viewModel) }
        live.lastParking?.let { p ->
            item {
                InfoCard(
                    "방금 주차",
                    "후진 ${p.reverseCount}회" + (p.fullLockCount?.let { " · 핸들 끝까지 ${it}회" } ?: "") +
                        " · ${minutes(p.durationMs)}",
                )
            }
        }
        if (live.manualMode) {
            item {
                Column {
                    InfoCard("직접 기록", "이 차에서는 자동 감지에 필요한 신호를 읽을 수 없어 직접 시작·종료합니다.")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = viewModel::manualStart, enabled = live.sessionId == null) { Text("차박 시작") }
                        OutlinedButton(onClick = viewModel::manualEnd, enabled = live.sessionId != null) { Text("차박 끝") }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("시연 모드", style = MaterialTheme.typography.titleMedium)
                    Text("차박 감지 대기 시간을 30분 → 1분으로 줄입니다", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = demo, onCheckedChange = viewModel::setDemoMode)
            }
        }
    }
}

@Composable
private fun TonightCard(viewModel: MainViewModel) {
    val f by viewModel.homeForecast.collectAsStateWithLifecycle()
    val body = f?.let {
        val p = it.prediction
        "약 ${p.socUsedPercent.roundToInt()}% 사용 예상 (${p.hours.roundToInt()}시간) · 기온 ${"%.1f".format(p.tempC)}° ${it.tempSource} · ${p.basis.label}"
    } ?: "지금 위치를 받으면 계산합니다"
    InfoCard("오늘 밤 여기서 차박하면", body)
}

// ---------- 기록 ----------

@Composable
private fun LogScreen(viewModel: MainViewModel) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    Title("기록")
    if (sessions.isEmpty()) Text("아직 차박 기록이 없습니다. 주차 후 P단으로 머무르면 자동으로 기록됩니다.")
    LazyColumn { items(sessions) { SessionCard(it) } }
}

@Composable
private fun SessionCard(s: SessionEntity) {
    val range = (if (s.isDemo) "[시연용] " else "") + "${s.startedAtMs.time()} ~ ${s.endedAtMs?.time() ?: "진행 중"}"
    val soc = if (s.socStart != null && s.socEnd != null) "배터리 ${s.socStart.pct()} → ${s.socEnd.pct()}" else "배터리 기록 없음"
    InfoCard(range, "$soc · 주차 ${s.parkingLevel ?: "-"} · 문 열림 ${s.doorOpenCount}회 · 공조 ${s.hvacOnMinutes}분")
}

// ---------- 아침 요약 ----------

@Composable
private fun MorningScreen(viewModel: MainViewModel) {
    val latest by viewModel.latest.collectAsStateWithLifecycle()
    Title("아침 요약")
    val s = latest ?: run { Text("어젯밤 기록이 없습니다."); return }
    val end = s.endedAtMs ?: System.currentTimeMillis()
    val used = if (s.socStart != null && s.socEnd != null) s.socStart - s.socEnd else null
    LazyColumn {
        item {
            Column {
                Text(
                    used?.let { "어젯밤 배터리 ${"%.0f".format(it)}% 사용" } ?: "배터리 변화 기록 없음",
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    "${s.startedAtMs.time()} ~ ${s.endedAtMs?.time() ?: "진행 중"} (${minutes(end - s.startedAtMs)})",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.padding(8.dp))
            }
        }
        item {
            Row(Modifier.fillMaxWidth()) {
                val w = Modifier.weight(1f)
                Stat("배터리", "${s.socStart?.pct() ?: "-"} → ${s.socEnd?.pct() ?: "-"}", w)
                Stat("공조 사용", "${s.hvacOnMinutes}분", w)
                Stat("문 열림", "${s.doorOpenCount}회", w)
                Stat("외기", s.outsideTempC?.let { "${"%.1f".format(it)}°" } ?: "-", w)
            }
        }
        item {
            InfoCard(
                "주차 난이도: ${s.parkingLevel ?: "기록 없음"}",
                "후진 ${s.reverseCount}회 · 핸들 끝까지 ${s.fullLockCount}회",
            )
        }
        item {
            InfoCard(
                "데이터 출처",
                listOfNotNull(
                    s.locationSource?.let { "위치: $it" },
                    s.outsideTempSource?.let { "외기: $it" },
                    "감지: ${s.detectorId} (${s.confidence})",
                ).joinToString(" · "),
            )
        }
    }
}

// ---------- 데이터 출처 ----------

@Composable
private fun SourcesScreen(viewModel: MainViewModel, live: LiveState, test: ConnectionTestViewModel) {
    val locations by test.locations.collectAsStateWithLifecycle()
    val log by test.log.collectAsStateWithLifecycle()
    LazyColumn {
        item {
            Column {
                Title("데이터 출처")
                Text("지금 이 차에서 실제로 쓰고 있는 경로입니다.", style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = viewModel::refreshSources) { Text("다시 확인") }
            }
        }
        items(live.sources) { row ->
            InfoCard(row.category, (row.active?.label ?: "없음") + (row.note?.let { " · $it" } ?: ""))
        }
        item {
            Column {
                Spacer(Modifier.padding(8.dp))
                Text("시연용 기록", style = MaterialTheme.typography.titleLarge)
                Text("영상 촬영용 예시 기록입니다. 화면에 '시연용'으로 표시되고 예측에는 쓰지 않습니다.", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.insertDemo() }) { Text("시연용 기록 넣기") }
                    OutlinedButton(onClick = { viewModel.clearDemo() }) { Text("시연용 기록 지우기") }
                }
                val msg by viewModel.message.collectAsStateWithLifecycle()
                msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
        item {
            Column {
                Spacer(Modifier.padding(8.dp))
                Text("연결 시험 (1단계)", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { test.checkLocations() }) { Text("위치 경로 확인") }
                    Button(onClick = { test.pickManually() }) { Text("직접 선택 시험") }
                }
            }
        }
        items(locations) { InfoCard(it.path, it.result) }
        item {
            Column {
                Text("NaviHelper 목적지·경유지", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { test.setDestination() }) { Text("목적지") }
                    Button(onClick = { test.addWaypoint() }) { Text("경유지 추가") }
                    Button(onClick = { test.readWaypoints() }) { Text("조회") }
                    Button(onClick = { test.removeWaypoints() }) { Text("경유지 삭제") }
                    Button(onClick = { test.cancelRoute() }) { Text("경로 취소") }
                }
            }
        }
        items(log) { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
