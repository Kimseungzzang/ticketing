package com.example.ticketcommand.exception

import com.example.ticketcommand.service.InvalidReservationStateException
import com.example.ticketcommand.service.ReservationNotFoundException
import com.example.ticketcommand.service.SeatNotAvailableException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(SeatNotAvailableException::class)
    fun handleSeatNotAvailable(e: SeatNotAvailableException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "SEAT_NOT_AVAILABLE", "message" to e.message))

    @ExceptionHandler(ReservationNotFoundException::class)
    fun handleReservationNotFound(e: ReservationNotFoundException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "RESERVATION_NOT_FOUND", "message" to e.message))

    @ExceptionHandler(InvalidReservationStateException::class)
    fun handleInvalidState(e: InvalidReservationStateException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "INVALID_RESERVATION_STATE", "message" to e.message))
}
