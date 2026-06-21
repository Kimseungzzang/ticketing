package com.example.bookingservice.repository

import com.example.bookingservice.domain.Seat
import org.springframework.data.jpa.repository.JpaRepository

interface SeatRepository : JpaRepository<Seat, String> {
    fun findByEventIdOrderBySectionIdAscRowAscNumberAsc(eventId: String): List<Seat>
    fun countByEventId(eventId: String): Long
}
