package com.hanyang.backend.domain.model3d

import com.hanyang.backend.domain.room.AiWebhookRequest
import com.hanyang.backend.domain.room.RenderStatus
import com.hanyang.backend.domain.room.RoomRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@Service
class Room3DModelService(
    private val roomRepository: RoomRepository,
    private val room3DModelRepository: Room3DModelRepository,
    private val aiServerClient: AiServerClient,
    private val jsonMapper: JsonMapper,
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

        val model = findLatestModelByRoomId(roomId)
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

    /*
     * [프론트 -> 백엔드]
     * POST /api/rooms/{roomId}/models/refresh
     *
     * AI 서버의 현재 상태를 다시 조회해서 백엔드 DB에 반영합니다.
     */
    @Transactional
    fun refreshFromAi(roomId: Long): ModelStatusResponse {
        val model = findLatestModelByRoomId(roomId)
        val aiResponse = aiServerClient.getRoom(model.aiRoomId)

        model.modelUrl = aiResponse.modelUrl ?: model.modelUrl
        model.status = mapAiStatusToModelStatus(
            aiStatus = aiResponse.status,
            current = model.status,
            modelUrl = aiResponse.modelUrl
        )

        return model.toStatusResponse()
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
        modelUrl: String?
    ): ModelStatus {
        return when (aiStatus?.trim()?.lowercase()) {
            "analyzing", "processing" ->
                ModelStatus.ANALYZING

            "pending_correction", "waiting_for_correction" ->
                ModelStatus.WAITING_FOR_CORRECTION

            "corrected", "reconstructing" ->
                ModelStatus.RECONSTRUCTING

            "success", "completed" -> when {
                // 최종 파일 URL까지 생성된 경우에만 최종 성공
                modelUrl != null ->
                    ModelStatus.SUCCESS

                // 1차 분석만 완료된 상태
                current == ModelStatus.ANALYZING ->
                    ModelStatus.WAITING_FOR_CORRECTION

                // 재구성 중인데 URL이 아직 없다면 완료 처리하지 않음
                current == ModelStatus.RECONSTRUCTING ->
                    ModelStatus.RECONSTRUCTING

                else ->
                    current
            }

            "failed", "error" ->
                ModelStatus.FAILED

            else ->
                current
        }
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
        val model = room3DModelRepository.findByAiRoomId(request.aiRoomId)
            .orElseThrow {
                IllegalArgumentException(
                    "해당 AI Room ID를 찾을 수 없습니다. aiRoomId=${request.aiRoomId}"
                )
            }

        val currentStatus = model.status
        val effectiveModelUrl = request.modelUrl ?: model.modelUrl

        model.status = resolveWebhookStatus(
            current = currentStatus,
            incoming = request.status,
            hasModelUrl = effectiveModelUrl != null
        )

        if (shouldApplyWebhookPayload(currentStatus, request.status)) {
            request.roomDataJson?.let {
                model.roomData = it
            }
            request.modelUrl?.let {
                model.modelUrl = it
            }
            request.fileSizeMb?.let {
                model.fileSizeMb = it
            }
            request.format?.let {
                model.format = it
            }
        } else {
            log.info(
                "지연 Webhook 결과 필드 무시. aiRoomId={}, currentStatus={}, incomingStatus={}",
                request.aiRoomId,
                currentStatus,
                request.status
            )
        }

        log.info(
            "AI webhook 반영 완료. aiRoomId={}, status={}, modelUrl={}",
            request.aiRoomId,
            model.status,
            model.modelUrl
        )
    }

    /**
     * 늦게 도착한 웹훅이 현재 상태를 과거 단계로 되돌리지 않도록
     * 다음 상태를 계산합니다.
     */
    private fun resolveWebhookStatus(
        current: ModelStatus,
        incoming: RenderStatus,
        hasModelUrl: Boolean
    ): ModelStatus {

        // 이미 최종 성공한 작업은 이후 웹훅으로 상태를 변경하지 않습니다.
        if (current == ModelStatus.SUCCESS) {
            return ModelStatus.SUCCESS
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
                    current == ModelStatus.ANALYZING ||
                    current == ModelStatus.WAITING_FOR_CORRECTION
                ) {
                    ModelStatus.WAITING_FOR_CORRECTION
                } else {
                    current
                }
            }

            /*
             * AI 서버가 재구성을 시작했다는 웹훅입니다.
             */
            RenderStatus.RECONSTRUCTING -> {
                ModelStatus.RECONSTRUCTING
            }

            /*
             * 어느 단계에서든 실패 웹훅이 오면 FAILED로 변경합니다.
             */
            RenderStatus.FAILED -> {
                ModelStatus.FAILED
            }

            /*
             * SUCCESS는 현재 단계에 따라 의미가 다릅니다.
             *
             * ANALYZING에서 SUCCESS:
             *   Phase 1 분석 완료 → 보정 대기
             *
             * RECONSTRUCTING에서 SUCCESS:
             *   Phase 2 완료 → 최종 성공
             */
            RenderStatus.SUCCESS -> {
                when {
                    // 최종 모델 URL이 확인된 경우에만 최종 성공으로 판단합니다.
                    hasModelUrl -> {
                        ModelStatus.SUCCESS
                    }

                    // Phase 1 분석이 끝났으므로 보정 입력을 기다립니다.
                    current == ModelStatus.ANALYZING -> {
                        ModelStatus.WAITING_FOR_CORRECTION
                    }

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
        if (current == ModelStatus.SUCCESS) {
            return false
        }

        if (
            current == ModelStatus.WAITING_FOR_CORRECTION &&
            incoming == RenderStatus.ANALYZING
        ) {
            return false
        }

        if (
            current == ModelStatus.RECONSTRUCTING &&
            incoming in setOf(
                RenderStatus.ANALYZING,
                RenderStatus.WAITING_FOR_CORRECTION
            )
        ) {
            return false
        }

        return true
    }

    private fun validateCorrection(request: CorrectionRequestDto) {
        require(request.vertices.isNotEmpty()) {
            "보정된 vertex는 최소 1개 이상 필요합니다."
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
        room3DModelRepository.findByAiRoomId(aiRoomId)
            .ifPresent { model ->
                if (model.status == ModelStatus.SUCCESS) {
                    return@ifPresent
                }

                model.status = ModelStatus.FAILED

                if (model.roomData == null) {
                    model.roomData = jsonMapper.writeValueAsString(
                        mapOf(
                            "code" to "AI_REQUEST_FAILED",
                            "operation" to operation,
                            "message" to (
                                    error.message
                                        ?: "AI 서버 요청에 실패했습니다."
                                    )
                        )
                    )
                }

                room3DModelRepository.save(model)
            }
    }


}

