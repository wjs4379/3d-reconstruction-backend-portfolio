package com.hanyang.backend.domain.media

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class UploadSessionRequest(
    @param:JsonProperty(required = true) @param:JsonSetter(nulls = Nulls.FAIL) @field:Positive val unitId: Long,
    val kind: MediaKind,
    val contentType: String,
    @param:JsonProperty(required = true) @param:JsonSetter(nulls = Nulls.FAIL) @field:Positive val sizeBytes: Long,
    @field:Positive val spaceId: Long? = null,
    @field:Positive val modelId: Long? = null,
    @field:Size(max = 20) val sourceImageAssetIds: List<Long> = emptyList()
)
data class UploadSessionView(val id: String, val uploadUrl: String, val method: String = "PUT", val headers: Map<String,String>, val expiresAt: LocalDateTime)
data class MediaAssetView(val id: Long, val unitId: Long, val kind: MediaKind, val status: MediaStatus, val sizeBytes: Long, val contentType: String, val sourceModelId: Long?, val spaceId: Long? = null) {
    companion object { fun from(a: MediaAsset, spaceId: Long? = null) = MediaAssetView(requireNotNull(a.id),a.unitId,a.kind,a.status,a.sizeBytes,a.contentType,a.sourceModelId,spaceId) }
}
data class MediaImageMetadataView(val assetId: Long, val pixelWidth: Int, val pixelHeight: Int)
data class ImportModelRequest(@param:JsonProperty(required = true) @param:JsonSetter(nulls = Nulls.FAIL) @field:Positive val unitId: Long)
data class MediaRead(val contentType: String, val bytes: ByteArray? = null, val redirect: String? = null)
