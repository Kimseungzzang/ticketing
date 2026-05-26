package com.example.ticketquery.service

import com.example.ticketquery.dto.EventView
import com.example.ticketquery.dto.SeatMapView
import com.example.ticketquery.repository.EventRepository
import com.example.ticketquery.repository.SeatRepository
import com.example.ticketquery.repository.SeatSectionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

class EventNotFoundException(message: String) : RuntimeException(message)

@Service
@Transactional(readOnly = true)
class EventQueryService(
    private val eventRepository: EventRepository,
    private val seatSectionRepository: SeatSectionRepository,
    private val seatRepository: SeatRepository,
) {
    fun listEvents(): List<EventView> = eventRepository.findAll().map(EventView::from)

    fun getEvent(eventId: String): EventView = eventRepository.findById(eventId)
        .map(EventView::from)
        .orElseThrow { EventNotFoundException("Event not found: $eventId") }

    fun getSeatMap(eventId: String): SeatMapView {
        if (!eventRepository.existsById(eventId)) {
            throw EventNotFoundException("Event not found: $eventId")
        }
        val sections = seatSectionRepository.findByEventId(eventId)
        val seats = seatRepository.findBySectionIdIn(sections.map { it.id }).groupBy { it.sectionId }
        return SeatMapView.build(eventId, sections, seats)
    }
}
