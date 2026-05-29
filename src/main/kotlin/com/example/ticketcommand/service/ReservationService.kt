package com.example.ticketcommand.service

import com.example.ticketcommand.entity.ReservationEntity
import com.example.ticketcommand.entity.ReservationStatus
import com.example.ticketcommand.entity.SeatStatus
import com.example.ticketcommand.event.ReservationEvent
import com.example.ticketcommand.event.ReservationEventBatch
import com.example.ticketcommand.event.ReservationEventType
import com.example.ticketcommand.repository.ReservationRepository
import com.example.ticketcommand.repository.SeatRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

class SeatNotAvailableException(message: String) : RuntimeException(message)
class ReservationNotFoundException(message: String) : RuntimeException(message)
class InvalidReservationStateException(message: String) : RuntimeException(message)

@Service
class ReservationService(
    private val seatRepository: SeatRepository,
    private val reservationRepository: ReservationRepository,
    // 도메인 이벤트는 트랜잭션 내부에서 publish하고, 실제 MyKafka 발행은
    // ReservationEventPublisher가 AFTER_COMMIT 단계에서 처리한다.
    private val eventPublisher: ApplicationEventPublisher,
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
        val saved = reservationRepository.saveAll(reservations)
        // 좌석 N개 → 이벤트 N건을 한 배치로 발행 (리스너가 파티션별로 묶어 보냄)
        publish(saved.map { event(ReservationEventType.SEAT_RESERVED, it) })
        return saved
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
        val saved = reservationRepository.save(reservation)
        publish(listOf(event(ReservationEventType.SEAT_SOLD, saved)))
        return saved
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
        val saved = reservationRepository.save(reservation)
        publish(listOf(event(ReservationEventType.SEAT_RELEASED, saved)))
        return saved
    }

    private fun event(type: ReservationEventType, reservation: ReservationEntity) =
        ReservationEvent(
            type = type,
            reservationId = reservation.id,
            userId = reservation.userId,
            seatId = reservation.seatId,
            reservationStatus = reservation.status.name,
            createdAt = reservation.createdAt,
            occurredAt = Instant.now(),
        )

    // 작업당 ApplicationEvent 1개(배치)만 발행. 커밋 후 ReservationEventPublisher가 MyKafka로 보낸다.
    private fun publish(events: List<ReservationEvent>) {
        if (events.isNotEmpty()) eventPublisher.publishEvent(ReservationEventBatch(events))
    }
}
