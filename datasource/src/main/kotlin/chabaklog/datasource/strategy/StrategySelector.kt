package chabaklog.datasource.strategy

import chabaklog.datasource.core.SourceId
import chabaklog.datasource.vehicle.VehicleGateway
import chabaklog.datasource.vehicle.VehicleSignal
import chabaklog.datasource.vehicle.supportsAll

/**
 * 차박 감지·주차 난이도처럼 "값 하나"가 아니라 "이벤트를 해석하는 방법"을 고르는 경우.
 * 각 전략은 필요한 신호를 선언하고, 선택기는 필요한 신호가 모두 지원되는 첫 전략을 고른다.
 */
interface SignalStrategy {
    val id: SourceId
    val priority: Int
    val requiredSignals: Set<VehicleSignal>
}

class StrategySelector<S : SignalStrategy>(
    val category: String,
    strategies: List<S>,
) {
    private val ordered = strategies.sortedBy { it.priority }

    init {
        require(ordered.isNotEmpty()) { "$category: 전략이 하나 이상 필요합니다" }
        require(ordered.last().requiredSignals.isEmpty()) {
            "$category: 마지막 전략은 신호 없이 동작해야 합니다(항상 성공하는 대체)"
        }
    }

    suspend fun select(vehicle: VehicleGateway): S =
        ordered.first { vehicle.supportsAll(it.requiredSignals) }
}
