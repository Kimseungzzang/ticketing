package com.example.ticketquery.exception

import com.example.ticketquery.service.EventNotFoundException
import com.example.ticketquery.service.ReservationNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(EventNotFoundException::class)
    fun handleEventNotFound(e: EventNotFoundException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "EVENT_NOT_FOUND", "message" to e.message))

    @ExceptionHandler(ReservationNotFoundException::class)
    fun handleReservationNotFound(e: ReservationNotFoundException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "RESERVATION_NOT_FOUND", "message" to e.message))
}
