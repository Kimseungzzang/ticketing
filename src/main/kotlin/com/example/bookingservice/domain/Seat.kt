package com.example.bookingservice.domain

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table

enum class SeatStatus { AVAILABLE, TAKEN }

@Entity
@Table(name = "seats")
class Seat(
    @Id
    val id: String,           // "EVT2026-001:S-A-1"
    val eventId: String,
    val seatId: String,       // "S-A-1"
    val sectionId: String,    // "S"
    val sectionName: String,  // "S석"
    val row: String,
    val number: Int,
    val price: Int,
    @Enumerated(EnumType.STRING)
    var status: SeatStatus = SeatStatus.AVAILABLE,
)
