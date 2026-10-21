package com.hanyang.backend.domain.media

import com.hanyang.backend.common.ApiException
import org.springframework.http.HttpStatus
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.*
import java.net.URI
import java.time.Duration

class S3MediaStorage(private val bucket: String, region: String) : MediaStorage {
    private val client = S3Client.builder().region(Region.of(region))
        .overrideConfiguration { it.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(15)) }.build()
    private val signer = S3Presigner.builder().region(Region.of(region)).build()
    init { require(bucket.isNotBlank()) { "MEDIA_S3_BUCKET is required for s3 storage" } }
    // The authenticated API bounds actual bytes before uploading to S3. Browsers never receive a staging write capability.
    override fun putStaging(key: String, bytes: ByteArray, contentType: String) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
            .contentLength(bytes.size.toLong()).build(), RequestBody.fromBytes(bytes))
    }
    override fun read(key: String, version: String?, limit: Long): ByteArray {
        val request = GetObjectRequest.builder().bucket(bucket).key(key)
        if (version != null) request.versionId(version)
        return client.getObject(request.build()).use { readLimited(it,limit) }
    }
    override fun putFinal(key: String, bytes: ByteArray, contentType: String): StoredObject {
        val result = client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
            .ifNoneMatch("*").build(), RequestBody.fromBytes(bytes))
        return StoredObject(key,result.versionId())
    }
    override fun delete(key: String) { client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()) }
    override fun signedRead(key: String, version: String?): String {
        val get = GetObjectRequest.builder().bucket(bucket).key(key)
        if (version != null) get.versionId(version)
        return signer.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(3))
            .getObjectRequest(get.build()).build()).url().toString()
    }
    override fun importModel(url: String, limit: Long): ByteArray {
        val uri = runCatching { URI(url) }.getOrNull()
            ?: throw ApiException(HttpStatus.BAD_REQUEST,"INVALID_MODEL_SOURCE","모델 주소를 확인해 주세요.")
        val host = uri.host ?: ""
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1,443) ||
            !(host == "$bucket.s3.amazonaws.com" || (host.startsWith("$bucket.s3.") && host.endsWith(".amazonaws.com"))))
            throw ApiException(HttpStatus.BAD_REQUEST,"INVALID_MODEL_SOURCE","설정된 저장소의 모델만 이관할 수 있습니다.")
        val key = uri.path.removePrefix("/")
        if (key.isBlank() || !key.startsWith("final")) throw ApiException(HttpStatus.BAD_REQUEST,"INVALID_MODEL_SOURCE","완료 모델 경로를 확인해 주세요.")
        return read(key,null,limit)
    }
}
