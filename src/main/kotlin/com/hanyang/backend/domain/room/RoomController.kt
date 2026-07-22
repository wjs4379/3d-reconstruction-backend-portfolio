package com.hanyang.backend.domain.room

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@CrossOrigin(origins = ["*"])
@RestController
@RequestMapping("/api/rooms")
class RoomController(
    private val roomService: RoomService
) {

    /** 방 메타데이터를 생성하고, 후속 분석 요청에 사용할 roomId를 반환합니다. */
    @PostMapping
    fun createRoom(
        @RequestBody @Valid request: RoomCreateRequest
    ): ResponseEntity<RoomCreateResponse> {
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(roomService.createRoom(request))
    }
}
