package com.example.ticketquery.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table

enum class SeatStatus { AVAILABLE, RESERVED, SOLD }

@Entity
@Table(name = "seats")
class SeatEntity(
    @Id
    val id: String,

    @Column(name = "section_id", nullable = false)
    val sectionId: String,

    @Column(name = "row_label", nullable = false)
    val rowLabel: String,

    @Column(name = "seat_number", nullable = false)
    val seatNumber: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: SeatStatus,
)
