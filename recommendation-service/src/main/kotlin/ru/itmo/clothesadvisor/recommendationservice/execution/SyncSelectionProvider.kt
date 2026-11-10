package ru.itmo.clothesadvisor.recommendationservice.execution

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionException
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionRequest
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionResponse
import ru.itmo.clothesadvisor.recommendationservice.model.ai.AiSelectionException
import ru.itmo.clothesadvisor.recommendationservice.model.ai.AiSelectionFailure
import ru.itmo.clothesadvisor.recommendationservice.model.ai.SelectionGoal
import ru.itmo.clothesadvisor.recommendationservice.service.ItemSelectionProvider

@Component("syncSelectionProvider")
@ConditionalOnProperty("spring.r2dbc.url")
internal class SyncSelectionProvider(
    private val store: ExecutionStore,
    private val client: SelectionExecutionProvider,
) : ItemSelectionProvider {
    override fun select(goal: SelectionGoal, selection: ItemSelectionRequest): ItemSelectionResponse {
        try {
            val execution = store.claimSync() ?: unavailable()
            val response = try {
                client.select(goal, selection, client.instructions(goal)) {
                    if (!store.startSync(execution)) unavailable()
                }
            } catch (error: AiSelectionException) {
                store.finish(execution, if (error.outcomeUnknown) "UNKNOWN" else "FAILED")
                throw error
            }
            if (!store.finish(execution, "SUCCEEDED")) unavailable()
            return response
        } catch (_: DataAccessException) {
            unavailable()
        } catch (_: TransactionException) {
            unavailable()
        }
    }

    private fun unavailable(): Nothing = throw AiSelectionException(AiSelectionFailure.UNAVAILABLE)
}
