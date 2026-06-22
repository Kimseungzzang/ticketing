package com.example.bookingservice.controller

import com.example.bookingservice.dto.BookingCreateRequest
import com.example.bookingservice.dto.BookingResponse
import com.example.bookingservice.service.BookingService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/booking")
class BookingController(private val bookingService: BookingService) {

    @PostMapping
    fun create(
        @AuthenticationPrincipal userId: String,
        @RequestBody request: BookingCreateRequest,
    ): ResponseEntity<BookingResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(bookingService.create(userId, request))

    @PostMapping("/{bookingId}/confirm")
    fun confirm(
        @PathVariable bookingId: UUID,
    ): ResponseEntity<BookingResponse> =
        ResponseEntity.ok(bookingService.confirm(bookingId))
}
