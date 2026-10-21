package com.hanyang.backend.domain.media

import com.hanyang.backend.common.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

data class VerifiedImageDimensions(val width: Int, val height: Int)

@Component
class MediaValidation(private val mapper: JsonMapper) {
    fun validate(kind: MediaKind, contentType: String, bytes: ByteArray): VerifiedImageDimensions? =
        if (kind == MediaKind.IMAGE) image(contentType, bytes) else { model(bytes); null }
    private fun invalid(): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE", "파일 내용과 형식을 확인해 주세요.")
    private fun image(type: String, bytes: ByteArray): VerifiedImageDimensions {
        val png = bytes.size >= 8 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))
        val jpeg = bytes.size >= 3 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte()
        if ((type == "image/png" && !png) || (type == "image/jpeg" && !jpeg) || type !in setOf("image/png","image/jpeg")) invalid()
        return try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) invalid()
                val reader = readers.next()
                try {
                    reader.input = input
                    val w = reader.getWidth(0); val h = reader.getHeight(0)
                    if (w < 1 || h < 1 || w > 8192 || h > 8192 || w.toLong()*h > 12_000_000) invalid()
                    if (reader.read(0) == null) invalid()
                    // Browsers and OpenCV display EXIF-oriented JPEGs; ImageIO exposes raw dimensions.
                    // Keep the captured bytes unchanged and record their displayed/AI pixel extent.
                    if (type == "image/jpeg" && jpegOrientation(bytes) in 5..8)
                        VerifiedImageDimensions(h, w)
                    else VerifiedImageDimensions(w, h)
                } finally { reader.dispose() }
            }
        } catch (e: ApiException) { throw e } catch (_: Exception) { invalid() }
    }
    /** Read only bounded APP1/TIFF fields; no second image decode or external metadata I/O. */
    private fun jpegOrientation(bytes: ByteArray): Int {
        fun u8(offset: Int) = bytes[offset].toInt() and 255
        fun be16(offset: Int) = (u8(offset) shl 8) or u8(offset + 1)
        var offset = 2 // SOI already checked by the image validator.
        while (offset + 4 <= bytes.size) {
            if (u8(offset) != 255) return 1
            while (offset < bytes.size && u8(offset) == 255) offset++
            if (offset >= bytes.size) return 1
            val marker = u8(offset++)
            if (marker == 0xda || marker == 0xd9) return 1 // No marker scan inside compressed pixels.
            if (marker == 0x01 || marker in 0xd0..0xd8) continue
            if (offset + 2 > bytes.size) return 1
            val length = be16(offset)
            if (length < 2 || length > bytes.size - offset) return 1
            val payload = offset + 2
            val end = offset + length
            if (marker == 0xe1 && end - payload >= 14 &&
                bytes.copyOfRange(payload, payload + 6).contentEquals(byteArrayOf(69, 120, 105, 102, 0, 0))) {
                val tiff = payload + 6
                val little = u8(tiff) == 73 && u8(tiff + 1) == 73
                val big = u8(tiff) == 77 && u8(tiff + 1) == 77
                if (!little && !big) return 1
                fun short(at: Int): Int = if (little) u8(at) or (u8(at + 1) shl 8) else be16(at)
                fun integer(at: Int): Long = if (little) (0..3).fold(0L) { value, i -> value or (u8(at + i).toLong() shl (i * 8)) }
                    else (0..3).fold(0L) { value, i -> (value shl 8) or u8(at + i).toLong() }
                if (short(tiff + 2) != 42) return 1
                val first = integer(tiff + 4)
                if (first < 8 || first > end - tiff - 2) return 1
                val directory = tiff + first.toInt()
                val entries = short(directory)
                if (entries > (end - directory - 2) / 12) return 1
                for (index in 0 until entries) {
                    val entry = directory + 2 + index * 12
                    if (short(entry) == 0x0112 && short(entry + 2) == 3 && integer(entry + 4) == 1L)
                        return short(entry + 8).takeIf { it in 1..8 } ?: 1
                }
            }
            offset = end
        }
        return 1
    }
    private fun model(bytes: ByteArray) = GlbValidation(mapper).validate(bytes)
}
