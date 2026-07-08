package com.example.ticketquery.repository

import com.example.ticketquery.entity.SeatSectionEntity
import org.springframework.data.jpa.repository.JpaRepository

interface SeatSectionRepository : JpaRepository<SeatSectionEntity, String> {
    fun findByEventId(eventId: String): List<SeatSectionEntity>
}
