package com.example.ticketquery.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "seat_sections")
class SeatSectionEntity(
    @Id
    val id: String,

    @Column(name = "event_id", nullable = false)
    val eventId: String,

    @Column(nullable = false)
    val name: String,

    @Column(name = "kor_name", nullable = false)
    val korName: String,

    @Column(nullable = false)
    val price: Int,

    @Column(nullable = false)
    val color: String,
)
