package com.hanyang.backend.domain.media

import com.hanyang.backend.common.ApiException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MediaConcurrencyGuardTest {
    @Test fun `two ongoing operations reject additional buffers and release slots on completion`() {
        val guard=MediaConcurrencyGuard(); val pool=Executors.newFixedThreadPool(2)
        val started=CountDownLatch(2); val release=CountDownLatch(1)
        try {
            val jobs=(1..2).map { pool.submit<Int> { guard.run { started.countDown(); check(release.await(5,TimeUnit.SECONDS)); 7 } } }
            assertTrue(started.await(5,TimeUnit.SECONDS))
            assertEquals("MEDIA_BUSY",assertThrows(ApiException::class.java) { guard.run { 0 } }.code)
            release.countDown(); jobs.forEach { assertEquals(7,it.get(5,TimeUnit.SECONDS)) }
            assertEquals(9,guard.run { 9 })
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun `storage failures do not leak processing slots`() {
        val guard=MediaConcurrencyGuard()
        repeat(3) { assertThrows(IllegalStateException::class.java) { guard.run { error("storage failed") } } }
        assertTrue(guard.run { true })
    }
}
