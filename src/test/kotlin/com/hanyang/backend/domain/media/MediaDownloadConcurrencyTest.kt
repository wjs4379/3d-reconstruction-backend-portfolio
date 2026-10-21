package com.hanyang.backend.domain.media

import com.hanyang.backend.common.ApiException
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.mock.web.MockHttpServletResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MediaDownloadConcurrencyTest {
    @Test fun `slow HTTP response retains its slot until all bytes are sent`() {
        val service=mock(MediaService::class.java)
        `when`(service.publicRead(1,1)).thenReturn(MediaRead("image/png",byteArrayOf(1,2,3)))
        val guard=MediaConcurrencyGuard(); val controller=MediaController(service,guard)
        val started=CountDownLatch(2); val release=CountDownLatch(1); val pool=Executors.newFixedThreadPool(2)
        class SlowResponse:MockHttpServletResponse() {
            override fun getOutputStream():ServletOutputStream = object:ServletOutputStream() {
                override fun isReady()=true
                override fun setWriteListener(listener:WriteListener) {}
                override fun write(value:Int) {}
                override fun write(bytes:ByteArray) { started.countDown(); check(release.await(5,TimeUnit.SECONDS)) }
            }
        }
        try {
            val jobs=(1..2).map { pool.submit { controller.publicRead(1,1,SlowResponse()) } }
            assertTrue(started.await(5,TimeUnit.SECONDS))
            assertEquals("MEDIA_BUSY",assertThrows(ApiException::class.java) { controller.publicRead(1,1,MockHttpServletResponse()) }.code)
            release.countDown(); jobs.forEach { it.get(5,TimeUnit.SECONDS) }
            val response=MockHttpServletResponse();controller.publicRead(1,1,response)
            assertArrayEquals(byteArrayOf(1,2,3),response.contentAsByteArray)
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
