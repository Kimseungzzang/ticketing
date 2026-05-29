package com.example.ticketquery.consumer

import com.example.ticketquery.entity.ReservationEntity
import com.example.ticketquery.entity.ReservationStatus
import com.example.ticketquery.entity.SeatStatus
import com.example.ticketquery.event.ReservationEvent
import com.example.ticketquery.event.ReservationEventType
import com.example.ticketquery.repository.ReservationRepository
import com.example.ticketquery.repository.SeatRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

// 이벤트 1건을 read model(read DB)에 반영한다.
//
// 멱등(idempotent) 적용:
//   at-least-once 전달이라 같은 이벤트가 재처리될 수 있다(재시작/배치 재시도).
//   상태를 "고정값으로 set" 하므로 몇 번 적용해도 결과가 같다.
//
// 별도 빈인 이유: 폴링 스레드에서 self-invocation 하면 @Transactional 프록시가 안 걸린다.
//   ReservationEventConsumer가 이 빈을 주입받아 호출 → 프록시 경유 → 트랜잭션 적용.
@Component
class ReadModelUpdater(
    private val seatRepository: SeatRepository,
    private val reservationRepository: ReservationRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun apply(event: ReservationEvent) {
        when (event.type) {
            ReservationEventType.SEAT_RESERVED -> {
                setSeat(event.seatId, SeatStatus.RESERVED)
                upsertReservation(event, ReservationStatus.PENDING)
            }
            ReservationEventType.SEAT_SOLD -> {
                setSeat(event.seatId, SeatStatus.SOLD)
                upsertReservation(event, ReservationStatus.CONFIRMED)
            }
            ReservationEventType.SEAT_RELEASED -> {
                setSeat(event.seatId, SeatStatus.AVAILABLE)
                upsertReservation(event, ReservationStatus.CANCELLED)
            }
        }
    }

    private fun setSeat(seatId: String, status: SeatStatus) {
        val seat = seatRepository.findById(seatId).orElse(null)
        if (seat == null) {
            // read DB에 정적 좌석 시드가 없으면 발생. 학습 환경에선 mock-data.sql로 시드돼 있어야 함.
            log.warn("seat not found in read model: {} (read DB seeded?)", seatId)
            return
        }
        seat.status = status
        seatRepository.save(seat)
    }

    private fun upsertReservation(event: ReservationEvent, status: ReservationStatus) {
        val existing = reservationRepository.findById(event.reservationId).orElse(null)
        if (existing != null) {
            existing.status = status
            reservationRepository.save(existing)
        } else {
            reservationRepository.save(
                ReservationEntity(
                    id = event.reservationId,
                    userId = event.userId,
                    seatId = event.seatId,
                    status = status,
                    createdAt = event.createdAt,
                ),
            )
        }
    }
}
