package com.example.ticketquery.repository

import com.example.ticketquery.entity.ReservationEntity
import org.springframework.data.jpa.repository.JpaRepository

interface ReservationRepository : JpaRepository<ReservationEntity, String> {
    fun findByUserId(userId: String): List<ReservationEntity>
}
