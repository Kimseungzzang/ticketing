package com.example.ticketcommand.dto

import com.example.ticketcommand.entity.ReservationEntity
import com.example.ticketcommand.entity.ReservationStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import java.time.Instant

data class CreateReservationRequest(
    @field:NotBlank val userId: String,
    @field:NotEmpty val seatIds: List<String>,
)

data class ReservationResponse(
    val id: String,
    val userId: String,
    val seatId: String,
    val status: ReservationStatus,
    val createdAt: Instant,
) {
    companion object {
        fun from(entity: ReservationEntity) = ReservationResponse(
            id = entity.id,
            userId = entity.userId,
            seatId = entity.seatId,
            status = entity.status,
            createdAt = entity.createdAt,
        )
    }
}
