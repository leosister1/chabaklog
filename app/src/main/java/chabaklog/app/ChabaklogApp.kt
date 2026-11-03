package chabaklog.app

import android.app.Application
import android.content.Context
import chabaklog.app.adapters.AaosLocationProvider
import chabaklog.app.adapters.NaviHelperGateway
import chabaklog.app.adapters.OpenMeteoWeatherClient
import chabaklog.app.adapters.VehicleConnection
import chabaklog.app.data.AssetClimateTable
import chabaklog.app.data.ChabaklogDatabase
import chabaklog.app.data.KoreaOutline
import chabaklog.app.data.PoiIndex
import chabaklog.app.data.ToiletIndex
import chabaklog.app.data.PlaceRepository
import chabaklog.app.tracking.Appliance
import chabaklog.app.tracking.Forecaster
import chabaklog.app.tracking.SessionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChabaklogApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: ChabaklogDatabase by lazy { ChabaklogDatabase.create(this) }
    val navi: NaviHelperGateway by lazy { NaviHelperGateway(this).also { it.connect() } }
    val vehicle: VehicleConnection by lazy { VehicleConnection(this) }
    val weather by lazy { OpenMeteoWeatherClient() }
    val climate by lazy { AssetClimateTable(this) }
    val outline by lazy { KoreaOutline(this) }
    val toilets by lazy { ToiletIndex(this) }
    val pois by lazy { PoiIndex(this) }
    val places by lazy { PlaceRepository(this, database) }
    val forecaster by lazy {
        Forecaster(database, weather, climate, batteryKwh = { vehicle.batteryCapacityKwh() }, v2lPlan = { _v2l.value })
    }
    val tracker: SessionTracker by lazy {
        SessionTracker(database, vehicle, navi, AaosLocationProvider(this), weather, climate, places, appScope)
    }

    private val prefs by lazy { getSharedPreferences("chabaklog", Context.MODE_PRIVATE) }
    private val _demoMode = MutableStateFlow(false)
    /**
     * 시연 모드: 차박 감지 대기 시간을 30분 → 1분으로 줄이고, 끝날 때 3시간·밤 조건 없이 차박으로 저장한다
     * (영상 녹화·Virtual Console 시험용)
     */
    val demoMode: StateFlow<Boolean> = _demoMode

    private val _v2l = MutableStateFlow<Set<Appliance>>(emptySet())
    /** 오늘 밤 V2L로 쓸 가전 (홈에서 고름, 앱을 다시 켜도 유지) */
    val v2l: StateFlow<Set<Appliance>> = _v2l

    override fun onCreate() {
        super.onCreate()
        _demoMode.value = prefs.getBoolean(KEY_DEMO, false)
        _v2l.value = prefs.getString(KEY_V2L, "").orEmpty().split(",")
            .mapNotNull { name -> Appliance.entries.firstOrNull { it.name == name } }.toSet()
        appScope.launch { places.seedIfEmpty() }
        tracker.start(dwellMs(), demo = _demoMode.value)
    }

    fun setDemoMode(on: Boolean) {
        prefs.edit().putBoolean(KEY_DEMO, on).apply()
        _demoMode.value = on
        tracker.start(dwellMs(), demo = on)
    }

    fun toggleAppliance(a: Appliance) {
        val next = _v2l.value.let { if (a in it) it - a else it + a }
        _v2l.value = next
        prefs.edit().putString(KEY_V2L, next.joinToString(",") { it.name }).apply()
    }

    private fun dwellMs() = if (_demoMode.value) DEMO_DWELL_MS else NORMAL_DWELL_MS

    companion object {
        private const val KEY_DEMO = "demo_mode"
        private const val KEY_V2L = "v2l_appliances"
        const val NORMAL_DWELL_MS = 30 * 60_000L
        const val DEMO_DWELL_MS = 60_000L
    }
}
