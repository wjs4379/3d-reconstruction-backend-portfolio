package com.hanyang.backend.domain.media

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

enum class MediaKind { IMAGE, MODEL }
enum class MediaStatus { PENDING, VERIFIED }

@Entity
@Table(name = "media_assets")
class MediaAsset(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "asset_id") val id: Long? = null,
    @Column(nullable = false) val unitId: Long,
    @Column(nullable = false) val ownerUserId: Long,
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 20) val kind: MediaKind,
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 20) val status: MediaStatus = MediaStatus.VERIFIED,
    @Column(nullable = false, length = 500) val objectKey: String,
    @Column(length = 255) val objectVersion: String? = null,
    @Column(nullable = false) val sizeBytes: Long,
    @Column(nullable = false, length = 100) val contentType: String,
    @Column(length = 64) val checksum: String? = null,
    val sourceModelId: Long? = null,
    @Column(name = "pixel_width") var pixelWidth: Int? = null,
    @Column(name = "pixel_height") var pixelHeight: Int? = null,
    @Column(nullable = false) val createdAt: LocalDateTime = LocalDateTime.now()
)

@Entity
@Table(name = "upload_sessions")
class UploadSession(
    @Id @Column(length = 36) val id: String = UUID.randomUUID().toString(),
    @Column(nullable = false) val unitId: Long,
    @Column(nullable = false) val ownerUserId: Long,
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 20) val kind: MediaKind,
    @Column(nullable = false, length = 500) val stagingKey: String,
    @Column(nullable = false, length = 100) val contentType: String,
    @Column(nullable = false) val sizeBytes: Long,
    @Column(nullable = false) val expiresAt: LocalDateTime,
    var assetId: Long? = null,
    @Column(name = "space_id") val spaceId: Long? = null,
    @Column(name = "model_id") val modelId: Long? = null,
    @Column(name = "source_image_asset_ids", columnDefinition = "LONGTEXT") val sourceImageAssetIds: String? = null,
    @Version var version: Long = 0,
    @Column(nullable = false) val createdAt: LocalDateTime = LocalDateTime.now()
)
