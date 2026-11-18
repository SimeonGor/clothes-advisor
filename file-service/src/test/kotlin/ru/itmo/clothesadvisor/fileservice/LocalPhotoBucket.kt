package ru.itmo.clothesadvisor.fileservice

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal class LocalPhotoBucket : AutoCloseable {
    val objects = ConcurrentHashMap<String, ByteArray>()
    val calls = AtomicInteger()
    @Volatile var beforeReply: (String) -> Unit = {}
    @Volatile var unavailable = false
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@LocalPhotoBucket.executor
        createContext("/test-bucket/", ::handle)
        start()
    }
    val endpoint = "http://127.0.0.1:${server.address.port}"

    private fun handle(exchange: HttpExchange) = exchange.use {
        calls.incrementAndGet()
        val key = exchange.requestURI.path.removePrefix("/test-bucket/")
        beforeReply(exchange.requestMethod)
        when {
            unavailable -> exchange.sendResponseHeaders(503, -1)
            exchange.requestMethod == "PUT" -> {
                val previous = objects.putIfAbsent(key, exchange.requestBody.readAllBytes())
                exchange.sendResponseHeaders(if (previous == null) 200 else 412, -1)
            }
            else -> {
                val bytes = objects[key]
                if (bytes == null) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                }
            }
        }
    }

    fun reset() {
        calls.set(0)
        objects.clear()
        unavailable = false
        beforeReply = {}
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
