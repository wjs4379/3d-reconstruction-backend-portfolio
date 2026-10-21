package com.hanyang.backend.domain.media

import com.hanyang.backend.common.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Bound buffered uploads, decoding and downloads together within this application process. */
@Component
class MediaConcurrencyGuard {
    private val permits = Semaphore(2, true)
    fun <T> run(work: () -> T): T {
        val acquired = try { permits.tryAcquire(1, TimeUnit.SECONDS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_BUSY", "파일 처리 요청이 많습니다. 잠시 후 다시 시도해 주세요.")
        try { return work() } finally { permits.release() }
    }
}
