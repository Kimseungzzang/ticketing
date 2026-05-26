package com.example.ticketcommand.service

import com.example.ticketcommand.entity.ReservationEntity
import com.example.ticketcommand.entity.ReservationStatus
import com.example.ticketcommand.entity.SeatStatus
import com.example.ticketcommand.repository.ReservationRepository
import com.example.ticketcommand.repository.SeatRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

class SeatNotAvailableException(message: String) : RuntimeException(message)
class ReservationNotFoundException(message: String) : RuntimeException(message)
class InvalidReservationStateException(message: String) : RuntimeException(message)

@Service
class ReservationService(
    private val seatRepository: SeatRepository,
    private val reservationRepository: ReservationRepository,
) {
    @Transactional
    fun reserve(userId: String, seatIds: List<String>): List<ReservationEntity> {
        val seats = seatRepository.findAllByIdIn(seatIds)
        if (seats.size != seatIds.size) {
            throw SeatNotAvailableException("Some seats do not exist: requested=$seatIds, found=${seats.map { it.id }}")
        }
        val unavailable = seats.filter { it.status != SeatStatus.AVAILABLE }
        if (unavailable.isNotEmpty()) {
            throw SeatNotAvailableException("Seats not available: ${unavailable.map { it.id }}")
        }

        seats.forEach { it.status = SeatStatus.RESERVED }
        seatRepository.saveAll(seats)

        val reservations = seats.map {
            ReservationEntity(
                id = UUID.randomUUID().toString(),
                userId = userId,
                seatId = it.id,
                status = ReservationStatus.PENDING,
            )
        }
        return reservationRepository.saveAll(reservations)
    }

    @Transactional
    fun confirm(reservationId: String): ReservationEntity {
        val reservation = reservationRepository.findById(reservationId)
            .orElseThrow { ReservationNotFoundException("Reservation not found: $reservationId") }
        if (reservation.status != ReservationStatus.PENDING) {
            throw InvalidReservationStateException("Cannot confirm reservation in state: ${reservation.status}")
        }
        val seat = seatRepository.findById(reservation.seatId)
            .orElseThrow { SeatNotAvailableException("Seat not found: ${reservation.seatId}") }

        reservation.status = ReservationStatus.CONFIRMED
        seat.status = SeatStatus.SOLD
        seatRepository.save(seat)
        return reservationRepository.save(reservation)
    }

    @Transactional
    fun cancel(reservationId: String): ReservationEntity {
        val reservation = reservationRepository.findById(reservationId)
            .orElseThrow { ReservationNotFoundException("Reservation not found: $reservationId") }
        if (reservation.status == ReservationStatus.CANCELLED) {
            return reservation
        }
        val seat = seatRepository.findById(reservation.seatId)
            .orElseThrow { SeatNotAvailableException("Seat not found: ${reservation.seatId}") }

        reservation.status = ReservationStatus.CANCELLED
        seat.status = SeatStatus.AVAILABLE
        seatRepository.save(seat)
        return reservationRepository.save(reservation)
    }
}
