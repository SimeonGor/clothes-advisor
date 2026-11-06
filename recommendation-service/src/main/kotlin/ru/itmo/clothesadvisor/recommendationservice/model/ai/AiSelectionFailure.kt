package ru.itmo.clothesadvisor.recommendationservice.model.ai

enum class AiSelectionFailure {
    INVALID_REQUEST,
    NO_SELECTION,
    NO_OUTFIT,
    NO_CAPSULE,
    INVALID_RESPONSE,
    UNAVAILABLE,
    TIMEOUT,
}

class AiSelectionException(val failure: AiSelectionFailure) : RuntimeException()

enum class SelectionGoal { OUTFIT, CAPSULE }
