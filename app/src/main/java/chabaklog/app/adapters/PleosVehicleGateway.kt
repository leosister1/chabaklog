// Pleos/AAOS 구현은 PleosBindings.kt (1단계 결과표로 채움).
//
// 차량 접근을 VehicleGateway 하나로 감싼다.
package chabaklog.app.adapters

import chabaklog.datasource.core.Availability
import chabaklog.datasource.vehicle.Subscription
import chabaklog.datasource.vehicle.VehicleEvent
import chabaklog.datasource.vehicle.VehicleGateway
import chabaklog.datasource.vehicle.VehicleSignal

/**
 * 신호 하나를 Pleos typed API 네 개(지원 확인·조회·등록·해제)에 연결하는 단위.
 * Door Position 기준 예: checkDoorPositionCapabilities / getCurrentDoorPosition /
 * registerDoorPosition / unregisterDoorPosition.
 */
interface PleosSignalBinding {
    val signal: VehicleSignal
    /** 지원 확인 결과를 Availability로. 실패(예외·오류 콜백)는 UNSUPPORTED로 단정하지 말고 TEMPORARILY_UNAVAILABLE */
    suspend fun check(): Availability
    /** 현재값 조회. null/unavailable 응답이면 null */
    suspend fun current(): VehicleEvent?
    /** 등록. 반환한 Subscription.cancel()은 등록 때와 같은 Listener 객체·Area로 해제해야 한다 */
    fun register(onEvent: (VehicleEvent) -> Unit): Subscription
}

class PleosVehicleGateway(
    bindings: List<PleosSignalBinding>,
) : VehicleGateway {
    private val bySignal = bindings.associateBy { it.signal }
    private val active = mutableListOf<Subscription>()

    override suspend fun availability(signal: VehicleSignal): Availability =
        bySignal[signal]?.check() ?: Availability.UNSUPPORTED

    override suspend fun readCurrent(signal: VehicleSignal): VehicleEvent? =
        bySignal[signal]?.current()

    override fun subscribe(signal: VehicleSignal, listener: (VehicleEvent) -> Unit): Subscription {
        val binding = bySignal[signal] ?: return Subscription { }
        val sub = binding.register(listener)
        active += sub
        return Subscription { sub.cancel(); active -= sub }
    }

    /** 화면·서비스 종료 시 호출. 이후 Vehicle.release()로 SDK 리소스를 정리한다. */
    fun releaseAll() {
        active.toList().forEach { it.cancel() }
        active.clear()
    }
}
