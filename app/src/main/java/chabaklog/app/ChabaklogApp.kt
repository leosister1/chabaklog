package chabaklog.app

import android.app.Application
import android.content.Context
import chabaklog.app.adapters.AaosLocationProvider
import chabaklog.app.adapters.NaviHelperGateway
import chabaklog.app.adapters.VehicleConnection
import chabaklog.app.data.ChabaklogDatabase
import chabaklog.app.tracking.SessionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ChabaklogApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: ChabaklogDatabase by lazy { ChabaklogDatabase.create(this) }
    val navi: NaviHelperGateway by lazy { NaviHelperGateway(this).also { it.connect() } }
    val vehicle: VehicleConnection by lazy { VehicleConnection(this) }
    val tracker: SessionTracker by lazy {
        SessionTracker(database, vehicle, navi, AaosLocationProvider(this), appScope)
    }

    private val prefs by lazy { getSharedPreferences("chabaklog", Context.MODE_PRIVATE) }
    private val _demoMode = MutableStateFlow(false)
    /** 시연 모드: 차박 감지 대기 시간을 30분 → 1분으로 줄인다 (영상 녹화·Virtual Console 시험용) */
    val demoMode: StateFlow<Boolean> = _demoMode

    override fun onCreate() {
        super.onCreate()
        _demoMode.value = prefs.getBoolean(KEY_DEMO, false)
        tracker.start(dwellMs())
    }

    fun setDemoMode(on: Boolean) {
        prefs.edit().putBoolean(KEY_DEMO, on).apply()
        _demoMode.value = on
        tracker.start(dwellMs())
    }

    private fun dwellMs() = if (_demoMode.value) DEMO_DWELL_MS else NORMAL_DWELL_MS

    companion object {
        private const val KEY_DEMO = "demo_mode"
        const val NORMAL_DWELL_MS = 30 * 60_000L
        const val DEMO_DWELL_MS = 60_000L
    }
}
