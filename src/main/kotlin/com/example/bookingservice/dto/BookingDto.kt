package com.example.bookingservice.dto

import com.example.bookingservice.domain.Booking

data class BookingCreateRequest(
    val eventId: String,
    val seatId: String,
    val entryToken: String,
)

data class BookingResponse(
    val id: String,
    val userId: String,
    val eventId: String,
    val seatId: String,
    val status: String,
    val createdAt: String,
) {
    companion object {
        fun from(booking: Booking): BookingResponse = BookingResponse(
            id = booking.id.toString(),
            userId = booking.userId,
            eventId = booking.eventId,
            seatId = booking.seatId,
            status = booking.status.name,
            createdAt = booking.createdAt.toString(),
        )
    }
}

data class ErrorResponse(val message: String)
