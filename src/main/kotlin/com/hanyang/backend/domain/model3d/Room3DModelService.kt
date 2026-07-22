package com.hanyang.backend.domain.model3d

import com.hanyang.backend.domain.room.AiWebhookRequest
import com.hanyang.backend.domain.room.RenderStatus
import com.hanyang.backend.domain.room.RoomRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.UUID

@Service
class Room3DModelService(
    private val roomRepository: RoomRepository,
    private val room3DModelRepository: Room3DModelRepository,
    private val aiServerClient: AiServerClient,
    private val jsonMapper: JsonMapper,
    private val transactionOperations: TransactionOperations,
    @param:Value("\${app.webhook-url}")
    private val webhookUrl: String) {
    private val log = LoggerFactory.getLogger(javaClass)

    /*
     * [프론트 -> 백엔드]
     * POST /api/rooms/{roomId}/models/analyze
     *
     * 프론트가 S3 URL을 보내면:
     * 1. room 존재 확인
     * 2. Room3DModel 생성
     * 3. AI 서버에 imagePaths로 S3 URL 전달
     * 4. AI 응답을 roomData에 저장
     */
    @Transactional
    fun analyzeRoom(
        roomId: Long,
        request: AnalyzeModelRequest
    ): ModelStatusResponse {
        require(request.imageUrls.isNotEmpty()) {
            "이미지 URL은 최소 1개 이상 필요합니다."
        }
        require(request.imageUrls.none(String::isBlank)) {
            "비어 있는 이미지 URL은 전송할 수 없습니다."
        }
        //기존 방과 연결되어야하므로 방 존재 여부를 먼저 확인한다.
        val room = roomRepository.findById(roomId)
            .orElseThrow {
                IllegalArgumentException("해당 방이 존재하지 않습니다. roomId=$roomId")
            }

        val ownerId = room.owner.id
            ?: throw IllegalStateException("방 소유자의 userId가 존재하지 않습니다.")

        /*
         * [DB 저장]
         * 분석 요청이 들어오면 먼저 Room3DModel을 생성합니다.
         */
        val model = room3DModelRepository.save(
            Room3DModel(
                room = room,
                aiRoomId = UUID.randomUUID().toString(),
                originalImageUrl = request.imageUrls.first(),
                status = ModelStatus.ANALYZING
            )
        )

        /*
         * [백엔드 -> AI 서버 요청 변환]
         * 프론트는 imageUrls로 보냈지만,
         * AI 서버 Swagger는 imagePaths를 요구합니다.
         *
         * 따라서 이름만 imagePaths로 바꿔서 보냅니다.
         * 값은 S3 URL 그대로입니다.
         */
        val aiRequest = AiAnalyzeRequestDto(
            imagePaths = request.imageUrls,
            roomId = model.aiRoomId,
            roomName = room.roomName,
            userId = toAiUserUuid(ownerId),
            webhookUrl = webhookUrl
        )
        dispatchAfterCommit(
            aiRoomId = model.aiRoomId,
            operation = "analyze"
        ) {
            aiServerClient.analyze(aiRequest)
        }

        return model.toStatusResponse()

    }


    /*
     * [프론트 -> 백엔드]
     * GET /api/rooms/{roomId}/models/status
     *
     * 해당 방의 최신 3D 모델 상태를 프론트에 내려줍니다.
     */
    @Transactional(readOnly = true)
    fun getModelStatus(roomId: Long): ModelStatusResponse {
        val model = findLatestModelByRoomId(roomId)
        return model.toStatusResponse()
    }

    /** 보정 요청이 완료된 뒤 재구성 요청을 전송하도록 하나의 체인으로 실행합니다. */
    @Transactional
    fun applyCorrectionAndReconstruct(
        roomId: Long,
        request: CorrectionRequestDto
    ): ModelStatusResponse {
        validateCorrection(request)

        val model = findLatestModelByRoomIdForUpdate(roomId)
        check(model.status == ModelStatus.WAITING_FOR_CORRECTION) {
            "현재 상태에서는 재구성을 시작할 수 없습니다. status=${model.status}"
        }

        model.status = ModelStatus.RECONSTRUCTING

        dispatchAfterCommit(
            aiRoomId = model.aiRoomId,
            operation = "correction-and-reconstruct"
        ) {
            aiServerClient.sendCorrection(model.aiRoomId, request)
                .then(aiServerClient.reconstruct(model.aiRoomId))
        }

        return model.toStatusResponse()
    }

    /** 프론트가 만든 최종 모델 파일의 URL을 저장하고 작업을 완료합니다. */
    @Transactional
    fun completeModelUpload(
        roomId: Long,
        modelId: Long,
        request: ModelUploadResultRequest
    ): ModelStatusResponse {
        val model = room3DModelRepository.findByIdAndRoomId(modelId, roomId)
            .orElseThrow {
                IllegalArgumentException(
                    "해당 방의 3D 모델이 존재하지 않습니다. roomId=$roomId, modelId=$modelId"
                )
            }
        val modelUrl = validateAndNormalizeModelUrl(request.modelUrl)
        val format = request.format?.trim()?.lowercase()?.also {
            require(it.isNotEmpty()) {
                "파일 포맷을 전달할 경우 비어 있을 수 없습니다."
            }
        }

        if (model.status == ModelStatus.SUCCESS) {
            check(model.modelUrl == modelUrl) {
                "이미 완료된 모델의 URL은 변경할 수 없습니다."
            }
            check(
                request.fileSizeMb == null ||
                    model.fileSizeMb?.compareTo(request.fileSizeMb) == 0
            ) {
                "이미 완료된 모델의 파일 크기는 변경할 수 없습니다."
            }
            check(format == null || model.format == format) {
                "이미 완료된 모델의 파일 포맷은 변경할 수 없습니다."
            }
            return model.toStatusResponse()
        }

        check(model.status == ModelStatus.WAITING_FOR_MODEL_UPLOAD) {
            "현재 상태에서는 최종 모델 URL을 저장할 수 없습니다. status=${model.status}"
        }
        check(hasValidFinalRoomData(model.roomData)) {
            "최종 좌표가 준비되지 않아 모델 URL을 저장할 수 없습니다."
        }

        model.modelUrl = modelUrl
        request.fileSizeMb?.let { model.fileSizeMb = it }
        format?.let { model.format = it }
        model.status = ModelStatus.SUCCESS

        return model.toStatusResponse()
    }

    /*
     * [프론트 -> 백엔드]
     * POST /api/rooms/{roomId}/models/refresh
     *
     * AI 서버의 현재 상태를 다시 조회해서 백엔드 DB에 반영합니다.
     */
    fun refreshFromAi(roomId: Long): ModelStatusResponse {
        val snapshot = findLatestModelByRoomId(roomId)
        val modelId = requireNotNull(snapshot.id) {
            "상태를 갱신할 3D 모델의 modelId가 존재하지 않습니다."
        }
        val aiResponse = aiServerClient.getRoom(snapshot.aiRoomId)

        return transactionOperations.execute {
            val model = room3DModelRepository.findByIdAndRoomId(modelId, roomId)
                .orElseThrow {
                    IllegalArgumentException(
                        "해당 방의 3D 모델이 존재하지 않습니다. roomId=$roomId, modelId=$modelId"
                    )
            }
            val currentStatus = model.status
            val initialResultReady = hasCoordinatePayload(
                vertices = aiResponse.aiVertices,
                edges = aiResponse.aiEdges
            )
            val finalResultReady = hasCoordinatePayload(
                vertices = aiResponse.finalVertices,
                edges = aiResponse.finalEdges
            )

            model.status = mapAiStatusToModelStatus(
                aiStatus = aiResponse.status,
                current = currentStatus,
                initialResultReady = initialResultReady,
                finalResultReady = finalResultReady
            )

            if (
                initialResultReady &&
                model.status == ModelStatus.WAITING_FOR_CORRECTION &&
                (
                    currentStatus == ModelStatus.ANALYZING ||
                        !hasValidInitialRoomData(model.roomData)
                    )
            ) {
                model.roomData = jsonMapper.writeValueAsString(
                    mapOf(
                        "aiVertices" to aiResponse.aiVertices,
                        "aiEdges" to aiResponse.aiEdges
                    )
                )
            }

            if (
                finalResultReady &&
                model.status == ModelStatus.WAITING_FOR_MODEL_UPLOAD &&
                (
                    currentStatus == ModelStatus.RECONSTRUCTING ||
                        !hasValidFinalRoomData(model.roomData)
                    )
            ) {
                model.roomData = jsonMapper.writeValueAsString(
                    mapOf(
                        "finalVertices" to aiResponse.finalVertices,
                        "finalEdges" to aiResponse.finalEdges
                    )
                )
            }

            model.toStatusResponse()
        } ?: throw IllegalStateException("AI 상태를 DB에 반영하지 못했습니다.")
    }

    /*
     * [추가]
     * 해당 방의 최신 3D 모델을 가져옵니다.
     */
    private fun findLatestModelByRoomId(roomId: Long): Room3DModel {
        return room3DModelRepository.findTopByRoomIdOrderByUploadedAtDesc(roomId)
            .orElseThrow {
                IllegalArgumentException("해당 방의 3D 모델이 존재하지 않습니다. roomId=$roomId")
            }
    }

    /*
     * [추가]
     * AI 서버 Swagger에서 userId가 uuid 형식이라
     * 백엔드 Long userId를 UUID 문자열로 변환합니다.
     */
    private fun toAiUserUuid(userId: Long): String {
        return UUID.nameUUIDFromBytes("user:$userId".toByteArray()).toString()
    }

    /*
     * [추가]
     * AI 서버 status 문자열을 백엔드 ModelStatus enum으로 변환합니다.
     */
    private fun mapAiStatusToModelStatus(
        aiStatus: String?,
        current: ModelStatus,
        initialResultReady: Boolean,
        finalResultReady: Boolean
    ): ModelStatus {
        val incoming = when (aiStatus?.trim()?.lowercase()) {
            "analyzing", "processing" -> RenderStatus.ANALYZING
            "pending_correction", "waiting_for_correction" ->
                RenderStatus.WAITING_FOR_CORRECTION
            "corrected", "reconstructing" -> RenderStatus.RECONSTRUCTING
            "success", "completed" -> RenderStatus.SUCCESS
            "failed", "error" -> RenderStatus.FAILED
            else -> null
        }

        return incoming?.let {
            resolveWebhookStatus(
                current = current,
                incoming = it,
                initialResultReady = initialResultReady,
                finalResultReady = finalResultReady
            )
        } ?: current
    }


    /*
     * [추가]
     * Entity를 프론트 응답 DTO로 바꿔주는 함수입니다.
     */
    private fun Room3DModel.toStatusResponse(): ModelStatusResponse {
        return ModelStatusResponse(
            modelId = this.id,
            roomId = this.room.id,
            status = this.status,
            modelUrl = this.modelUrl,
            roomData = this.roomData?.let { json ->
                jsonMapper.readValue(json, Any::class.java)
            }
        )
    }

    // ==========================================
    // 🌟 AI 서버로부터 Webhook 수신 후 DB 업데이트
    // ==========================================
    /**
     * AI 서버 웹훅으로 상태와 결과 데이터를 갱신합니다.
     */
    @Transactional
    fun handleAiWebhook(request: AiWebhookRequest) {
        val model = room3DModelRepository.findByAiRoomIdForUpdate(request.aiRoomId)
            .orElseThrow {
                IllegalArgumentException(
                    "해당 AI Room ID를 찾을 수 없습니다. aiRoomId=${request.aiRoomId}"
                )
            }

        val currentStatus = model.status
        val payloadApplicable = shouldApplyWebhookPayload(
            current = currentStatus,
            incoming = request.status
        )
        val parsedRoomData = if (payloadApplicable) {
            request.roomDataJson?.let { rawRoomData ->
                if (request.status == RenderStatus.FAILED) {
                    runCatching { parseRoomDataObject(rawRoomData) }.getOrNull()
                } else {
                    parseRoomDataObject(rawRoomData)
                }
            }
        } else {
            null
        }
        val initialResultReady = hasInitialCoordinatePayload(parsedRoomData)
        val finalResultReady = hasFinalCoordinatePayload(parsedRoomData)

        if (
            currentStatus == ModelStatus.ANALYZING &&
            request.status in setOf(
                RenderStatus.WAITING_FOR_CORRECTION,
                RenderStatus.SUCCESS
            )
        ) {
            require(initialResultReady) {
                "AI 분석 완료 Webhook에는 유효한 aiVertices와 aiEdges가 필요합니다."
            }
        }

        if (
            currentStatus == ModelStatus.RECONSTRUCTING &&
            request.status == RenderStatus.SUCCESS
        ) {
            require(finalResultReady) {
                "AI 최종 SUCCESS Webhook에는 finalVertices와 finalEdges가 필요합니다."
            }
        }

        model.status = resolveWebhookStatus(
            current = currentStatus,
            incoming = request.status,
            initialResultReady = initialResultReady,
            finalResultReady = finalResultReady
        )

        val roomDataUpdated =
            payloadApplicable &&
                request.roomDataJson != null &&
                (request.status != RenderStatus.FAILED || parsedRoomData != null)
        if (roomDataUpdated) {
            model.roomData = request.roomDataJson
        } else if (!payloadApplicable && request.roomDataJson != null) {
            log.info(
                "지연 Webhook 결과 필드 무시. aiRoomId={}, currentStatus={}, incomingStatus={}",
                request.aiRoomId,
                currentStatus,
                request.status
            )
        } else if (
            payloadApplicable &&
            request.status == RenderStatus.FAILED &&
            request.roomDataJson != null &&
            parsedRoomData == null
        ) {
            log.warn(
                "FAILED Webhook의 유효하지 않은 roomDataJson은 저장하지 않습니다. aiRoomId={}",
                request.aiRoomId
            )
        }

        log.info(
            "AI webhook 반영 완료. aiRoomId={}, status={}, roomDataUpdated={}",
            request.aiRoomId,
            model.status,
            roomDataUpdated
        )
    }

    /**
     * 늦게 도착한 웹훅이 현재 상태를 과거 단계로 되돌리지 않도록
     * 다음 상태를 계산합니다.
     */
    private fun resolveWebhookStatus(
        current: ModelStatus,
        incoming: RenderStatus,
        initialResultReady: Boolean,
        finalResultReady: Boolean
    ): ModelStatus {

        // 성공 또는 실패로 종료된 작업은 늦은 Webhook으로 되살리지 않습니다.
        if (current == ModelStatus.SUCCESS || current == ModelStatus.FAILED) {
            return current
        }

        return when (incoming) {

            /*
             * ANALYZING은 최초 분석 단계에서만 적용합니다.
             *
             * 현재 상태가 이미 WAITING_FOR_CORRECTION이나
             * RECONSTRUCTING이면 늦게 온 ANALYZING 웹훅이므로 무시합니다.
             */
            RenderStatus.ANALYZING -> {
                if (current == ModelStatus.ANALYZING) {
                    ModelStatus.ANALYZING
                } else {
                    current
                }
            }

            /*
             * 보정 대기 상태는 ANALYZING 단계에서만 이동할 수 있습니다.
             *
             * 이미 재구성 중이라면 늦게 도착한 Phase 1 웹훅이므로
             * 현재 상태를 유지합니다.
             */
            RenderStatus.WAITING_FOR_CORRECTION -> {
                if (
                    current == ModelStatus.ANALYZING && initialResultReady
                ) {
                    ModelStatus.WAITING_FOR_CORRECTION
                } else if (current == ModelStatus.WAITING_FOR_CORRECTION) {
                    ModelStatus.WAITING_FOR_CORRECTION
                } else {
                    current
                }
            }

            /*
             * AI 서버가 재구성을 시작했다는 웹훅입니다.
             */
            RenderStatus.RECONSTRUCTING -> {
                when (current) {
                    ModelStatus.WAITING_FOR_CORRECTION,
                    ModelStatus.RECONSTRUCTING -> ModelStatus.RECONSTRUCTING
                    else -> current
                }
            }

            /*
             * 어느 단계에서든 실패 웹훅이 오면 FAILED로 변경합니다.
             */
            RenderStatus.FAILED -> {
                when (current) {
                    ModelStatus.ANALYZING,
                    ModelStatus.RECONSTRUCTING -> ModelStatus.FAILED
                    else -> current
                }
            }

            /*
             * SUCCESS는 현재 단계에 따라 의미가 다릅니다.
             *
             * ANALYZING에서 SUCCESS:
             *   Phase 1 분석 완료 → 보정 대기
             *
             * RECONSTRUCTING에서 SUCCESS:
             *   Phase 2 좌표 생성 완료 → 프론트 모델 업로드 대기
             */
            RenderStatus.SUCCESS -> {
                when {
                    // Phase 1 분석이 끝났으므로 보정 입력을 기다립니다.
                    current == ModelStatus.ANALYZING && initialResultReady -> {
                        ModelStatus.WAITING_FOR_CORRECTION
                    }

                    // Phase 2 좌표 생성 완료 후 프론트가 모델 파일을 업로드합니다.
                    current == ModelStatus.RECONSTRUCTING && finalResultReady -> {
                        ModelStatus.WAITING_FOR_MODEL_UPLOAD
                    }

                    current == ModelStatus.WAITING_FOR_MODEL_UPLOAD ->
                        ModelStatus.WAITING_FOR_MODEL_UPLOAD

                    // 이미 다른 단계라면 현재 상태를 유지합니다.
                    else -> {
                        current
                    }
                }
            }
        }
    }

    /** 과거 단계의 Webhook이 최신 결과 필드를 덮어쓰지 못하게 합니다. */
    private fun shouldApplyWebhookPayload(
        current: ModelStatus,
        incoming: RenderStatus
    ): Boolean {
        return when (current) {
            ModelStatus.ANALYZING -> incoming in setOf(
                RenderStatus.ANALYZING,
                RenderStatus.WAITING_FOR_CORRECTION,
                RenderStatus.SUCCESS,
                RenderStatus.FAILED
            )

            ModelStatus.RECONSTRUCTING -> incoming in setOf(
                RenderStatus.RECONSTRUCTING,
                RenderStatus.SUCCESS,
                RenderStatus.FAILED
            )

            ModelStatus.WAITING_FOR_CORRECTION,
            ModelStatus.WAITING_FOR_MODEL_UPLOAD,
            ModelStatus.SUCCESS,
            ModelStatus.FAILED -> false
        }
    }

    private fun findLatestModelByRoomIdForUpdate(roomId: Long): Room3DModel {
        return room3DModelRepository.findFirstByRoomIdOrderByUploadedAtDesc(roomId)
            .orElseThrow {
                IllegalArgumentException("해당 방의 3D 모델이 존재하지 않습니다. roomId=$roomId")
            }
    }

    private fun validateAndNormalizeModelUrl(rawModelUrl: String): String {
        val modelUrl = rawModelUrl.trim()
        val uri = runCatching { URI(modelUrl) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()

        require(
            uri?.isAbsolute == true &&
                (scheme == "http" || scheme == "https") &&
                !uri.host.isNullOrBlank()
        ) {
            "최종 모델 URL은 유효한 http 또는 https 절대 주소여야 합니다."
        }

        return modelUrl
    }

    private fun parseRoomDataObject(rawRoomData: String): Map<*, *> {
        val parsed = try {
            jsonMapper.readValue(rawRoomData, Any::class.java)
        } catch (exception: Exception) {
            throw IllegalArgumentException(
                "roomDataJson은 유효한 JSON 객체여야 합니다.",
                exception
            )
        }

        return parsed as? Map<*, *>
            ?: throw IllegalArgumentException(
                "roomDataJson은 유효한 JSON 객체여야 합니다."
            )
    }

    private fun hasFinalCoordinatePayload(roomData: Map<*, *>?): Boolean {
        if (roomData == null) {
            return false
        }

        return hasCoordinatePayload(
            verticesValue = roomData["finalVertices"],
            edgesValue = roomData["finalEdges"]
        )
    }

    private fun hasInitialCoordinatePayload(roomData: Map<*, *>?): Boolean {
        if (roomData == null) {
            return false
        }

        return hasCoordinatePayload(
            verticesValue = roomData["aiVertices"],
            edgesValue = roomData["aiEdges"]
        ) || hasCoordinatePayload(
            verticesValue = roomData["vertices"],
            edgesValue = roomData["edges"]
        )
    }

    private fun hasCoordinatePayload(
        verticesValue: Any?,
        edgesValue: Any?
    ): Boolean {
        val vertices = verticesValue as? List<*> ?: return false
        val edges = edgesValue as? List<*> ?: return false
        val vertexIds = mutableSetOf<String>()

        for (vertexValue in vertices) {
            val vertex = vertexValue as? Map<*, *> ?: return false
            val id = (vertex["id"] as? String)
                ?.takeIf(String::isNotBlank)
                ?: return false
            if (!vertexIds.add(id)) {
                return false
            }
            if (
                !isFiniteNumber(vertex["x"]) ||
                !isFiniteNumber(vertex["y"]) ||
                !isFiniteNumber(vertex["z"])
            ) {
                return false
            }
            val imageIndex = vertex["imageIndex"]
            if (imageIndex != null) {
                if (imageIndex !is Number) {
                    return false
                }
                val numericImageIndex = imageIndex.toDouble()
                if (
                    !numericImageIndex.isFinite() ||
                    numericImageIndex % 1.0 != 0.0 ||
                    numericImageIndex < Int.MIN_VALUE.toDouble() ||
                    numericImageIndex > Int.MAX_VALUE.toDouble()
                ) {
                    return false
                }
            }
            val sourceVertexId = vertex["sourceVertexId"]
            if (
                sourceVertexId != null &&
                (sourceVertexId !is String || sourceVertexId.isBlank())
            ) {
                return false
            }
        }

        val edgeIds = mutableSetOf<String>()
        for (edgeValue in edges) {
            val edge = edgeValue as? Map<*, *> ?: return false
            val id = (edge["id"] as? String)
                ?.takeIf(String::isNotBlank)
                ?: return false
            val startVertexId = (edge["startVertexId"] as? String)
                ?.takeIf(String::isNotBlank)
                ?: return false
            val endVertexId = (edge["endVertexId"] as? String)
                ?.takeIf(String::isNotBlank)
                ?: return false

            if (
                !edgeIds.add(id) ||
                startVertexId !in vertexIds ||
                endVertexId !in vertexIds
            ) {
                return false
            }
        }

        return true
    }

    private fun hasCoordinatePayload(
        vertices: List<VertexDto>?,
        edges: List<EdgeDto>?
    ): Boolean {
        if (vertices == null || edges == null) {
            return false
        }

        val vertexIds = mutableSetOf<String>()
        for (vertex in vertices) {
            if (
                vertex.id.isBlank() ||
                !vertex.x.isFinite() ||
                !vertex.y.isFinite() ||
                !vertex.z.isFinite() ||
                !vertexIds.add(vertex.id)
            ) {
                return false
            }
        }

        val edgeIds = mutableSetOf<String>()
        for (edge in edges) {
            if (
                edge.id.isBlank() ||
                edge.startVertexId.isBlank() ||
                edge.endVertexId.isBlank() ||
                !edgeIds.add(edge.id) ||
                edge.startVertexId !in vertexIds ||
                edge.endVertexId !in vertexIds
            ) {
                return false
            }
        }

        return true
    }

    private fun isFiniteNumber(value: Any?): Boolean {
        return value is Number && value.toDouble().isFinite()
    }

    private fun hasValidFinalRoomData(rawRoomData: String?): Boolean {
        if (rawRoomData == null) {
            return false
        }

        val parsed = runCatching {
            parseRoomDataObject(rawRoomData)
        }.getOrNull()

        return hasFinalCoordinatePayload(parsed)
    }

    private fun hasValidInitialRoomData(rawRoomData: String?): Boolean {
        if (rawRoomData == null) {
            return false
        }

        val parsed = runCatching {
            parseRoomDataObject(rawRoomData)
        }.getOrNull()

        return hasInitialCoordinatePayload(parsed)
    }

    private fun validateCorrection(request: CorrectionRequestDto) {
        require(request.vertices.isNotEmpty()) {
            "보정된 vertex는 최소 1개 이상 필요합니다."
        }
        require(
            hasCoordinatePayload(
                vertices = request.vertices,
                edges = request.edges
            )
        ) {
            "보정 좌표의 ID·좌표 값과 edge 연결 관계를 확인해 주세요."
        }
    }

    /** DB 커밋 이후 외부 AI 요청을 시작합니다. */
    private fun dispatchAfterCommit(
        aiRoomId: String,
        operation: String,
        request: () -> Mono<Void>
    ) {
        val dispatch = {
            Mono.defer { request() }
                // 오류 발생 시 JPA 처리를 별도 스레드에서 실행합니다.
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                    { },
                    { error ->
                        log.error(
                            "AI 비동기 요청 실패. operation={}, aiRoomId={}",
                            operation,
                            aiRoomId,
                            error
                        )
                        markDispatchFailed(
                            aiRoomId,
                            operation,
                            error
                        )
                    },
                    {
                        log.info(
                            "AI 서버 요청 완료. operation={}, aiRoomId={}",
                            operation,
                            aiRoomId
                        )
                    }
                )
        }
        //커밋 뒤 실행
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        dispatch()
                    }
                }
            )
        } else { //트랜잭션이 없으면 바로 실행
            dispatch()
        }
    }

    private fun markDispatchFailed(
        aiRoomId: String,
        operation: String,
        error: Throwable
    ) {
        val expectedStatus = when (operation) {
            "analyze" -> ModelStatus.ANALYZING
            "correction-and-reconstruct" -> ModelStatus.RECONSTRUCTING
            else -> {
                log.warn(
                    "알 수 없는 AI 요청 작업의 실패 상태를 반영하지 않습니다. operation={}, aiRoomId={}",
                    operation,
                    aiRoomId
                )
                return
            }
        }
        val errorRoomData = jsonMapper.writeValueAsString(
            mapOf(
                "code" to "AI_REQUEST_FAILED",
                "operation" to operation,
                "message" to (error.message ?: "AI 서버 요청에 실패했습니다.")
            )
        )
        val updated = room3DModelRepository.markFailedIfCurrentStatus(
            aiRoomId = aiRoomId,
            expectedStatus = expectedStatus,
            failedStatus = ModelStatus.FAILED,
            errorRoomData = errorRoomData
        )

        if (updated == 0) {
            log.info(
                "현재 단계가 이미 진행되어 늦은 AI 요청 실패를 무시합니다. operation={}, aiRoomId={}",
                operation,
                aiRoomId
            )
        }
    }


}

