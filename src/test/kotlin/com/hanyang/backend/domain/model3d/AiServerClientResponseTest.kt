package com.hanyang.backend.domain.model3d

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBufferLimitException

class AiServerClientResponseTest {
    private fun withResponse(body: ByteArray, test: (AiServerClient) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/rooms") { exchange ->
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            try { exchange.responseBody.use { it.write(body) } } catch (_: java.io.IOException) { }
        }
        server.start()
        try { test(AiServerClient("http://127.0.0.1:${server.address.port}", "test-only-service-key")) }
        finally { server.stop(0) }
    }

    @Test fun `valid multi-view coordinates above default 256 KiB buffer are received`() {
        val vertices = (1..2500).joinToString(",") {
            """{"id":"${UUID(0,it.toLong())}","x":123.45,"y":456.78,"z":789.12,"imageIndex":${it % 4},"sourceVertexId":null}"""
        }
        val id = UUID(0, 9999).toString()
        val body = """{"id":"$id","status":"PENDING_CORRECTION","aiVertices":[$vertices],"aiEdges":[]}""".toByteArray()
        assertTrue(body.size > 262144)
        withResponse(body) { client ->
            val result = client.getRoom(id)
            assertEquals(2500, result.aiVertices?.size)
            assertEquals(setOf(0,1,2,3), result.aiVertices?.map { it.imageIndex }?.toSet())
        }
    }

    @Test fun `AI response retains a finite upper byte bound`() {
        val id = UUID(0,9999).toString()
        val body = """{"id":"$id","roomName":"${"a".repeat(AiPayloadLimits.MAX_RESULT_BYTES)}"}""".toByteArray()
        withResponse(body) { client ->
            val error = assertThrows(Exception::class.java) { client.getRoom(id) }
            var cause: Throwable = error
            while (cause.cause != null) cause = requireNotNull(cause.cause)
            assertInstanceOf(DataBufferLimitException::class.java, cause)
        }
    }
}
