package com.example.bookingservice.controller

import com.example.bookingservice.dto.SeatSectionResponse
import com.example.bookingservice.service.SeatService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/seats")
class SeatController(private val seatService: SeatService) {

    @GetMapping("/{eventId}")
    fun getSections(@PathVariable eventId: String): ResponseEntity<List<SeatSectionResponse>> =
        ResponseEntity.ok(seatService.getSections(eventId))
}
