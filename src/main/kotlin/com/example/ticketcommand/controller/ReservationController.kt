package com.example.ticketcommand.controller

import com.example.ticketcommand.dto.CreateReservationRequest
import com.example.ticketcommand.dto.ReservationResponse
import com.example.ticketcommand.service.ReservationService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/reservations")
class ReservationController(
    private val reservationService: ReservationService,
) {
    @PostMapping
    fun reserve(@Valid @RequestBody request: CreateReservationRequest): ResponseEntity<List<ReservationResponse>> {
        val created = reservationService.reserve(request.userId, request.seatIds)
        return ResponseEntity.status(HttpStatus.CREATED).body(created.map(ReservationResponse::from))
    }

    @PostMapping("/{id}/confirm")
    fun confirm(@PathVariable id: String): ReservationResponse =
        ReservationResponse.from(reservationService.confirm(id))

    @DeleteMapping("/{id}")
    fun cancel(@PathVariable id: String): ReservationResponse =
        ReservationResponse.from(reservationService.cancel(id))
}
