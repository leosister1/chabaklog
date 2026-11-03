package chabaklog.app.adapters

import ai.pleos.playground.vehicle.Vehicle
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

/**
 * 차량 쪽 연결을 한 곳에서 연다.
 * - Pleos Vehicle SDK: initialize()가 예외 없이 끝나도 서비스 연결 완료를 뜻하지는 않는다 (Sample App 가이드).
 * - AAOS Car: 기어·외기온(CarPropertyManager), 주행 중 화면 제한(CarUxRestrictionsManager).
 */
class VehicleConnection(context: Context) {
    private val appContext = context.applicationContext
    val vehicle = Vehicle(appContext)
    private var car: Car? = null
    var properties: CarPropertyManager? = null
        private set
    var uxRestrictions: CarUxRestrictionsManager? = null
        private set
    private var connected = false

    fun connect() {
        if (connected) return
        try { vehicle.initialize() } catch (e: Exception) { Log.w(TAG, "Vehicle initialize 실패: ${e.message}") }
        try {
            car = Car.createCar(appContext)
            properties = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            uxRestrictions = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
        } catch (e: Exception) {
            Log.w(TAG, "Car 연결 실패: ${e.message}")
        }
        connected = true
    }

    /**
     * 배터리 용량(kWh). AAOS 표준 INFO_EV_BATTERY_CAPACITY(Wh, 권한 android.car.permission.CAR_INFO).
     * 차가 알려주지 않으면 null → 예측은 기준 용량으로 계산한다.
     */
    fun batteryCapacityKwh(): Float? = try {
        (properties?.getProperty<Any>(android.car.VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY, 0)?.value as? Float)
            ?.div(1000f)?.takeIf { it in 10f..250f }
    } catch (e: Exception) {
        Log.w(TAG, "배터리 용량 조회 실패: ${e.message}"); null
    }

    /** 누적 주행거리(km). Vehicle SDK Odometer.getCurrentDistanceDriven (권한 pleos CAR_DRIVE). 못 읽으면 null */
    suspend fun odometerKm(): Float? = kotlinx.coroutines.withTimeoutOrNull(3_000) {
        kotlinx.coroutines.suspendCancellableCoroutine<Float?> { cont ->
            try {
                vehicle.getOdometer().getCurrentDistanceDriven(
                    { v -> if (cont.isActive) cont.resumeWith(Result.success(v)) },
                    { e -> Log.w(TAG, "주행거리 조회 실패: ${e.message}"); if (cont.isActive) cont.resumeWith(Result.success(null)) },
                )
            } catch (e: Exception) {
                Log.w(TAG, "주행거리 조회 예외: ${e.message}"); if (cont.isActive) cont.resumeWith(Result.success(null))
            }
        }
    }

    fun gateway(quirks: VehicleQuirks = VehicleQuirks()): PleosVehicleGateway =
        PleosVehicleGateway(PleosBindingFactory(vehicle, properties, quirks).all())

    fun release() {
        try { vehicle.release() } catch (_: Exception) {}
        try { car?.disconnect() } catch (_: Exception) {}
        connected = false
    }

    private companion object { const val TAG = "CHABAK_VEHICLE" }
}
