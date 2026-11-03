package chabaklog.app.tracking

import android.car.drivingstate.CarUxRestrictionsManager
import android.util.Log
import chabaklog.app.adapters.AaosLocationProvider
import chabaklog.app.adapters.NaviHelperGateway
import chabaklog.app.adapters.PleosVehicleGateway
import chabaklog.app.adapters.VehicleConnection
import chabaklog.app.data.AssetClimateTable
import chabaklog.app.data.ChabaklogDatabase
import chabaklog.app.data.PlaceKind
import chabaklog.app.data.PlaceRepository
import chabaklog.app.data.SessionEntity
import chabaklog.datasource.DataSourceRegistry
import chabaklog.datasource.SourceReportRow
import chabaklog.datasource.core.Availability
import chabaklog.datasource.detection.ClassificationConfig
import chabaklog.datasource.detection.Confidence
import chabaklog.datasource.detection.DetectionConfig
import chabaklog.datasource.detection.ManualSessionDetector
import chabaklog.datasource.detection.SessionClassifier
import chabaklog.datasource.detection.SessionDecision
import chabaklog.datasource.parking.ParkingDifficulty
import chabaklog.datasource.parking.ParkingResult
import chabaklog.datasource.vehicle.Gear
import chabaklog.datasource.vehicle.Subscription
import chabaklog.datasource.vehicle.VehicleEvent
import chabaklog.datasource.vehicle.VehicleSignal
import chabaklog.datasource.temperature.WeatherClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 지금 차가 어떤 단계에 있는지 (홈 화면에 표시) */
enum class Phase(val label: String) {
    CONNECTING("차량 연결 중"),
    DRIVING("주행 중"),
    PARKING_MANEUVER("주차 중 (후진)"),
    PARKED("정차 (P)"),
    SESSION("차박 중"),
}

data class LiveState(
    val phase: Phase = Phase.CONNECTING,
    val gear: Gear? = null,
    val doorOpen: Boolean? = null,
    val seatOccupied: Boolean? = null,
    val socPercent: Float? = null,
    val hvacOn: Boolean? = null,
    val outsideTempC: Float? = null,
    val steeringDeg: Float? = null,
    /** 충전 중(충전 스위치 켜짐 또는 충전구 열림). 읽을 수 없는 차면 null */
    val charging: Boolean? = null,
    val parkedSinceMs: Long? = null,
    val reverseCountLive: Int = 0,
    val lastParking: ParkingResult? = null,
    val sessionId: Long? = null,
    val sessionStartedMs: Long? = null,
    val detectorLabel: String? = null,
    val parkingLabel: String? = null,
    /** 이 차는 자동 감지 신호가 없어 차박 모드로만 기록할 수 있음 */
    val manualMode: Boolean = false,
    /** 지금 기록이 차박 모드(사용자가 직접 켬)인지 */
    val campMode: Boolean = false,
    val dwellMs: Long = 0,
    /** 시연 모드: 대기 1분, 끝날 때 시간·밤 조건 없이 차박으로 저장 */
    val demo: Boolean = false,
    /** 주행 중 화면 제한(CarUxRestrictions) */
    val restricted: Boolean = false,
    val sources: List<SourceReportRow> = emptyList(),
)

/**
 * 2단계 핵심: 차량 신호를 구독해 상태 머신을 돌리고, 차박 세션을 DB에 기록한다.
 *
 * 주행 → (R) 주차 시도 → (P) 정차 → dwell 경과 → 기록 시작 → (P 해제) 종료 → 최종 판단
 *
 * - 자동 감지는 30분 머물면 넓게 "기록"을 시작하고, 차박인지는 끝날 때 SessionClassifier가 정한다
 *   (3시간 이상 + 밤 시간대 → 차박, 둘 다 아니면 휴식, 하나만 맞으면 아침에 사용자에게 확인).
 * - 차박 모드: 사용자가 켜면 바로 기록을 시작하고 차박으로 확정한다. 끄거나 기어를 P에서 빼면 끝난다.
 * - 차량 콜백은 여러 스레드에서 오므로 모든 이벤트를 채널 하나로 모아 한 코루틴에서 순서대로 처리한다.
 * - 감지기·주차 추정기는 datasource 모듈의 전략 선택 결과를 그대로 쓴다 (지원 신호에 따라 자동 선택).
 * - 시간 경과만으로 세션이 시작될 수 있으므로 30초마다 tick을 넣는다.
 */
class SessionTracker(
    private val db: ChabaklogDatabase,
    private val connection: VehicleConnection,
    private val navi: NaviHelperGateway,
    private val aaosLocation: AaosLocationProvider,
    private val weather: WeatherClient,
    private val climate: AssetClimateTable,
    private val places: PlaceRepository,
    private val scope: CoroutineScope,
) {
    private val tag = "CHABAK_TRACKER"
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state

    private sealed interface Input {
        data class Vehicle(val event: VehicleEvent) : Input
        data class Tick(val nowMs: Long) : Input
        data class CampModeOn(val nowMs: Long) : Input
        data class CampModeOff(val nowMs: Long) : Input
    }

    private var job: Job? = null
    private var gateway: PleosVehicleGateway? = null
    private val subscriptions = mutableListOf<Subscription>()
    private var uxListener: CarUxRestrictionsManager.OnUxRestrictionsChangedListener? = null

    // 처리 코루틴 안에서만 바뀌는 값
    private lateinit var registry: DataSourceRegistry
    private var classifier = SessionClassifier()
    private var doorOpenCount = 0
    private var hvacOnSinceMs: Long? = null
    private var hvacOnAccumMs = 0L
    /** 여정: 이번 출발(P에서 벗어남) 시각과 그때 누적 주행거리. 차박 기록이 시작되면 그 기록에 담고 비운다 */
    private var tripStartMs: Long? = null
    private var tripStartOdoKm: Float? = null
    /** 지금 기록 중에 충전이 한 번이라도 감지됐는지 (끝날 때 판단을 한 단계 낮춘다) */
    private var chargingSeen = false
    /** 마지막 세션 이후(또는 앱 시작 이후) 실제로 움직였는지. 앱을 켰을 때 이미 P였다면 차박으로 세지 않는다. */
    private var movedSinceLastSession = false

    /** 앱 시작 시, 또는 시연 모드(대기 시간) 변경 시 호출 */
    fun start(dwellMs: Long, demo: Boolean = false) {
        stop()
        _state.value = LiveState(dwellMs = dwellMs, demo = demo)
        classifier = SessionClassifier(ClassificationConfig(demo = demo))
        job = scope.launch {
            connection.connect()
            val gw = connection.gateway().also { gateway = it }
            registry = DataSourceRegistry(
                vehicle = gw,
                naviLocation = navi,
                platformLocation = aaosLocation,
                navi = navi,
                weather = weather,
                climate = climate,
                detectionConfig = DetectionConfig(dwellMs = dwellMs),
            )

            // SDK 연결이 늦게 끝나면 지원 확인이 일시 실패한다. 기어가 확인될 때까지 잠시 기다린 뒤 전략을 고른다.
            for (attempt in 1..10) {
                if (gw.availability(VehicleSignal.GEAR) == Availability.AVAILABLE) break
                delay(1_000)
            }
            registry.selectStrategies()
            _state.update {
                it.copy(
                    detectorLabel = registry.detector.id.label,
                    parkingLabel = registry.parking.id.label,
                    manualMode = registry.detector is ManualSessionDetector,
                )
            }
            Log.i(tag, "감지: ${registry.detector.id.key}, 주차: ${registry.parking.id.key}")

            closeStaleSession()
            watchUxRestrictions()

            val inputs = Channel<Input>(Channel.UNLIMITED)
            val signals = VehicleSignal.entries
            // 지원 확인을 신호마다 한 번씩 먼저 한다. 바인딩이 capability 결과로 Area를 정하기 때문
            // (예: 공조 전원은 좌석 묶음 영역). 확인하지 않으면 조회·구독이 비어 버린다.
            signals.forEach { s -> Log.i(tag, "지원 확인 $s = ${gw.availability(s)}") }
            // 초기값을 먼저 넣고(기어가 이미 P면 그 시각부터 정차로 본다) 이후 알림을 구독한다.
            signals.forEach { s -> gw.readCurrent(s)?.let { inputs.send(Input.Vehicle(it)) } }
            signals.forEach { s -> subscriptions += gw.subscribe(s) { e -> inputs.trySend(Input.Vehicle(e)) } }
            launch { while (true) { delay(30_000); inputs.send(Input.Tick(System.currentTimeMillis())) } }
            manualInputs = inputs
            refreshSources()

            for (input in inputs) handle(input)
        }
    }

    fun stop() {
        job?.cancel(); job = null
        subscriptions.forEach { runCatching { it.cancel() } }; subscriptions.clear()
        gateway?.releaseAll(); gateway = null
        uxListener?.let { runCatching { connection.uxRestrictions?.unregisterListener() } }; uxListener = null
        manualInputs = null
    }

    private var manualInputs: Channel<Input>? = null
    /** 차박 모드 켜기: 바로 기록 시작(이미 자동 기록 중이면 그 기록을 차박으로 확정) */
    fun campModeOn() { manualInputs?.trySend(Input.CampModeOn(System.currentTimeMillis())) }
    /** 차박 모드 끄기: 지금 기록을 끝낸다 */
    fun campModeOff() { manualInputs?.trySend(Input.CampModeOff(System.currentTimeMillis())) }

    fun refreshSourcesAsync() { scope.launch { refreshSources() } }

    /** 위치 최후 대체: 사용자가 지도에서 고른 지점 */
    fun pickLocationManually(point: chabaklog.datasource.location.GeoPoint) {
        if (::registry.isInitialized) {
            registry.manualLocation.pick(point)
            refreshSourcesAsync()
        }
    }

    // ---------- 처리 ----------

    private suspend fun handle(input: Input) {
        when (input) {
            is Input.Vehicle -> onVehicle(input.event)
            is Input.Tick -> {
                // 충전 스위치는 변경 알림이 없어 tick마다 다시 읽는다
                (gateway?.readCurrent(VehicleSignal.CHARGING) as? VehicleEvent.ChargingChanged)?.let {
                    if (it.charging != _state.value.charging) onVehicle(it)
                }
                apply(registry.detector.onTick(input.nowMs))
                updateOpenSession(input.nowMs)
            }
            is Input.CampModeOn -> {
                val openId = _state.value.sessionId
                val gear = _state.value.gear
                if (gear != null && gear != Gear.PARK && gear != Gear.UNKNOWN) {
                    // 주행 중에는 켜지지 않는다 (화면에서도 막지만 한 번 더 확인)
                    Log.i(tag, "기어 $gear → 차박 모드 무시")
                } else if (openId != null) {
                    // 자동 감지가 이미 기록 중 → 새로 만들지 않고 이 기록을 차박 모드로 바꾼다
                    db.sessions().byId(openId)?.let { db.sessions().update(it.copy(manual = true)) }
                    _state.update { it.copy(campMode = true) }
                    Log.i(tag, "세션 #$openId 차박 모드로 전환")
                } else {
                    startSession(SessionDecision.Started(input.nowMs, Confidence.USER_DECLARED))
                }
            }
            is Input.CampModeOff -> endSession(input.nowMs)
        }
    }

    private suspend fun onVehicle(e: VehicleEvent) {
        val before = _state.value
        when (e) {
            is VehicleEvent.GearChanged -> {
                onGear(e, before)
                // P에서 벗어나면 기록을 끝낸다. 자동 감지 기록은 감지기도 Ended를 보내지만,
                // 차박 모드 기록은 감지기가 모르는 세션이라 여기서 닫는다 (두 번 닫혀도 무해).
                if (e.gear != Gear.PARK && e.gear != Gear.UNKNOWN && before.sessionId != null) endSession(e.timestampMs)
                // 여정 시작: P에서 처음 벗어날 때 (중간에 잠깐 선 곳은 같은 여정으로 이어진다)
                if (e.gear != Gear.PARK && e.gear != Gear.UNKNOWN && tripStartMs == null) {
                    tripStartMs = e.timestampMs
                    tripStartOdoKm = connection.odometerKm()
                    Log.i(tag, "여정 시작 주행거리=${tripStartOdoKm}km")
                }
            }
            is VehicleEvent.DoorChanged -> {
                if (before.sessionId != null && e.anyOpen && before.doorOpen == false) doorOpenCount++
                _state.update { it.copy(doorOpen = e.anyOpen) }
            }
            is VehicleEvent.HvacPowerChanged -> {
                if (before.sessionId != null) {
                    if (e.on && hvacOnSinceMs == null) hvacOnSinceMs = e.timestampMs
                    if (!e.on) hvacOnSinceMs?.let { hvacOnAccumMs += e.timestampMs - it; hvacOnSinceMs = null }
                }
                _state.update { it.copy(hvacOn = e.on) }
            }
            is VehicleEvent.SeatOccupancyChanged -> _state.update { it.copy(seatOccupied = e.anyOccupied) }
            is VehicleEvent.BatterySocChanged -> _state.update { it.copy(socPercent = e.percent) }
            is VehicleEvent.OutsideTemperatureChanged -> _state.update { it.copy(outsideTempC = e.celsius) }
            is VehicleEvent.ChargingChanged -> {
                if (e.charging && before.sessionId != null && !chargingSeen) {
                    chargingSeen = true
                    Log.i(tag, "기록 중 충전 감지 → 끝날 때 판단을 한 단계 낮춤")
                }
                _state.update { it.copy(charging = e.charging) }
            }
            is VehicleEvent.SteeringAngleChanged -> _state.update { it.copy(steeringDeg = e.degrees) }
        }
        // 후진도 조향도 없던 P 진입(앱 시작 시 이미 P 등)은 "주차"로 보지 않는다
        registry.parking.onEvent(e)?.takeIf { it.reverseCount > 0 || (it.fullLockCount ?: 0) > 0 }?.let { result ->
            Log.i(tag, "주차 결과: $result")
            _state.update { it.copy(lastParking = result) }
        }
        apply(registry.detector.onEvent(e))
    }

    private fun onGear(e: VehicleEvent.GearChanged, before: LiveState) {
        if (e.gear != Gear.PARK && e.gear != Gear.UNKNOWN) movedSinceLastSession = true
        val phase = when (e.gear) {
            Gear.PARK -> if (before.sessionId != null) Phase.SESSION else Phase.PARKED
            Gear.REVERSE -> Phase.PARKING_MANEUVER
            Gear.DRIVE, Gear.NEUTRAL -> if (before.phase == Phase.PARKING_MANEUVER) Phase.PARKING_MANEUVER else Phase.DRIVING
            Gear.UNKNOWN -> before.phase
        }
        val reverses = when {
            e.gear == Gear.REVERSE && before.gear != Gear.REVERSE -> before.reverseCountLive + 1
            e.gear == Gear.PARK -> before.reverseCountLive
            phase == Phase.DRIVING -> 0
            else -> before.reverseCountLive
        }
        _state.update {
            it.copy(
                gear = e.gear,
                phase = phase,
                reverseCountLive = reverses,
                parkedSinceMs = if (e.gear == Gear.PARK) (it.parkedSinceMs ?: e.timestampMs) else null,
            )
        }
    }

    private suspend fun apply(decision: SessionDecision) {
        when (decision) {
            SessionDecision.None -> Unit
            is SessionDecision.Started -> startSession(decision)
            is SessionDecision.Ended -> endSession(decision.atMs)
        }
    }

    private suspend fun startSession(d: SessionDecision.Started) {
        // 이미 기록 중이면(예: 차박 모드로 켠 뒤 자동 감지가 조건을 채움) 새로 만들지 않는다
        if (_state.value.sessionId != null) return
        val manual = d.confidence == Confidence.USER_DECLARED
        // 자동 감지는 "주행 후 주차"에서만 세션을 만든다 (빈 세션 방지). 차박 모드·직접 시작은 예외.
        if (!movedSinceLastSession && !manual) {
            Log.i(tag, "주행 없이 P 유지 → 세션 만들지 않음")
            return
        }
        movedSinceLastSession = false
        val s = _state.value
        val location = registry.location.resolve()
        val temp = registry.temperature.resolve()
        val tempValue = temp.result?.value?.value?.takeUnless { it.isNaN() }
        val parking = s.lastParking
        doorOpenCount = 0
        hvacOnAccumMs = 0
        chargingSeen = s.charging == true
        hvacOnSinceMs = if (s.hvacOn == true) d.atMs else null
        // 여정: 출발부터 지금까지 달린 거리와 들른 곳
        val odoNow = connection.odometerKm()
        val tripKm = if (odoNow != null && tripStartOdoKm != null) (odoNow - tripStartOdoKm!!).takeIf { it >= 0f } else null
        val tripStart = tripStartMs
        val stops = tripStart?.let { navi.arrivalsSince(it) }.orEmpty()
        tripStartMs = null; tripStartOdoKm = null
        // 가까운 장소(공공 캠핑장·지난 차박지)에 연결하고, 없으면 "내 차박지"를 만든다
        val place = location.result?.value?.let { places.matchOrCreate(it, d.atMs) }
        val id = db.sessions().insert(
            SessionEntity(
                startedAtMs = d.atMs,
                placeId = place?.id,
                detectorId = if (manual) "camp_mode" else registry.detector.id.key,
                confidence = d.confidence.name,
                latitude = location.result?.value?.latitude,
                longitude = location.result?.value?.longitude,
                locationSource = location.usedSource?.label,
                socStart = s.socPercent,
                socEnd = s.socPercent,
                outsideTempC = tempValue,
                outsideTempSource = if (tempValue != null) temp.usedSource?.label else null,
                reverseCount = parking?.reverseCount ?: 0,
                fullLockCount = parking?.fullLockCount ?: 0,
                parkingLevel = parking?.difficulty?.korean(),
                manual = manual,
                lastSeenAtMs = System.currentTimeMillis(),
                tripDistanceKm = tripKm,
                tripStartedAtMs = tripStart,
                tripStops = stops.takeIf { it.isNotEmpty() }?.joinToString("|"),
            )
        )
        Log.i(tag, "세션 시작 #$id (${d.confidence}) 위치=${location.usedSource?.key} 온도=${temp.usedSource?.key}")
        _state.update { it.copy(sessionId = id, sessionStartedMs = d.atMs, phase = Phase.SESSION, campMode = manual) }
        refreshSources()
    }

    private suspend fun endSession(atMs: Long) {
        val id = _state.value.sessionId ?: return
        hvacOnSinceMs?.let { hvacOnAccumMs += atMs - it; hvacOnSinceMs = null }
        db.sessions().byId(id)?.let { current ->
            val knownSpot = isKnownSpot(current)
            val verdict = classifier.classify(
                current.startedAtMs, atMs, userDeclared = current.manual, charging = chargingSeen, atKnownSpot = knownSpot,
            )
            db.sessions().update(
                current.copy(
                    endedAtMs = atMs,
                    socEnd = _state.value.socPercent ?: current.socEnd,
                    hvacOnMinutes = (hvacOnAccumMs / 60_000).toInt(),
                    doorOpenCount = doorOpenCount,
                    verdict = verdict.name,
                )
            )
            Log.i(tag, "세션 종료 #$id → $verdict (${(atMs - current.startedAtMs) / 60_000}분, 차박 모드=${current.manual}, 충전=$chargingSeen, 아는 차박지=$knownSpot)")
        }
        chargingSeen = false
        // 기어가 아직 P면(차박 모드를 끈 경우) 홈은 "정차"로 돌아간다
        _state.update {
            it.copy(
                sessionId = null, sessionStartedMs = null, lastParking = null, campMode = false,
                phase = if (it.phase == Phase.SESSION) Phase.PARKED else it.phase,
            )
        }
    }

    /** 공공 캠핑장이거나, 이미 차박으로 확정된 기록이 있는 내 차박지인지 */
    private suspend fun isKnownSpot(s: SessionEntity): Boolean {
        val placeId = s.placeId ?: return false
        val place = db.places().byId(placeId) ?: return false
        return place.kind == PlaceKind.PUBLIC_CAMPSITE || db.sessions().campingCountAt(placeId, s.id) > 0
    }

    /** 진행 중인 세션의 현재 SOC·공조 시간·문 열림을 주기적으로 저장 (앱이 꺼져도 마지막 값이 남게) */
    private suspend fun updateOpenSession(nowMs: Long) {
        val id = _state.value.sessionId ?: return
        val runningHvac = hvacOnSinceMs?.let { nowMs - it } ?: 0
        db.sessions().byId(id)?.let { current ->
            // 세션을 시작할 때 위치를 못 받았으면(내비가 아직 준비 전 등) tick마다 다시 시도해 채운다
            val located = if (current.latitude == null) {
                val loc = registry.location.resolve()
                loc.result?.value?.let { p ->
                    val place = places.matchOrCreate(p, current.startedAtMs)
                    Log.i(tag, "세션 #$id 위치 늦게 확인: ${loc.usedSource?.key}")
                    current.copy(placeId = place.id, latitude = p.latitude, longitude = p.longitude, locationSource = loc.usedSource?.label)
                } ?: current
            } else current
            db.sessions().update(
                located.copy(
                    socEnd = _state.value.socPercent ?: current.socEnd,
                    hvacOnMinutes = ((hvacOnAccumMs + runningHvac) / 60_000).toInt(),
                    doorOpenCount = doorOpenCount,
                    lastSeenAtMs = nowMs,
                )
            )
        }
    }

    /**
     * 앱이 세션 도중 종료됐다가 다시 켜졌을 때: 이어 붙이지 않고 닫는다 (중복 세션 방지).
     * - 마지막 저장 후 12시간 안에 다시 켜졌으면: 밤새 시동을 껐다가 아침에 켠 경우로 보고 지금 시각에 닫는다.
     * - 그보다 오래됐으면: 며칠짜리 기록이 생기지 않게 마지막 저장 시각에 닫는다.
     */
    private suspend fun closeStaleSession() {
        db.sessions().openSession()?.let { stale ->
            val now = System.currentTimeMillis()
            val lastSeen = stale.lastSeenAtMs
            val end = if (lastSeen != null && now - lastSeen > STALE_GAP_MS) lastSeen else now
            val verdict = classifier.classify(stale.startedAtMs, end, userDeclared = stale.manual, atKnownSpot = isKnownSpot(stale))
            db.sessions().update(stale.copy(endedAtMs = end, verdict = verdict.name))
            Log.i(tag, "이전 실행의 열린 세션 #${stale.id} 닫음 (끝=${if (end == now) "지금" else "마지막 저장"}) → $verdict")
        }
    }

    private companion object {
        /** 이보다 오래 앱이 꺼져 있었으면 열린 기록을 마지막 저장 시각에 닫는다 */
        const val STALE_GAP_MS = 12 * 60 * 60_000L
    }

    private suspend fun refreshSources() {
        if (!::registry.isInitialized) return
        _state.update { it.copy(sources = registry.report()) }
    }

    private fun watchUxRestrictions() {
        val ux = connection.uxRestrictions ?: return
        try {
            _state.update { it.copy(restricted = ux.currentCarUxRestrictions?.isRequiresDistractionOptimization == true) }
            val l = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r ->
                _state.update { it.copy(restricted = r.isRequiresDistractionOptimization) }
            }
            ux.registerListener(l)
            uxListener = l
        } catch (e: Exception) {
            Log.w(tag, "UX 제한 구독 실패: ${e.message}")
        }
    }
}

fun ParkingDifficulty.korean() = when (this) {
    ParkingDifficulty.EASY -> "쉬움"
    ParkingDifficulty.MODERATE -> "보통"
    ParkingDifficulty.HARD -> "어려움"
}
