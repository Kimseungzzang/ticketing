package com.example.bookingservice.dto

import com.example.bookingservice.domain.Seat
import com.example.bookingservice.domain.SeatStatus

data class SeatResponse(
    val seatId: String,
    val row: String,
    val number: Int,
    val status: String,
) {
    companion object {
        fun from(seat: Seat) = SeatResponse(
            seatId = seat.seatId,
            row = seat.row,
            number = seat.number,
            status = if (seat.status == SeatStatus.AVAILABLE) "available" else "taken",
        )
    }
}

data class SeatSectionResponse(
    val sectionId: String,
    val sectionName: String,
    val price: Int,
    val seats: List<SeatResponse>,
)
