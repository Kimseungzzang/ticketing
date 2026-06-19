package com.example.bookingservice.kafka

data class BookingCreatedEvent(
    val type: String = "BOOKING_CREATED",
    val bookingId: String,
    val userId: String,
    val eventId: String,
    val seatId: String,
    val occurredAt: Long = System.currentTimeMillis(),
)

data class BookingConfirmedEvent(
    val type: String = "BOOKING_CONFIRMED",
    val bookingId: String,
    val userId: String,
    val occurredAt: Long = System.currentTimeMillis(),
)

data class BookingCancelledEvent(
    val type: String = "BOOKING_CANCELLED",
    val bookingId: String,
    val userId: String,
    val occurredAt: Long = System.currentTimeMillis(),
)
