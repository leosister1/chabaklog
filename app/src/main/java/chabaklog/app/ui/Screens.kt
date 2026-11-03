package chabaklog.app.ui

import chabaklog.app.BuildConfig

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
    /** 시연 모드의 "주행 중 화면 미리보기" (adb 설치 앱은 실제 주행 중에 시스템이 앱을 가려 이 화면이 보이지 않는다) */
    var drivingPreview by rememberSaveable { mutableStateOf(false) }
    val live by viewModel.live.collectAsStateWithLifecycle()
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density * CAR_SCALE, base.fontScale)) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                if (live.restricted) {
                    DrivingScreen(live)
                    return@Surface
                }
                if (drivingPreview) {
                    DrivingScreen(live, onClosePreview = { drivingPreview = false })
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
                            Tab.HOME -> HomeScreen(viewModel, live, onPreviewDriving = { drivingPreview = true })
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

/**
 * 주행 중(CarUxRestrictions가 최적화 화면을 요구할 때) 보여 주는 간소화 화면.
 * onClosePreview가 있으면 시연용 미리보기: 위에 "미리보기" 표시와 닫기 버튼을 붙인다.
 */
@Composable
private fun DrivingScreen(live: LiveState, onClosePreview: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (onClosePreview != null) {
            Text(
                "미리보기 · 실제 차에서는 주행을 시작하면 자동으로 이 화면으로 바뀝니다",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.padding(16.dp))
        }
        Text("주행 중", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.padding(12.dp))
        Text("차박로그가 주차를 기다리고 있어요", style = MaterialTheme.typography.headlineSmall)
        live.socPercent?.let {
            Spacer(Modifier.padding(12.dp))
            Text("배터리 ${it.pct()}", style = MaterialTheme.typography.headlineMedium)
        }
        if (onClosePreview != null) {
            Spacer(Modifier.padding(24.dp))
            OutlinedButton(onClick = onClosePreview) { Text("미리보기 닫기") }
        }
    }
}

// ---------- 홈 ----------

@Composable
private fun HomeScreen(viewModel: MainViewModel, live: LiveState, onPreviewDriving: () -> Unit) {
    val demo by viewModel.demoMode.collectAsStateWithLifecycle()
    // 정차 경과 시간을 화면에서 갱신하기 위한 시계
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(10_000); value = System.currentTimeMillis() }
    }
    LazyColumn {
        item {
            Column {
                Title("차박로그")
                // 자동 감지 기록은 끝날 때 차박인지 정해지므로 "기록 중"으로 보여 준다.
                // 차박 모드(직접 확정)와 시연 모드(충전이 없으면 끝나면 차박)는 "차박 중".
                val confirmed = live.campMode || (live.demo && live.charging != true)
                val phaseLabel =
                    if (live.phase == Phase.SESSION && !confirmed) "기록 중" else live.phase.label
                Text(phaseLabel, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.padding(6.dp))
                val sub = when {
                    live.sessionStartedMs != null && live.campMode -> "차박 모드 · ${live.sessionStartedMs.time()}부터 기록 중"
                    live.sessionStartedMs != null && live.demo -> "${live.sessionStartedMs.time()}부터 기록 중"
                    live.sessionStartedMs != null ->
                        "${live.sessionStartedMs.time()}부터 기록 중 · 3시간 넘게 밤을 보내면 차박으로 저장"
                    live.parkedSinceMs != null ->
                        "정차 ${minutes(now - live.parkedSinceMs)} · ${minutes(live.dwellMs)} 머무르면 자동 기록 시작"
                    live.phase == Phase.PARKING_MANEUVER -> "후진 ${live.reverseCountLive}회"
                    else -> ""
                }
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.titleMedium)
                if (live.charging == true && !live.campMode) {
                    Text(
                        "충전 중 · 자동 기록은 충전 대기로 보고 바로 차박으로 저장하지 않아요",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
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
        live.sessionId?.let { id ->
            item {
                val sessions by viewModel.sessions.collectAsStateWithLifecycle()
                sessions.firstOrNull { it.id == id }?.let { JourneyCard("오늘의 여정", it, sessions) }
            }
        }
        item { TonightCard(viewModel) }
        item { V2lRow(viewModel) }
        live.lastParking?.let { p ->
            item {
                InfoCard(
                    "방금 주차",
                    "후진 ${p.reverseCount}회" + (p.fullLockCount?.let { " · 핸들 끝까지 ${it}회" } ?: "") +
                        " · ${minutes(p.durationMs)}",
                )
            }
        }
        item { CampModeRow(viewModel, live) }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("시연 모드", style = MaterialTheme.typography.titleMedium)
                    Text("감지 대기 30분 → 1분, 끝나면 3시간·밤 조건 없이 차박으로 저장", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = demo, onCheckedChange = viewModel::setDemoMode)
            }
        }
        if (demo) {
            item {
                OutlinedButton(onClick = onPreviewDriving, modifier = Modifier.padding(top = 8.dp)) {
                    Text("주행 중 화면 미리보기")
                }
            }
        }
    }
}

/**
 * 차박 모드: 사용자가 직접 켜서 바로 기록하고 차박으로 확정한다.
 * 주차(P)했을 때만 켤 수 있고, 끄거나 기어를 P에서 빼면 기록이 끝난다.
 */
@Composable
private fun CampModeRow(viewModel: MainViewModel, live: LiveState) {
    val parked = live.gear == null || live.gear == Gear.PARK || live.gear == Gear.UNKNOWN
    val desc = when {
        live.campMode -> "끄면 기록을 끝내요 · 기어를 P에서 빼도 끝나요"
        !parked -> "주차(P)했을 때 켤 수 있어요"
        live.sessionId != null -> "지금 자동으로 기록 중이에요 · 켜면 이 밤을 차박으로 확정해요"
        live.manualMode -> "이 차는 자동 감지 신호가 없어 차박 모드로 기록해요"
        else -> "켜면 ${minutes(live.dwellMs)}을 기다리지 않고 바로 기록을 시작하고, 차박으로 저장해요"
    }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("차박 모드", style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = live.campMode, onCheckedChange = viewModel::setCampMode, enabled = parked || live.campMode)
    }
}

@Composable
private fun TonightCard(viewModel: MainViewModel) {
    val f by viewModel.homeForecast.collectAsStateWithLifecycle()
    val body = f?.let { forecastText(it) } ?: "지금 위치를 받으면 계산합니다"
    InfoCard("오늘 밤 여기서 차박하면", body)
}

/** "약 N% 사용 예상 …" 한 덩어리 설명. V2L과 배터리 용량 보정이 있으면 함께 적는다. */
internal fun forecastText(f: chabaklog.app.tracking.Forecast): String {
    val p = f.prediction
    val lines = mutableListOf(
        "약 ${f.totalSocPercent.roundToInt()}% 사용 예상 (${p.hours.roundToInt()}시간) · 기온 ${"%.1f".format(p.tempC)}° ${f.tempSource} · ${p.basis.label}"
    )
    if (f.v2l.appliances.isNotEmpty()) {
        lines += "차박 ${p.socUsedPercent.roundToInt()}% + V2L ${"%.1f".format(f.v2l.socPercent)}% " +
            "(${f.v2l.appliances.joinToString { it.label }}, 약 ${(f.v2l.wattHours / 1000f).let { "%.1f".format(it) }}kWh)"
    }
    p.batteryKwhAdjusted?.let { lines += "내 차 배터리 ${it.roundToInt()}kWh 기준으로 보정" }
    if (f.v2l.appliances.isNotEmpty() && !f.v2l.capacityKnown) lines += "배터리 용량을 읽지 못해 77kWh 기준으로 계산"
    return lines.joinToString("\n")
}

/** 오늘 밤 V2L로 쓸 가전 고르기. 소비 전력·시간은 흔한 캠핑용 제품 기준 대략값 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun V2lRow(viewModel: MainViewModel) {
    val chosen by viewModel.v2l.collectAsStateWithLifecycle()
    Column(Modifier.padding(top = 12.dp)) {
        Text("오늘 밤 차 배터리로 쓸 전기 (V2L)", style = MaterialTheme.typography.titleMedium)
        Text("고르면 예상 사용량에 더해요 · 소비 전력은 대략값", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chabaklog.app.tracking.Appliance.entries.forEach { a ->
                androidx.compose.material3.FilterChip(
                    selected = a in chosen,
                    onClick = { viewModel.toggleAppliance(a) },
                    label = {
                        val h = if (a.hours < 1f) "${(a.hours * 60).roundToInt()}분" else "${a.hours.roundToInt()}시간"
                        Text("${a.label} ${a.watts}W·$h", fontSize = 13.sp)
                    },
                )
            }
        }
    }
}

// ---------- 기록 ----------

@Composable
private fun LogScreen(viewModel: MainViewModel) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    Title("기록")
    if (sessions.isEmpty()) Text("아직 기록이 없습니다. 주차 후 P단으로 머무르거나 홈에서 차박 모드를 켜면 기록됩니다.")
    LazyColumn { items(sessions) { SessionCard(it) } }
}

@Composable
private fun SessionCard(s: SessionEntity) {
    val tags = listOfNotNull(
        if (s.isDemo) "시연용" else null,
        verdictLabel(s.verdict),
        if (s.manual) "차박 모드" else null,
    ).joinToString(" · ")
    val range = "[$tags] ${s.startedAtMs.time()} ~ ${s.endedAtMs?.time() ?: "진행 중"}"
    val soc = if (s.socStart != null && s.socEnd != null) "배터리 ${s.socStart.pct()} → ${s.socEnd.pct()}" else "배터리 기록 없음"
    InfoCard(range, "$soc · 주차 ${s.parkingLevel ?: "-"} · 문 열림 ${s.doorOpenCount}회 · 공조 ${s.hvacOnMinutes}분")
}

private fun verdictLabel(v: String) = when (v) {
    "CAMPING" -> "차박"
    "REST" -> "휴식"
    "ASK" -> "확인 필요"
    else -> "기록 중"
}

// ---------- 아침 요약 ----------

@Composable
private fun MorningScreen(viewModel: MainViewModel) {
    val latest by viewModel.latest.collectAsStateWithLifecycle()
    Title("아침 요약")
    val s = latest ?: run { Text("어젯밤 기록이 없습니다."); return }
    val end = s.endedAtMs ?: System.currentTimeMillis()
    val used = if (s.socStart != null && s.socEnd != null) s.socStart - s.socEnd else null
    val msg by viewModel.message.collectAsStateWithLifecycle()
    LazyColumn {
        if (s.verdict == "ASK") {
            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("어젯밤 차박이었나요?", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "머문 시간·시간대나 충전 여부가 보통 차박과 달라서 여쭤봐요. 차박이면 다음 예측에 써요.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { viewModel.answerVerdict(s.id, true) }) { Text("네, 차박이에요") }
                            OutlinedButton(onClick = { viewModel.answerVerdict(s.id, false) }) { Text("아니요, 잠깐 쉰 거예요") }
                        }
                    }
                }
            }
        }
        msg?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
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
            val sessions by viewModel.sessions.collectAsStateWithLifecycle()
            JourneyCard("어제의 여정", s, sessions)
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
                    if (s.manual) "기록: 차박 모드(직접)" else "감지: ${s.detectorId} (${s.confidence})",
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
    val extras by viewModel.sourceExtras.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshSources() }
    val vehicleRows = vehicleSourceRows(live, extras)
    LazyColumn {
        item {
            Column {
                Title("데이터 출처")
                Text(
                    "이 앱이 쓰는 값이 어디서 오는지 보여줘요. 외부 서버 없이 차량 데이터와 앱에 들어 있는 공개 데이터로 동작해요.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedButton(onClick = viewModel::refreshSources) { Text("다시 확인") }
            }
        }
        item { SectionHeader("차에서 읽는 값", "못 읽는 값은 대체값으로 계산하고, 그 사실을 여기에 적어요.") }
        items(vehicleRows) { (title, body) -> InfoCard(title, body) }
        item { SectionHeader("앱에 들어 있는 데이터", "인터넷 없이도 지도·예측·경유지 추천이 동작하도록 앱 안에 넣어 둔 공개 데이터예요.") }
        items(bundledSourceRows(extras)) { (title, body) -> InfoCard(title, body) }
        item { SectionHeader("추정값과 판단 기준", "직접 잴 수 없는 값은 아래 기준으로 계산해요.") }
        items(estimateRows(extras)) { (title, body) -> InfoCard(title, body) }
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
        // 개발용 연결 시험(1단계)은 debug 빌드에서만 보인다. release(제출본)에서는 숨김.
        if (BuildConfig.DEBUG) {
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
}

// ---------- 데이터 출처 묶음 ----------

@Composable
private fun SectionHeader(title: String, sub: String) {
    Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(sub, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun status(ok: Boolean?) = when (ok) { true -> "읽는 중"; false -> "값 없음"; null -> "확인 중" }

/** 차에서 읽는 값: 항목 · 상태 → 어떤 경로로, 지금 값, 못 읽으면 무엇으로 대신하는지 */
private fun vehicleSourceRows(live: LiveState, x: chabaklog.app.ui.SourceExtras): List<Pair<String, String>> {
    val row = { name: String -> live.sources.firstOrNull { it.category == name } }
    val rows = mutableListOf<Pair<String, String>>()
    row("위치")?.let { r ->
        val used = r.active?.label
        rows += "위치 · ${if (used != null) "읽는 중" else "값 없음"}" to
            (used?.let { "지금 경로: $it" } ?: "차 위치를 못 읽었어요") + (r.note?.let { " · $it" } ?: "") +
            "\n못 읽으면 다음 경로로 넘어가고, 끝까지 없으면 지도에서 직접 골라요"
    }
    val tempRow = row("외기 온도")
    rows += "외기 온도 · ${status(live.outsideTempC != null)}" to (
        live.outsideTempC?.let { "차량 외기온 센서 · ${"%.1f".format(it)}°" }
            ?: ("센서 값이 없어 " + (tempRow?.active?.label ?: "날씨 예보·평년값") + "으로 대신해요")
        )
    val basics = listOf(
        "기어" to live.gear?.let { it.korean() },
        "문" to live.doorOpen?.let { if (it) "열림" else "닫힘" },
        "공조" to live.hvacOn?.let { if (it) "켜짐" else "꺼짐" },
        "배터리" to live.socPercent?.let { "${it.roundToInt()}%" },
    )
    val missing = basics.filter { it.second == null }.map { it.first }
    rows += "기어 · 문 · 공조 · 배터리 · ${if (missing.isEmpty()) "읽는 중" else "일부 없음"}" to (
        "Pleos Vehicle SDK · " + basics.filter { it.second != null }.joinToString(", ") { "${it.first} ${it.second}" } +
            (if (missing.isNotEmpty()) "\n못 읽는 값: ${missing.joinToString(", ")}" else "")
        )
    rows += "충전 여부 · ${status(live.charging != null)}" to (
        when (live.charging) {
            true -> "충전 중 · 충전 스위치·충전구 상태(Vehicle SDK)\n충전하며 머문 시간은 바로 차박으로 저장하지 않고 확인해요"
            false -> "충전 안 함 · 충전 스위치·충전구 상태(Vehicle SDK)"
            null -> "이 차에서는 못 읽어요 · 충전 여부 없이 시간·밤 조건으로만 판단해요"
        }
        )
    rows += "배터리 용량 · ${if (x.checked) status(x.batteryKwh != null) else "확인 중"}" to (
        x.batteryKwh?.let { "${it.roundToInt()}kWh · AAOS 차량 정보(INFO_EV_BATTERY_CAPACITY)\n배터리 사용 예측과 V2L %를 이 용량으로 계산해요" }
            ?: "차가 알려주지 않아 77.4kWh 기준값으로 계산해요"
        )
    rows += "누적 주행거리 · ${if (x.checked) status(x.odometerKm != null) else "확인 중"}" to (
        x.odometerKm?.let { "${"%,.0f".format(it)}km · Vehicle SDK Odometer\n여정 카드의 '달려 온 거리'를 출발 때와의 차이로 계산해요" }
            ?: "못 읽어요 · 여정 카드에 거리를 표시하지 않아요"
        )
    rows += "내비게이션 · ${when (x.naviConnected) { true -> "연결됨"; false -> "연결 안 됨"; null -> "확인 중" }}" to (
        if (x.naviConnected == false) "도착 예상과 경유지 넣기를 쓸 수 없어요 · 나머지 기능은 그대로 동작해요"
        else "NaviHelper SDK · 도착 예상(거리·시간·도착 시 배터리), 경유지 넣기, 경유지·목적지 도착 알림(여정의 '들른 곳')"
        )
    row("차박 감지")?.let { r -> rows += "차박 감지 경로 · ${r.active?.label ?: "없음"}" to "자동 감지에 쓰는 신호 조합이에요. 신호가 없는 차는 차박 모드로 직접 기록해요" }
    row("주차 난이도")?.let { r -> rows += "주차 난이도 경로 · ${r.active?.label ?: "없음"}" to "후진 횟수와 핸들을 끝까지 돌린 횟수로 계산해요" }
    return rows
}

private fun bundledSourceRows(x: chabaklog.app.ui.SourceExtras): List<Pair<String, String>> {
    fun n(v: Int) = if (x.checked) "${v}곳" else "…"
    return listOf(
        "공공 우수야영장 ${n(x.campsites)}" to "문화체육관광부·한국관광공사 공공 우수야영장(2023~2025 선정), 고캠핑 좌표 · 공공데이터포털",
        "캠핑장 근처 공중화장실 ${n(x.toilets)}" to "행정안전부 전국공중화장실표준데이터(2026-05) · 좌표는 OpenStreetMap Nominatim으로 변환, 캠핑장 5km 안",
        "편의점 ${n(x.convenience)} · 마트 ${n(x.supermarkets)} · 전기차 충전소 ${n(x.chargers)}" to
            "© OpenStreetMap contributors (ODbL), 2026-11-03 기준 · 캠핑장 반경 5km · OSM에 없는 곳은 빠져 있을 수 있어요",
        "평년 최저기온 ${if (x.checked) "${x.climateStations}개 지점" else "…"}" to "기상청 1991~2020 평년값 · 날씨 예보를 못 받을 때 오늘 밤 기온으로 써요",
        "지도 윤곽" to "Natural Earth 1:50m",
        "인터넷 · ${when (x.internet) { true -> "연결됨"; false -> "연결 안 됨"; null -> "확인 중" }}" to
            "Open-Meteo 날씨 예보(오늘 밤 최저기온)에만 써요. 연결이 없으면 평년값으로 계산해요",
        "내 기록 저장" to "차박 기록과 내 장소는 이 차 안에만 저장해요. 외부 서버로 보내지 않아요",
    )
}

private fun estimateRows(x: chabaklog.app.ui.SourceExtras): List<Pair<String, String>> = listOf(
    "배터리 사용 예측 · ${if (x.usableNights >= 3) "내 기록 기준" else "기본값"}" to (
        "기온이 낮을수록 시간당 사용량이 늘어나는 곡선으로 계산해요.\n" +
            "내 차박 기록이 3건 이상(기온 차 3° 이상) 쌓이면 내 차에 맞게 바뀌어요 · 지금 쓸 수 있는 기록 ${x.usableNights}건"
        ),
    "V2L 사용량 · 대략값" to (
        "차량 SDK에 V2L 사용량을 읽는 기능이 없어, 고른 가전의 소비 전력 × 사용 시간으로 계산해요.\n" +
            chabaklog.app.tracking.Appliance.entries.joinToString(" · ") { a ->
                val h = if (a.hours < 1f) "${(a.hours * 60).roundToInt()}분" else "${a.hours.roundToInt()}시간"
                "${a.label} ${a.watts}W·$h"
            }
        ),
    "도착 시 배터리" to "내비게이션이 경로로 계산한 값을 그대로 보여주고, 그 위에 오늘 밤 예상 사용량을 빼서 아침 잔량을 알려줘요",
    "차박 판단" to (
        "3시간 이상 머물고 밤(22~06시)에 걸치면 차박, 둘 다 아니면 휴식, 하나만 맞으면 아침에 물어봐요.\n" +
            "충전하며 머문 경우는 한 단계 낮추고, 공공 캠핑장이나 전에 차박한 곳이면 한 단계 올려요. 차박 모드로 직접 켜면 항상 차박이에요."
        ),
)

// ---------- 여정 ----------

private val clockFormat = SimpleDateFormat("a h:mm", Locale.KOREA)

/**
 * 여정 카드: 출발부터 이 밤까지 달린 거리, 들른 곳, 그리고 내 기록 속 이 밤의 위치(올해 몇 번째, 이곳 몇 번째, 가장 추운 밤).
 * 거리는 차량 누적 주행거리 차이, 들른 곳은 내비 도착 알림, 나머지는 차 안 기록에서 계산한다.
 */
@Composable
internal fun JourneyCard(title: String, s: SessionEntity, all: List<SessionEntity>) {
    val lines = mutableListOf<String>()
    s.tripDistanceKm?.let { km ->
        val start = s.tripStartedAtMs?.let { " · ${clockFormat.format(Date(it))} 출발" } ?: ""
        lines += "${"%.1f".format(km)}km 달려 왔어요$start"
    }
    s.tripStops?.split("|")?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let {
        lines += "들른 곳: ${it.joinToString(", ")}"
    }
    val nights = all.filter { !it.isDemo && (it.isCamping || it.id == s.id) && it.startedAtMs <= s.startedAtMs }
    val year = java.util.Calendar.getInstance().apply { timeInMillis = s.startedAtMs }.get(java.util.Calendar.YEAR)
    val thisYear = nights.count {
        java.util.Calendar.getInstance().apply { timeInMillis = it.startedAtMs }.get(java.util.Calendar.YEAR) == year
    }
    lines += "올해 ${thisYear}번째 차박 밤"
    if (s.placeId != null) {
        val here = nights.count { it.placeId == s.placeId }
        if (here >= 2) lines += "이곳에서 ${here}번째 밤"
    }
    s.outsideTempC?.let { t ->
        val colder = all.filter { !it.isDemo && it.isCamping && it.id != s.id }.mapNotNull { it.outsideTempC }
        if (colder.isNotEmpty() && colder.all { it > t }) lines += "지금까지 가장 추운 차박 밤 (${"%.1f".format(t)}°)"
    }
    if (s.tripDistanceKm == null && s.tripStops == null) lines += "주행 기록이 없어요 (출발부터 앱이 켜져 있어야 거리를 잴 수 있어요)"
    InfoCard(title, lines.joinToString("\n"))
}
