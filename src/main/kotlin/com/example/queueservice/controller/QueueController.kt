package com.example.queueservice.controller

import com.example.queueservice.dto.QueueEnterRequest
import com.example.queueservice.dto.QueueMessageResponse
import com.example.queueservice.dto.QueueStatusResponse
import com.example.queueservice.service.QueueService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/queue")
class QueueController(private val queueService: QueueService) {

    @PostMapping("/enter")
    fun enter(
        @AuthenticationPrincipal userId: String,
        @RequestBody req: QueueEnterRequest,
    ): ResponseEntity<QueueStatusResponse> =
        ResponseEntity.ok(queueService.enter(userId, req.eventId))

    @GetMapping("/status")
    fun status(
        @AuthenticationPrincipal userId: String,
        @RequestParam eventId: String,
    ): ResponseEntity<QueueStatusResponse> =
        ResponseEntity.ok(queueService.status(userId, eventId))

    @GetMapping("/validate")
    fun validate(
        @AuthenticationPrincipal userId: String,
        @RequestHeader("X-Entry-Token") entryToken: String,
    ): ResponseEntity<QueueMessageResponse> {
        val valid = queueService.validateEntryToken(userId, entryToken)
        return if (valid) {
            ResponseEntity.ok(QueueMessageResponse("유효한 입장 토큰입니다"))
        } else {
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(QueueMessageResponse("유효하지 않은 입장 토큰입니다"))
        }
    }

    @PostMapping("/release")
    fun release(
        @AuthenticationPrincipal userId: String,
        @RequestParam eventId: String,
    ): ResponseEntity<QueueMessageResponse> {
        queueService.release(userId, eventId)
        return ResponseEntity.ok(QueueMessageResponse("입장 토큰이 반납되었습니다"))
    }
}
