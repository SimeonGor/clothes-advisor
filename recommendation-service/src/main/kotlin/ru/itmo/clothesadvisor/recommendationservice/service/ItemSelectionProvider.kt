package ru.itmo.clothesadvisor.recommendationservice.service

import ru.itmo.clothesadvisor.dto.ai.ItemSelectionRequest
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionResponse
import ru.itmo.clothesadvisor.recommendationservice.model.ai.SelectionGoal

fun interface ItemSelectionProvider {
    fun select(goal: SelectionGoal, selection: ItemSelectionRequest): ItemSelectionResponse
}
