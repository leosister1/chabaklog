package chabaklog.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import chabaklog.app.ChabaklogApp
import chabaklog.app.data.PlaceEntity
import chabaklog.app.data.SessionEntity
import chabaklog.app.tracking.LiveState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class MainViewModel(private val app: ChabaklogApp) : ViewModel() {
    private val db = app.database

    val live: StateFlow<LiveState> = app.tracker.state
    val demoMode: StateFlow<Boolean> = app.demoMode

    val sessions: StateFlow<List<SessionEntity>> =
        db.sessions().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val latest: StateFlow<SessionEntity?> =
        db.sessions().observeLatestEnded().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val places: StateFlow<List<PlaceEntity>> =
        db.places().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setDemoMode(on: Boolean) = app.setDemoMode(on)
    fun manualStart() = app.tracker.manualStart()
    fun manualEnd() = app.tracker.manualEnd()
    fun refreshSources() = app.tracker.refreshSourcesAsync()

    companion object {
        fun factory(app: ChabaklogApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(app) }
        }
    }
}
