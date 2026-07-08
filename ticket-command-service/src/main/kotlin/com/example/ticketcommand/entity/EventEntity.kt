package com.example.ticketcommand.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "events")
class EventEntity(
    @Id
    val id: String,

    @Column(nullable = false)
    val title: String,

    @Column
    val subtitle: String? = null,

    @Column(nullable = false)
    val artist: String,

    @Column(nullable = false)
    val venue: String,

    @Column(name = "starts_at", nullable = false)
    val startsAt: Instant,

    @Column(name = "doors_open_at")
    val doorsOpenAt: Instant? = null,
)
