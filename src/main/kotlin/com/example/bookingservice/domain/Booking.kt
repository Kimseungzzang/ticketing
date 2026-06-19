package com.example.bookingservice.domain

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "bookings")
class Booking(
    @Id
    val id: UUID = UUID.randomUUID(),
    val userId: String,
    val eventId: String,
    val seatId: String,
    @Enumerated(EnumType.STRING)
    var status: BookingStatus = BookingStatus.PENDING,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
