package com.hanyang.backend.domain.furniture

import com.hanyang.backend.domain.media.MediaConcurrencyGuard
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v2/furniture")
class FurnitureController(private val catalog: FurnitureCatalog, private val guard: MediaConcurrencyGuard) {
    @GetMapping
    fun list() = guard.run { catalog.list() }

    @RequestMapping("/{type}/content", method = [RequestMethod.GET, RequestMethod.HEAD])
    fun content(@PathVariable type: String, request: HttpServletRequest, response: HttpServletResponse) {
        // Keep the shared media permit until the final body write, including a slow reader.
        guard.run {
            val model = catalog.read(type)
            val etag = "\"${model.sha256}\""
            response.contentType = "model/gltf-binary"
            response.setHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
            response.setHeader(HttpHeaders.ETAG, etag)
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes")
            response.setHeader("X-Content-Type-Options", "nosniff")
            val condition = request.getHeader(HttpHeaders.IF_NONE_MATCH)
            if (condition != null && condition.length <= 4096 && condition.split(',').any {
                    val value = it.trim().removePrefix("W/")
                    value == etag || value == "*"
                }) {
                response.status = HttpServletResponse.SC_NOT_MODIFIED
                return@run
            }
            val rangeHeader = request.getHeader(HttpHeaders.RANGE)
            val ifRange = request.getHeader(HttpHeaders.IF_RANGE)
            val useRange = request.method == "GET" && rangeHeader != null && (ifRange == null || ifRange == etag)
            val range = if (useRange) singleByteRange(requireNotNull(rangeHeader), model.bytes.size) else null
            if (useRange && range == null) {
                response.status = HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */${model.bytes.size}")
                response.setContentLength(0)
                return@run
            }
            val start = range?.first ?: 0
            val end = range?.last ?: model.bytes.lastIndex
            val length = end - start + 1
            if (range != null) {
                response.status = HttpServletResponse.SC_PARTIAL_CONTENT
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes $start-$end/${model.bytes.size}")
            }
            response.setContentLength(length)
            if (request.method != "HEAD") {
                response.outputStream.write(model.bytes, start, length)
                response.outputStream.flush()
            }
        }
    }
}

/** One bounded byte range; invalid/multiple ranges are never expanded into allocations. */
internal fun singleByteRange(value: String, length: Int): IntRange? {
    if (value.length > 128 || length <= 0) return null
    val match = Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(value) ?: return null
    val first = match.groupValues[1]
    val last = match.groupValues[2]
    if (first.isEmpty()) {
        val suffix = last.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return (length - minOf(suffix, length.toLong()).toInt())..(length - 1)
    }
    val start = first.toLongOrNull()?.takeIf { it in 0 until length.toLong() } ?: return null
    val end = if (last.isEmpty()) length.toLong() - 1 else last.toLongOrNull() ?: return null
    if (end < start) return null
    return start.toInt()..minOf(end, length.toLong() - 1).toInt()
}
