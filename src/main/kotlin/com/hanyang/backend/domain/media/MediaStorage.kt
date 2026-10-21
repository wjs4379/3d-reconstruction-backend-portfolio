package com.hanyang.backend.domain.media

import com.hanyang.backend.common.ApiException
import org.springframework.http.HttpStatus
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class StoredObject(val key: String, val version: String? = null)
interface MediaStorage {
    fun putStaging(key: String, bytes: ByteArray, contentType: String)
    fun read(key: String, version: String? = null, limit: Long): ByteArray
    fun putFinal(key: String, bytes: ByteArray, contentType: String): StoredObject
    fun delete(key: String)
    fun signedRead(key: String, version: String?): String? = null
    fun importModel(url: String, limit: Long): ByteArray =
        throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_IMPORT_UNAVAILABLE", "기존 S3 모델 이관에는 S3 저장소 설정이 필요합니다.")
}

fun readLimited(input: InputStream, limit: Long): ByteArray {
    if (limit <= 0 || limit > 26_214_400) throw ApiException(HttpStatus.BAD_REQUEST,"INVALID_FILE_SIZE","파일 크기를 확인해 주세요.")
    val bytes = input.readNBytes((limit + 1).toInt())
    if (bytes.size > limit) throw ApiException(HttpStatus.BAD_REQUEST,"FILE_TOO_LARGE","허용된 파일 크기를 초과했습니다.")
    return bytes
}

class LocalMediaStorage(directory: Path) : MediaStorage {
    private val root = directory.toAbsolutePath().normalize().also { Files.createDirectories(it) }
    private fun path(key: String): Path {
        val p = root.resolve(key).normalize()
        if (!p.startsWith(root) || key.startsWith("/")) throw ApiException(HttpStatus.BAD_REQUEST,"INVALID_STORAGE_KEY","잘못된 파일 경로입니다.")
        return p
    }
    override fun putStaging(key: String, bytes: ByteArray, contentType: String) {
        val p = path(key)
        Files.createDirectories(p.parent)
        val temp = Files.createTempFile(p.parent, ".upload-", ".tmp")
        try {
            Files.write(temp, bytes)
            Files.move(temp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
    override fun read(key: String, version: String?, limit: Long): ByteArray {
        val p = path(key)
        if (!Files.isRegularFile(p)) throw ApiException(HttpStatus.NOT_FOUND,"FILE_NOT_FOUND","파일을 찾을 수 없습니다.")
        return Files.newInputStream(p).use { readLimited(it, limit) }
    }
    override fun putFinal(key: String, bytes: ByteArray, contentType: String): StoredObject {
        val p = path(key)
        Files.createDirectories(p.parent)
        Files.write(p, bytes, java.nio.file.StandardOpenOption.CREATE_NEW)
        return StoredObject(key)
    }
    override fun delete(key: String) { Files.deleteIfExists(path(key)) }
}
