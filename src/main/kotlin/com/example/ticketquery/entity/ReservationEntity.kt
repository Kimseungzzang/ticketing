package com.example.ticketquery.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class ReservationStatus { PENDING, CONFIRMED, CANCELLED }

@Entity
@Table(name = "reservations")
class ReservationEntity(
    @Id
    val id: String,

    @Column(name = "user_id", nullable = false)
    val userId: String,

    @Column(name = "seat_id", nullable = false)
    val seatId: String,

    // 이벤트 consumer가 갱신하는 projection이므로 가변(var).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ReservationStatus,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)
