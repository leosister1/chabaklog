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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chabaklog.app.data.PlaceKind
import chabaklog.app.data.SessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 화면 구조 (기획서 기준)
 * - 홈: 지금 상태(차량 연결, 차박 세션 진행 여부)와 오늘 밤 예상 소모
 * - 지도: 공공 캠핑장 + 시드(시연용 명시) + 내 기록, 장소 카드와 필터
 * - 기록: 지난 차박 세션 목록
 * - 아침 요약: 어젯밤 소모량·주차 난이도
 * - 설정 > 데이터 출처: 지금 어떤 경로로 데이터를 받는지
 * 주행 중(CarUxRestrictions)에는 간소화 화면으로 바꾼다 (2단계).
 */
enum class Tab(val label: String) { HOME("홈"), MAP("지도"), LOG("기록"), MORNING("아침 요약"), SOURCES("데이터 출처") }

@Composable
fun ChabaklogRoot(viewModel: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(Modifier.width(120.dp)) {
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {},
                            label = { Text(t.label, fontSize = 18.sp) },
                        )
                    }
                }
                Column(Modifier.fillMaxSize().padding(32.dp)) {
                    when (tab) {
                        Tab.HOME -> HomeScreen()
                        Tab.MAP -> MapScreen(viewModel)
                        Tab.LOG -> LogScreen(viewModel)
                        Tab.MORNING -> MorningScreen(viewModel)
                        Tab.SOURCES -> SourcesScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.padding(8.dp))
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun HomeScreen() {
    Title("차박로그")
    InfoCard("차량 연결", "2단계에서 연결: Vehicle SDK + CarPropertyManager")
    InfoCard("차박 세션", "P단 + 시간 + 도어 패턴으로 자동 감지 (수동 시작 버튼은 최후 대체)")
    InfoCard("오늘 밤 예상 소모", "3단계에서 연결: 내 기록 + 외기 온도")
}

@Composable
private fun MapScreen(viewModel: MainViewModel) {
    val places by viewModel.places.collectAsStateWithLifecycle()
    Title("지도")
    InfoCard("지도 영역", "3단계: 네트워크 없이 동작하는 내장 지도 + 장소 표시. 위치를 모를 때 여기서 직접 선택")
    if (places.isEmpty()) Text("아직 장소 데이터가 없습니다.")
    LazyColumn {
        items(places) { p ->
            val tag = when (p.kind) {
                PlaceKind.PUBLIC_CAMPSITE -> "공공 캠핑장"
                PlaceKind.DEMO_SEED -> "시연용 데이터"
                PlaceKind.MINE -> "내 기록"
            }
            InfoCard(p.name, "$tag · ${"%.4f".format(p.latitude)}, ${"%.4f".format(p.longitude)}")
        }
    }
}

@Composable
private fun LogScreen(viewModel: MainViewModel) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    Title("기록")
    if (sessions.isEmpty()) Text("아직 차박 기록이 없습니다. 주차 후 P단으로 머무르면 자동으로 기록됩니다.")
    LazyColumn { items(sessions) { SessionCard(it) } }
}

@Composable
private fun MorningScreen(viewModel: MainViewModel) {
    val latest by viewModel.latest.collectAsStateWithLifecycle()
    Title("아침 요약")
    latest?.let { SessionCard(it) } ?: Text("어젯밤 기록이 없습니다.")
}

@Composable
private fun SourcesScreen() {
    Title("데이터 출처")
    // 2단계에서 DataSourceRegistry.report()로 교체
    listOf(
        "위치" to "AAOS 위치 → 내비 목적지 → 지도에서 직접 선택",
        "외기 온도" to "차량 외기온 → 날씨 API → 내장 월별 평균",
        "차박 감지" to "P + 시간 + 도어 패턴 (좌석 점유는 보조)",
        "주차 난이도" to "후진 횟수 + 조향 끝까지 돌린 횟수",
    ).forEach { (k, v) -> InfoCard(k, v) }
}

private val timeFormat = SimpleDateFormat("M/d HH:mm", Locale.KOREA)

@Composable
private fun SessionCard(s: SessionEntity) {
    val range = buildString {
        append(timeFormat.format(Date(s.startedAtMs)))
        append(" ~ ")
        append(s.endedAtMs?.let { timeFormat.format(Date(it)) } ?: "진행 중")
    }
    val soc = if (s.socStart != null && s.socEnd != null) "배터리 ${s.socStart}% → ${s.socEnd}%" else "배터리 기록 없음"
    InfoCard(range, "$soc · 주차 ${s.parkingLevel ?: "-"} · 감지 ${s.detectorId}(${s.confidence})")
}
