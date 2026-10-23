package ru.itmo.clothesadvisor.service

import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

internal fun <T : Any> blockingCall(operation: () -> T): Mono<T> =
    Mono.fromCallable { operation() }.subscribeOn(Schedulers.boundedElastic())

internal fun blockingAction(operation: () -> Unit): Mono<Void> =
    Mono.fromRunnable<Void> { operation() }.subscribeOn(Schedulers.boundedElastic())
