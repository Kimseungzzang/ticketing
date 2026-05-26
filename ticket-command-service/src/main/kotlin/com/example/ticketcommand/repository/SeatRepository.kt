package com.example.ticketcommand.repository

import com.example.ticketcommand.entity.SeatEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface SeatRepository : JpaRepository<SeatEntity, String> {
    fun findBySectionId(sectionId: String): List<SeatEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByIdIn(ids: Collection<String>): List<SeatEntity>
}
