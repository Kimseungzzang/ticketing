package com.example.ticketquery.controller

import com.example.ticketquery.dto.ReservationView
import com.example.ticketquery.service.ReservationQueryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/reservations")
class ReservationQueryController(
    private val reservationQueryService: ReservationQueryService,
) {
    @GetMapping("/{id}")
    fun getReservation(@PathVariable id: String): ReservationView = reservationQueryService.getReservation(id)

    @GetMapping
    fun listByUser(@RequestParam userId: String): List<ReservationView> = reservationQueryService.listByUser(userId)
}
