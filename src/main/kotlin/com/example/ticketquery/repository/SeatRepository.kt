package com.example.ticketquery.repository

import com.example.ticketquery.entity.SeatEntity
import org.springframework.data.jpa.repository.JpaRepository

interface SeatRepository : JpaRepository<SeatEntity, String> {
    fun findBySectionId(sectionId: String): List<SeatEntity>
    fun findBySectionIdIn(sectionIds: Collection<String>): List<SeatEntity>
}
