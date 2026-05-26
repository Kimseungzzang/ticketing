package com.example.authservice.controller

import com.example.authservice.dto.QueueEnterRequest
import com.example.authservice.dto.QueueStatusResponse
import com.example.authservice.service.QueueService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/queue")
class QueueController(private val queueService: QueueService) {

    /** 로그인 후 대기열 진입. 이미 진입했으면 현재 상태 반환. */
    @PostMapping("/enter")
    fun enter(
        @AuthenticationPrincipal userId: String,
        @RequestBody req: QueueEnterRequest,
    ): ResponseEntity<QueueStatusResponse> =
        ResponseEntity.ok(queueService.enter(userId, req.eventId))

    /** 폴링용 — 현재 순위 또는 입장 토큰 반환. */
    @GetMapping("/status")
    fun status(
        @AuthenticationPrincipal userId: String,
        @RequestParam eventId: String,
    ): ResponseEntity<QueueStatusResponse> =
        ResponseEntity.ok(queueService.status(userId, eventId))

    /**
     * 좌석 선택 페이지 진입 전 토큰 검증.
     * Redis에 살아있는 토큰인지 확인.
     */
    @GetMapping("/validate")
    fun validate(
        @AuthenticationPrincipal userId: String,
        @RequestParam entryToken: String,
    ): ResponseEntity<Map<String, String>> {
        val valid = queueService.validateEntryToken(userId, entryToken)
        return if (valid) {
            ResponseEntity.ok(mapOf("message" to "유효한 입장 토큰입니다"))
        } else {
            ResponseEntity.status(403).body(mapOf("message" to "유효하지 않은 입장 토큰입니다"))
        }
    }

    /** 좌석 선택 완료 or 이탈 시 슬롯 반납. */
    @PostMapping("/release")
    fun release(
        @AuthenticationPrincipal userId: String,
        @RequestParam eventId: String,
    ): ResponseEntity<Map<String, String>> {
        queueService.release(userId, eventId)
        return ResponseEntity.ok(mapOf("message" to "입장 토큰이 반납되었습니다"))
    }
}
