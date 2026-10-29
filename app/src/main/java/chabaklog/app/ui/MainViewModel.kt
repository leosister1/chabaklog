package chabaklog.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import chabaklog.app.ChabaklogApp
import chabaklog.app.data.ChabaklogDatabase
import chabaklog.app.data.PlaceEntity
import chabaklog.app.data.SessionEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** 화면 구조 단계의 ViewModel. 차량 연결·감지·예측은 2~3단계에서 DataSourceRegistry로 연결한다. */
class MainViewModel(db: ChabaklogDatabase) : ViewModel() {
    val sessions: StateFlow<List<SessionEntity>> =
        db.sessions().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val latest: StateFlow<SessionEntity?> =
        db.sessions().observeLatest().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val places: StateFlow<List<PlaceEntity>> =
        db.places().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        fun factory(app: ChabaklogApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(app.database) }
        }
    }
}
