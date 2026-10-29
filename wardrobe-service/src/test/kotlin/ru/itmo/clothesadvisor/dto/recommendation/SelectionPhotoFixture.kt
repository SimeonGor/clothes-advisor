package ru.itmo.clothesadvisor.dto.recommendation

import java.util.Base64
import org.junit.jupiter.api.AfterEach

open class SelectionPhotoFixture {
    protected val selectionPhotos = SelectionPhotos()
    protected fun image(value: String = "AQID") = selectionPhotos.add(Base64.getDecoder().decode(value))

    @AfterEach
    fun closeSelectionPhotos() = selectionPhotos.close()
}
