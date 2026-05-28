package com.example.queueservice.exception

import com.example.queueservice.dto.QueueMessageResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<QueueMessageResponse> =
        ResponseEntity.badRequest().body(QueueMessageResponse(e.message ?: "잘못된 요청입니다"))

    @ExceptionHandler(Exception::class)
    fun handleGeneral(e: Exception): ResponseEntity<QueueMessageResponse> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(QueueMessageResponse("서버 오류가 발생했습니다"))
}
