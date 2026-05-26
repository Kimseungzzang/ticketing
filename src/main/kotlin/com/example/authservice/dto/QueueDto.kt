package com.example.authservice.dto

data class QueueEnterRequest(val eventId: String)

data class QueueStatusResponse(
    val status: String,        // WAITING | READY | NOT_IN_QUEUE
    val position: Long?,       // 1-indexed, READY면 0
    val total: Long,           // 현재 대기열 전체 크기
    val entryToken: String?,   // READY일 때만 존재
)
