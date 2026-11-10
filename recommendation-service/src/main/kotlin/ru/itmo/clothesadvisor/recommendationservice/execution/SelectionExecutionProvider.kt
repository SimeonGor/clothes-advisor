package ru.itmo.clothesadvisor.recommendationservice.execution

import ru.itmo.clothesadvisor.dto.ai.ItemSelectionRequest
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionResponse
import ru.itmo.clothesadvisor.recommendationservice.model.ai.SelectionGoal

internal interface SelectionExecutionProvider {
    fun instructions(goal: SelectionGoal): SelectionInstructions

    /** Calls beforeSend after preparing the request, immediately before committing to the external call. */
    fun select(
        goal: SelectionGoal,
        selection: ItemSelectionRequest,
        instructions: SelectionInstructions,
        beforeSend: () -> Unit,
    ): ItemSelectionResponse
}

data class SelectionInstructions(val model: String, val promptVersion: Long, val promptText: String)
