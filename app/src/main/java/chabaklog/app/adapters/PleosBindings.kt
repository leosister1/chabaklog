package chabaklog.app.adapters

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.car.VehiclePropertyIds
import android.car.VehicleGear
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.util.Log
import chabaklog.datasource.core.Availability
import chabaklog.datasource.vehicle.Gear
import chabaklog.datasource.vehicle.Subscription
import chabaklog.datasource.vehicle.VehicleEvent
import chabaklog.datasource.vehicle.VehicleSignal
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/*
 * 1단계 결과표(2026-10-29, Pleos_Connect_v2.1.0 AVD)로 채운 신호별 바인딩.
 *
 * | 신호                | 경로                          | Area                    | 권한                                 |
 * |---------------------|-------------------------------|-------------------------|--------------------------------------|
 * | GEAR                | AAOS GEAR_SELECTION           | 전역(0)                 | android.car.permission.CAR_POWERTRAIN|
 * | SEAT_OCCUPANCY      | Seat.*SeatOccupancy           | 좌석별 → any            | pleos CAR_SEATS                      |
 * | DOOR_OPEN           | Door.*DoorPosition            | 문 4개 → any            | pleos CAR_DOORS                      |
 * | BATTERY_SOC         | EvBattery.*StateOfCharge      | 없음                    | pleos CAR_ENERGY                     |
 * | HVAC_POWER          | HVAC.*HvacPowerState          | capability가 준 묶음 영역 | pleos CAR_CLIMATE                    |
 * | STEERING_ANGLE      | SteeringWheel.*SteeringWheelAngle | 없음                | pleos CAR_DRIVE                      |
 * | OUTSIDE_TEMPERATURE | AAOS ENV_OUTSIDE_TEMPERATURE  | 전역(0)                 | android.car.permission.CAR_EXTERIOR_ENVIRONMENT |
 *
 * 규칙
 * - 지원 확인 실패(예외·onFailed)는 UNSUPPORTED로 단정하지 않고 TEMPORARILY_UNAVAILABLE.
 * - 등록 성공은 알림 수신이 아니다. 첫 알림 전까지는 current()로 초기값을 채운다.
 * - 해제는 등록 때와 같은 Listener 객체·Area로.
 */

private const val TAG = "CHABAK_VEHICLE"

private fun CapabilityConfig.toAvailability() =
    if (accessType == AccessType.READ || accessType == AccessType.READ_WRITE) Availability.AVAILABLE
    else Availability.UNSUPPORTED

private fun now() = System.currentTimeMillis()

/** 콜백형 Pleos API를 suspend로. 예외는 실패 콜백과 똑같이 취급한다. */
private suspend fun <T> awaitCallback(block: (ok: (T) -> Unit, fail: (Exception) -> Unit) -> Unit): Result<T> =
    suspendCancellableCoroutine { cont ->
        try {
            block(
                { if (cont.isActive) cont.resume(Result.success(it)) },
                { if (cont.isActive) cont.resume(Result.failure(it)) },
            )
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(Result.failure(e))
        }
    }

private fun Result<Availability>.orTemporary(signal: VehicleSignal): Availability =
    getOrElse { Log.w(TAG, "$signal capability 실패: ${it.message}"); Availability.TEMPORARILY_UNAVAILABLE }

/**
 * 좌석 점유 해석. 1단계에서 에뮬레이터는 콘솔 Empty → true, Occupied → false로 반대로 나왔다.
 * 실차 동작은 확인 전이므로 해석을 이 한 곳에서만 뒤집는다.
 */
data class VehicleQuirks(val seatOccupancyInverted: Boolean = true)

class PleosBindingFactory(
    private val vehicle: Vehicle,
    private val carProperties: CarPropertyManager?,
    private val quirks: VehicleQuirks = VehicleQuirks(),
) {
    fun all(): List<PleosSignalBinding> = listOf(
        gear(), seatOccupancy(), doorOpen(), batterySoc(), hvacPower(), steeringAngle(), outsideTemperature(),
    )

    // ---------- AAOS 표준 속성 ----------

    private fun aaosBinding(
        signal: VehicleSignal,
        propertyId: Int,
        toEvent: (CarPropertyValue<*>) -> VehicleEvent?,
    ) = object : PleosSignalBinding {
        override val signal = signal

        override suspend fun check(): Availability {
            val pm = carProperties ?: return Availability.TEMPORARILY_UNAVAILABLE
            return try {
                // 미지원이거나 권한이 없으면 null
                if (pm.getCarPropertyConfig(propertyId) != null) Availability.AVAILABLE else Availability.UNSUPPORTED
            } catch (e: SecurityException) {
                Availability.PERMISSION_DENIED
            } catch (e: Exception) {
                Availability.TEMPORARILY_UNAVAILABLE
            }
        }

        override suspend fun current(): VehicleEvent? = try {
            carProperties?.getProperty<Any>(propertyId, 0)?.let(toEvent)
        } catch (e: Exception) {
            Log.w(TAG, "$signal 조회 실패: ${e.message}"); null
        }

        override fun register(onEvent: (VehicleEvent) -> Unit): Subscription {
            val pm = carProperties ?: return Subscription { }
            val callback = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) { toEvent(value)?.let(onEvent) }
                override fun onErrorEvent(propId: Int, zone: Int) { Log.w(TAG, "$signal 알림 오류") }
            }
            pm.registerCallback(callback, propertyId, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            return Subscription { pm.unregisterCallback(callback, propertyId) }
        }
    }

    private fun gear() = aaosBinding(VehicleSignal.GEAR, VehiclePropertyIds.GEAR_SELECTION) { v ->
        val gear = when (v.value as? Int) {
            VehicleGear.GEAR_PARK -> Gear.PARK
            VehicleGear.GEAR_REVERSE -> Gear.REVERSE
            VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
            VehicleGear.GEAR_DRIVE -> Gear.DRIVE
            else -> Gear.UNKNOWN
        }
        VehicleEvent.GearChanged(gear, now())
    }

    private fun outsideTemperature() =
        aaosBinding(VehicleSignal.OUTSIDE_TEMPERATURE, VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE) { v ->
            (v.value as? Float)?.let { VehicleEvent.OutsideTemperatureChanged(it, now()) }
        }

    // ---------- Pleos Vehicle SDK ----------

    private fun seatOccupancy() = object : PleosSignalBinding {
        override val signal = VehicleSignal.SEAT_OCCUPANCY
        private val seat get() = vehicle.getSeat()
        private var areas: List<SeatArea> = listOf(SeatArea(SeatValue.ROW_1_LEFT))

        private fun interpret(raw: Boolean?) = raw?.let { if (quirks.seatOccupancyInverted) !it else it }

        override suspend fun check() = awaitCallback<Availability> { ok, fail ->
            seat.checkSeatOccupancyCapabilities({ c, list -> if (list.isNotEmpty()) areas = list; ok(c.toAvailability()) }, fail)
        }.orTemporary(signal)

        override suspend fun current(): VehicleEvent? {
            val states = areas.map { area ->
                awaitCallback<Boolean?> { ok, fail -> seat.getCurrentSeatOccupancy(area, ok, fail) }.getOrNull().let(::interpret)
            }
            if (states.all { it == null }) return null
            return VehicleEvent.SeatOccupancyChanged(states.any { it == true }, now())
        }

        override fun register(onEvent: (VehicleEvent) -> Unit): Subscription {
            val state = mutableMapOf<SeatArea, Boolean>()
            val listeners = areas.associateWith { area ->
                object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(occupancy: Boolean?) {
                        interpret(occupancy)?.let { state[area] = it }
                        onEvent(VehicleEvent.SeatOccupancyChanged(state.values.any { it }, now()))
                    }
                    override fun onFailed(e: Exception) { Log.w(TAG, "좌석 점유 알림 실패: ${e.message}") }
                }
            }
            listeners.forEach { (area, l) -> seat.registerSeatOccupancy(area, l) }
            return Subscription { listeners.forEach { (area, l) -> seat.unregisterSeatOccupancy(area, l) } }
        }
    }

    private fun doorOpen() = object : PleosSignalBinding {
        override val signal = VehicleSignal.DOOR_OPEN
        private val door get() = vehicle.getDoor()
        // 승객 문 4개만 본다 (트렁크·후드는 범위가 0..100이라 제외)
        private val areas = listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
            .map { DoorArea(it) }

        override suspend fun check() = awaitCallback<Availability> { ok, fail ->
            door.checkDoorPositionCapabilities({ c, _ -> ok(c.toAvailability()) }, fail)
        }.orTemporary(signal)

        override suspend fun current(): VehicleEvent? {
            val positions = areas.map { area ->
                awaitCallback<Int?> { ok, fail -> door.getCurrentDoorPosition(area, ok, fail) }.getOrNull()
            }
            if (positions.all { it == null }) return null
            return VehicleEvent.DoorChanged(positions.any { (it ?: 0) > 0 }, now())
        }

        override fun register(onEvent: (VehicleEvent) -> Unit): Subscription {
            val state = mutableMapOf<DoorArea, Int>()
            val listeners = areas.associateWith { area ->
                object : DoorPositionListener {
                    override fun onPositionUpdated(pos: Int?) {
                        pos?.let { state[area] = it }
                        onEvent(VehicleEvent.DoorChanged(state.values.any { it > 0 }, now()))
                    }
                    override fun onFailed(e: Exception) { Log.w(TAG, "도어 알림 실패: ${e.message}") }
                }
            }
            listeners.forEach { (area, l) -> door.registerDoorPosition(area, l) }
            return Subscription { listeners.forEach { (area, l) -> door.unregisterDoorPosition(area, l) } }
        }
    }

    private fun batterySoc() = object : PleosSignalBinding {
        override val signal = VehicleSignal.BATTERY_SOC
        private val battery get() = vehicle.getEvBattery()

        override suspend fun check() = awaitCallback<Availability> { ok, fail ->
            battery.checkEvBatteryStateOfChargeCapabilities({ c -> ok(c.toAvailability()) }, fail)
        }.orTemporary(signal)

        override suspend fun current(): VehicleEvent? =
            awaitCallback<Int?> { ok, fail -> battery.getEvBatteryStateOfCharge(ok, fail) }.getOrNull()
                ?.let { VehicleEvent.BatterySocChanged(it.toFloat(), now()) }

        override fun register(onEvent: (VehicleEvent) -> Unit): Subscription {
            val l = object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                    value?.let { onEvent(VehicleEvent.BatterySocChanged(it.toFloat(), now())) }
                }
                override fun onFailed(e: Exception) { Log.w(TAG, "SOC 알림 실패: ${e.message}") }
            }
            battery.registerEvBatteryStateOfCharge(l)
            return Subscription { battery.unregisterEvBatteryStateOfCharge(l) }
        }
    }

    private fun hvacPower() = object : PleosSignalBinding {
        override val signal = VehicleSignal.HVAC_POWER
        private val hvac get() = vehicle.getHVAC()
        // 공조 전원은 좌석 묶음 영역(ROW_1_LEFT+...+ROW_2_RIGHT)으로만 읽힌다. capability가 준 영역을 쓴다.
        private var area: SeatArea? = null

        override suspend fun check() = awaitCallback<Availability> { ok, fail ->
            hvac.checkHvacPowerCapabilities({ c, list -> area = list.firstOrNull(); ok(c.toAvailability()) }, fail)
        }.orTemporary(signal)

        override suspend fun current(): VehicleEvent? {
            val a = area ?: return null
            return awaitCallback<Boolean?> { ok, fail -> hvac.getHvacPowerState(a, ok, fail) }.getOrNull()
                ?.let { VehicleEvent.HvacPowerChanged(it, now()) }
        }

        override fun register(onEvent: (VehicleEvent) -> Unit): Subscription {
            val a = area ?: return Subscription { }
            val l = object : HvacPowerStateListener {
                override fun onHvacPowerStateUpdated(state: Boolean?) {
                    state?.let { onEvent(VehicleEvent.HvacPowerChanged(it, now())) }
                }
                override fun onFailed(e: Exception) { Log.w(TAG, "공조 알림 실패: ${e.message}") }
            }
            hvac.registerHvacPowerState(a, l)
            return Subscription { hvac.unregisterHvacPowerState(a, l) }
        }
    }

    private fun steeringAngle() = object : PleosSignalBinding {
        override val signal = VehicleSignal.STEERING_ANGLE
        private val wheel get() = vehicle.getSteeringWheel()

        override suspend fun check() = awaitCallback<Availability> { ok, fail ->
            wheel.checkSteeringWheelAngleCapabilities({ c, _ -> ok(c.toAvailability()) }, fail)
        }.orTemporary(signal)

        override suspend fun current(): VehicleEvent? =
            awaitCallback<Float?> { ok, fail -> wheel.getCurrentSteeringWheelAngle(ok, fail) }.getOrNull()
                ?.let { VehicleEvent.SteeringAngleChanged(it, now()) }

        override fun register(onEvent: (VehicleEvent) -> Unit): Subscription {
            val l = object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(angle: Float?) {
                    angle?.let { onEvent(VehicleEvent.SteeringAngleChanged(it, now())) }
                }
                override fun onFailed(e: Exception) { Log.w(TAG, "조향각 알림 실패: ${e.message}") }
            }
            wheel.registerSteeringWheelAngle(l)
            return Subscription { wheel.unregisterSteeringWheelAngle(l) }
        }
    }
}
