package com.hanyang.backend.domain.model3d

import com.hanyang.backend.domain.room.Room
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

enum class ModelSourceType { LEGACY_AI, AI_PIPELINE, MANUAL_UPLOAD }

// 상태 관리를 위한 Enum 추가
enum class ModelStatus {
    ANALYZING,               // Phase 1: 이미지 분석 중
    WAITING_FOR_CORRECTION,  // 분석 완료, 프론트의 보정(Correction)을 기다리는 중 🌟
    RECONSTRUCTING,          // Phase 2: 보정 완료 후 3D 재구성 중
    WAITING_FOR_MODEL_UPLOAD, // 최종 좌표 생성 완료, 프론트의 모델 업로드를 기다리는 중
    SUCCESS,                 // 프론트가 생성한 최종 모델 URL 등록 완료
    FAILED                   // 실패
}
@Entity
@Table(name = "room_3d_models")
class Room3DModel(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "model_id")
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    var room: Room,

    // AI 서버에 넘길 roomId. 백엔드 room.id(Long)랑 구분하려고 UUID 문자열 사용
    @Column(name = "ai_room_id", nullable = false, unique = true)
    var aiRoomId: String = UUID.randomUUID().toString(),

    // 일단 대표 이미지 URL 하나 저장. 여러 장은 roomData나 별도 필드로 확장 가능
    @Column(name = "original_image_url", columnDefinition = "TEXT")
    var originalImageUrl: String? = null,

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source_type", nullable = false, length = 20)
    var sourceType: ModelSourceType = ModelSourceType.LEGACY_AI,

    @Column(name = "source_image_asset_ids", columnDefinition = "LONGTEXT")
    var sourceImageAssetIds: String? = null,

    //s3링크
    @Column(name = "model_url", nullable = true, columnDefinition = "TEXT")
    var modelUrl: String? = null,

    @Column(name = "file_size_mb", precision = 10, scale = 2)
    var fileSizeMb: BigDecimal? = null,

    @Column(nullable = true)
    var format: String? = null, // gltf, obj 등

    // String 대신 Enum 사용으로 타입 안정성 확보
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 40)
    var status: ModelStatus = ModelStatus.ANALYZING,

    // 🌟 핵심: 프론트와 합의한 3D 가상 공간 좌표 JSON 데이터를 통째로 저장하는 컬럼
    @Column(name = "room_data", columnDefinition = "LONGTEXT")
    var roomData: String? = null,

    @Column(name = "uploaded_at", nullable = false)
    var uploadedAt: LocalDateTime = LocalDateTime.now()
)
