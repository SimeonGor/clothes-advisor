package ru.itmo.clothesadvisor.client.file

import java.util.UUID
import org.mockito.ArgumentMatchers.any
import org.mockito.invocation.InvocationOnMock
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.service.wardrobe.FileCreated

internal fun anyFileId(): UUID = any(UUID::class.java) ?: UUID(0, 0)

internal fun confirmedUpload(call: InvocationOnMock): Mono<FileCreated> = Mono.just(
    FileCreated(call.getArgument(0), call.getArgument(1), call.getArgument<ByteArray>(2).size.toLong()),
)
