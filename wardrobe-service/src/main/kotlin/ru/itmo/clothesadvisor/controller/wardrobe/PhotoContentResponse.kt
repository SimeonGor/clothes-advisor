package ru.itmo.clothesadvisor.controller.wardrobe

import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoContent

internal fun WardrobeItemPhotoContent.toPhotoContentResponse(): ResponseEntity<ByteArray> =
    ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(contentType))
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .body(bytes)
