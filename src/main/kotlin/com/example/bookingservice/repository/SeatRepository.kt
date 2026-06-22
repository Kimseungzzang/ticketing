package com.example.bookingservice.repository

import com.example.bookingservice.domain.Seat
import com.example.bookingservice.domain.SeatStatus
import org.springframework.data.jpa.repository.JpaRepository

interface SeatRepository : JpaRepository<Seat, String> {
    fun findByEventIdOrderBySectionIdAscRowAscNumberAsc(eventId: String): List<Seat>
    fun countByEventId(eventId: String): Long
    fun countByEventIdAndStatus(eventId: String, status: SeatStatus): Long
}
