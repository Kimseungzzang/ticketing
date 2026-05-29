package com.example.ticketcommand.service

import com.example.ticketcommand.entity.OutboxEvent
import com.example.ticketcommand.entity.ReservationEntity
import com.example.ticketcommand.entity.ReservationStatus
import com.example.ticketcommand.entity.SeatStatus
import com.example.ticketcommand.event.ReservationEvent
import com.example.ticketcommand.event.ReservationEventType
import com.example.ticketcommand.repository.OutboxRepository
import com.example.ticketcommand.repository.ReservationRepository
import com.example.ticketcommand.repository.SeatRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

class SeatNotAvailableException(message: String) : RuntimeException(message)
class ReservationNotFoundException(message: String) : RuntimeException(message)
class InvalidReservationStateException(message: String) : RuntimeException(message)

@Service
class ReservationService(
    private val seatRepository: SeatRepository,
    private val reservationRepository: ReservationRepository,
    // 이벤트는 비즈니스 데이터와 같은 트랜잭션에서 outbox 테이블에 저장한다(transactional outbox).
    // 실제 MyKafka 발행은 OutboxRelay가 커밋된 행을 폴링해 처리 → 커밋되면 유실 없음.
    private val outboxRepository: OutboxRepository,
    private val objectMapper: ObjectMapper,
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

    // 이벤트들을 outbox 행으로 변환해 **현재 트랜잭션 안에서** 저장한다.
    // 비즈니스 데이터(seat/reservation)와 한 커밋으로 묶이므로, 커밋되면 이벤트는 절대 유실되지 않는다.
    private fun publish(events: List<ReservationEvent>) {
        if (events.isEmpty()) return
        val rows = events.map {
            OutboxEvent(seatId = it.seatId, payload = objectMapper.writeValueAsString(it))
        }
        outboxRepository.saveAll(rows)
    }
}
