// 앱 모듈(Android)로 옮겨서 사용하는 파일. 이 모듈의 JVM 빌드에는 포함되지 않는다.
package chabaklog.app.adapters

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import chabaklog.datasource.core.Availability
import chabaklog.datasource.location.GeoPoint
import chabaklog.datasource.location.PlatformLocationProvider

/**
 * 1순위 위치: AAOS 표준 LocationManager.
 * - 권한이 없으면 PERMISSION_DENIED (위험 권한이므로 런타임 요청 필요)
 * - 위치 프로바이더가 하나도 없으면 UNSUPPORTED
 * - 켜져 있지만 마지막 위치가 없으면 read()에서 null → 체인이 2순위로 넘어감
 */
class AaosLocationProvider(private val context: Context) : PlatformLocationProvider {
    private val manager = context.getSystemService(LocationManager::class.java)

    override suspend fun availability(): Availability {
        if (manager == null || manager.allProviders.isEmpty()) return Availability.UNSUPPORTED
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return Availability.PERMISSION_DENIED
        val enabled = manager.getProviders(true).isNotEmpty()
        return if (enabled) Availability.AVAILABLE else Availability.TEMPORARILY_UNAVAILABLE
    }

    @Suppress("MissingPermission") // availability()에서 권한을 확인한 뒤에만 호출된다
    override suspend fun lastKnown(): GeoPoint? =
        manager?.getProviders(true)
            ?.mapNotNull { manager.getLastKnownLocation(it) }
            ?.maxByOrNull { it.time }
            ?.let { GeoPoint(it.latitude, it.longitude, it.accuracy) }
}
