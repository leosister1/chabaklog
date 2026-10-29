package chabaklog.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import chabaklog.app.ui.ChabaklogRoot
import chabaklog.app.ui.ConnectionTestViewModel
import chabaklog.app.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels { MainViewModel.factory(application as ChabaklogApp) }
    private val testViewModel: ConnectionTestViewModel by viewModels { ConnectionTestViewModel.factory(application as ChabaklogApp) }

    // 위치 1순위(AAOS)는 위험 권한이라 런타임 요청이 필요하다. 거부되면 체인이 2순위로 넘어간다.
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        setContent { ChabaklogRoot(viewModel, testViewModel) }
    }
}
