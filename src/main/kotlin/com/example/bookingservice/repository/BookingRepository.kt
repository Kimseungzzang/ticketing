package com.example.bookingservice.repository

import com.example.bookingservice.domain.Booking
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BookingRepository : JpaRepository<Booking, UUID> {
    fun findByUserIdOrderByCreatedAtDesc(userId: String): List<Booking>
}
