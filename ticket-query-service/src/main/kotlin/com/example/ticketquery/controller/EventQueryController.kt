package com.example.ticketquery.controller

import com.example.ticketquery.dto.EventView
import com.example.ticketquery.dto.SeatMapView
import com.example.ticketquery.service.EventQueryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/events")
class EventQueryController(
    private val eventQueryService: EventQueryService,
) {
    @GetMapping
    fun listEvents(): List<EventView> = eventQueryService.listEvents()

    @GetMapping("/{eventId}")
    fun getEvent(@PathVariable eventId: String): EventView = eventQueryService.getEvent(eventId)

    @GetMapping("/{eventId}/seats")
    fun getSeatMap(@PathVariable eventId: String): SeatMapView = eventQueryService.getSeatMap(eventId)
}
