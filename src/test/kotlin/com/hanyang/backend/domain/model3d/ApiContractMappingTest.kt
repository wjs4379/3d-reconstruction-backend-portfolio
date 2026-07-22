package com.hanyang.backend.domain.model3d

import com.hanyang.backend.domain.room.RoomController
import com.hanyang.backend.domain.room.WebhookController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

class ApiContractMappingTest {

    @Test
    fun `방과 3D 작업 API는 합의된 경로만 노출한다`() {
        assertEquals("/api/rooms", requestMappingOf(RoomController::class.java))
        assertEquals(
            "/api/rooms/{roomId}/models",
            requestMappingOf(Room3DModelController::class.java)
        )
        assertEquals("/api/webhook", requestMappingOf(WebhookController::class.java))

        val modelPostPaths = Room3DModelController::class.java.declaredMethods
            .mapNotNull { method ->
                method.getAnnotation(PostMapping::class.java)
                    ?.value
                    ?.singleOrNull()
                    ?: if (method.isAnnotationPresent(PostMapping::class.java)) "" else null
            }
            .toSet()
        val modelGetPaths = Room3DModelController::class.java.declaredMethods
            .mapNotNull { method ->
                method.getAnnotation(GetMapping::class.java)
                    ?.value
                    ?.singleOrNull()
            }
            .toSet()

        assertEquals(setOf("/analyze", "/reconstruct", "/refresh"), modelPostPaths)
        assertEquals(setOf("/status"), modelGetPaths)
        assertFalse(
            Room3DModelController::class.java.declaredMethods.any {
                it.isAnnotationPresent(PatchMapping::class.java)
            }
        )
    }

    @Test
    fun `재구성 API는 보정 좌표를 요청 본문으로 받는다`() {
        val reconstruct = Room3DModelController::class.java.declaredMethods
            .single { it.name == "reconstruct" }

        assertTrue(
            reconstruct.parameterTypes.contains(CorrectionRequestDto::class.java)
        )
    }

    private fun requestMappingOf(type: Class<*>): String {
        return type.getAnnotation(RequestMapping::class.java)
            .value
            .single()
    }
}
