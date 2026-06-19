package com.example.bookingservice.exception

import com.example.bookingservice.dto.ErrorResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(InvalidEntryTokenException::class)
    fun handleInvalidEntryToken(e: InvalidEntryTokenException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(e.message!!))

    @ExceptionHandler(SeatAlreadyTakenException::class, NoSeatsAvailableException::class)
    fun handleConflict(e: RuntimeException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(e.message!!))

    @ExceptionHandler(BookingNotFoundException::class)
    fun handleNotFound(e: BookingNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(e.message!!))

    @ExceptionHandler(BookingAlreadyCancelledException::class)
    fun handleAlreadyCancelled(e: BookingAlreadyCancelledException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse(e.message!!))

    @ExceptionHandler(Exception::class)
    fun handleGeneral(e: Exception): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse("서버 오류가 발생했습니다"))
}
