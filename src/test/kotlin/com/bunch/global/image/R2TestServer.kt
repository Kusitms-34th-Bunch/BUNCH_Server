package com.bunch.global.image

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections

/** A local S3 wire stub. It intentionally never records Authorization or query strings. */
class R2TestServer : AutoCloseable {
    data class Request(val method: String, val path: String, val body: ByteArray, val cacheControl: String?)
    val requests = Collections.synchronizedList(mutableListOf<Request>())
    var onPut: () -> Unit = {}
    var failPuts = false
    var failThumbnail = false
    var failDeletes = false
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val request = Request(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestBody.readBytes(),
                exchange.requestHeaders.getFirst("Cache-Control")
            )
            requests.add(request)
            if (request.method == "PUT") onPut()
            val fail = (request.method == "PUT" && (failPuts || (failThumbnail && request.path.endsWith("thumbnail.jpg")))) ||
                (request.method == "DELETE" && failDeletes)
            if (fail) {
                val body = "<Error><Code>AccessDenied</Code></Error>".toByteArray()
                exchange.sendResponseHeaders(403, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                exchange.responseHeaders.add("ETag", "\"test\"")
                exchange.sendResponseHeaders(if (request.method == "DELETE") 204 else 200, -1)
            }
            exchange.close()
        }
        start()
    }
    val endpoint = "http://127.0.0.1:${server.address.port}"
    val storage = R2ObjectStorage(endpoint, "bunch-test", "test-key", "test-secret")

    override fun close() {
        storage.close()
        server.stop(0)
    }
}
