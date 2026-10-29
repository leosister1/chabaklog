package chabaklog.datasource

import chabaklog.datasource.core.Availability
import chabaklog.datasource.vehicle.Subscription
import chabaklog.datasource.vehicle.VehicleEvent
import chabaklog.datasource.vehicle.VehicleGateway
import chabaklog.datasource.vehicle.VehicleSignal

/** 테스트·데모용 가짜 차량. 신호별 지원 여부와 현재값을 직접 정한다. */
class FakeVehicleGateway(
    private val support: Map<VehicleSignal, Availability> = emptyMap(),
) : VehicleGateway {
    val current = mutableMapOf<VehicleSignal, VehicleEvent>()

    override suspend fun availability(signal: VehicleSignal) =
        support[signal] ?: Availability.UNSUPPORTED

    override suspend fun readCurrent(signal: VehicleSignal) = current[signal]

    override fun subscribe(signal: VehicleSignal, listener: (VehicleEvent) -> Unit) = Subscription { }

    companion object {
        fun supporting(vararg signals: VehicleSignal) =
            FakeVehicleGateway(signals.associateWith { Availability.AVAILABLE })
    }
}
