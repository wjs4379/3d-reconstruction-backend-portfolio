package com.hanyang.backend.domain.model3d

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

data class ManagedAnalyzeRequest(
    @param:JsonProperty(required = true) @param:JsonSetter(nulls = Nulls.FAIL)
    @field:NotEmpty @field:Size(min = AiPayloadLimits.SOURCE_IMAGE_COUNT, max = AiPayloadLimits.SOURCE_IMAGE_COUNT, message = "같은 공간을 촬영한 사진 4장을 선택해 주세요.") val imageAssetIds: List<Long>
)
data class ManagedModelView(
    val modelId: Long, val spaceId: Long, val unitId: Long, val status: ModelStatus,
    val sourceType: ModelSourceType, val sourceImageAssetIds: List<Long>, val assetId: Long?,
    val modelUrl: String?, val roomData: Any?, val createdAt: LocalDateTime,
    val legacyImportRequired: Boolean
)

@RestController
@RequestMapping("/api/v2/spaces/{spaceId}/models")
class ManagedModelController(private val models: ManagedModelService) {
    @GetMapping fun list(@PathVariable spaceId: Long) = models.list(spaceId)
    @GetMapping("/{modelId}") fun get(@PathVariable spaceId: Long, @PathVariable modelId: Long) = models.get(spaceId, modelId)
    @PostMapping("/analyze") fun analyze(@PathVariable spaceId: Long, @Valid @RequestBody request: ManagedAnalyzeRequest) = models.analyze(spaceId, request)
    @PostMapping("/{modelId}/reconstruct") fun reconstruct(@PathVariable spaceId: Long, @PathVariable modelId: Long, @Valid @RequestBody request: CorrectionRequestDto) = models.reconstruct(spaceId, modelId, request)
    @PostMapping("/{modelId}/refresh") fun refresh(@PathVariable spaceId: Long, @PathVariable modelId: Long) = models.refresh(spaceId, modelId)
}
