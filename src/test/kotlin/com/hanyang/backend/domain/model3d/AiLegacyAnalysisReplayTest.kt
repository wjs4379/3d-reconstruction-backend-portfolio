package com.hanyang.backend.domain.model3d

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiLegacyAnalysisReplayTest {
    @Test fun `only new four-photo sets use intake while old Outbox inputs use existing-job replay`() {
        val paths = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/api/v1/rooms") { exchange ->
            paths.add(exchange.requestURI.path)
            exchange.requestBody.use { it.readAllBytes() }
            exchange.sendResponseHeaders(202,-1)
            exchange.close()
        }
        server.start()
        try {
            val client=AiServerClient("http://127.0.0.1:${server.address.port}","test-only-service-key")
            val source=(1..4).map { "http://127.0.0.1:8080/api/internal/ai/assets/$it/content" }
            for (images in listOf(source,source.take(2),List(4){source.first()})) {
                client.analyze(AiAnalyzeRequestDto(images,UUID.randomUUID().toString(),"legacy-safe",UUID.randomUUID().toString(),"http://127.0.0.1:8080/api/internal/ai/webhook"),"test-command")
                    .block(Duration.ofSeconds(5))
            }
            assertEquals(listOf("/api/v1/rooms/analyze","/api/v1/rooms/analyze/replay","/api/v1/rooms/analyze/replay"),paths)
        } finally { server.stop(0) }
    }
}
