package ru.itmo.clothesadvisor.model.ai

enum class AiOutfitFailure(val status: Int) {
    INVALID_REQUEST(400),
    SOURCE_CHANGED(409),
    NO_OUTFIT(422),
    INVALID_RESPONSE(502),
    UNAVAILABLE(503),
    TIMEOUT(504),
}

class AiOutfitException(val failure: AiOutfitFailure) : RuntimeException() {
    val status: Int
        get() = failure.status
}
