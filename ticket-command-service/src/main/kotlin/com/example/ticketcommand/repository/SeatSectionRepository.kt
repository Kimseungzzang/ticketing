package com.example.ticketcommand.repository

import com.example.ticketcommand.entity.SeatSectionEntity
import org.springframework.data.jpa.repository.JpaRepository

interface SeatSectionRepository : JpaRepository<SeatSectionEntity, String> {
    fun findByEventId(eventId: String): List<SeatSectionEntity>
}
