package com.example.ticketquery.consumer

import com.example.ticketquery.entity.ReservationEntity
import com.example.ticketquery.entity.ReservationStatus
import com.example.ticketquery.entity.SeatStatus
import com.example.ticketquery.event.BookingEvent
import com.example.ticketquery.repository.ReservationRepository
import com.example.ticketquery.repository.SeatRepository
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

// booking-service의 `booking-events` 1건을 read model(ticket_read_db)에 반영한다.
//
// 멱등(idempotent): at-least-once라 같은 이벤트가 재처리될 수 있다. 상태를 "고정값으로 set" 하므로
//   몇 번 적용해도 결과가 같다.
// 별도 빈인 이유: 폴링 스레드 self-invocation은 @Transactional 프록시가 안 걸린다.
@Component
class ReadModelUpdater(
    private val seatRepository: SeatRepository,
    private val reservationRepository: ReservationRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tracer = GlobalOpenTelemetry.getTracer("ticket-query")

    @Transactional
    fun apply(event: BookingEvent) {
        // booking produce span을 remote parent로 삼아 consume span을 같은 trace에 잇는다.
        //   → Jaeger에서 confirm → 카프카 → 여기(consume + read DB UPDATE)가 한 waterfall로 관통.
        val span = tracer.spanBuilder("consume booking-events")
            .setParent(remoteContext(event.traceparent))
            .setSpanKind(SpanKind.CONSUMER)
            .startSpan()
        try {
            span.makeCurrent().use {
                when (event.status) {
                    // 결제 확정 → 좌석 판매 완료(SOLD), 예약 CONFIRMED
                    "CONFIRMED" -> {
                        setSeat(event.seatId, SeatStatus.SOLD)
                        upsertReservation(event, ReservationStatus.CONFIRMED)
                    }
                    // booking이 취소 이벤트를 발행하게 되면(현재는 Redis-only) 대비
                    "CANCELLED" -> {
                        setSeat(event.seatId, SeatStatus.AVAILABLE)
                        upsertReservation(event, ReservationStatus.CANCELLED)
                    }
                    else -> log.warn("unknown booking status: {} (seat={})", event.status, event.seatId)
                }
            }
        } finally {
            span.end()
        }
    }

    // W3C traceparent "00-<traceId>-<spanId>-<flags>" → remote parent Context (없으면 새 trace)
    private fun remoteContext(tp: String?): Context {
        if (tp.isNullOrBlank()) return Context.current()
        val p = tp.split("-")
        if (p.size < 4) return Context.current()
        return try {
            val sc = SpanContext.createFromRemoteParent(
                p[1], p[2], TraceFlags.fromByte(p[3].toInt(16).toByte()), TraceState.getDefault(),
            )
            Context.current().with(Span.wrap(sc))
        } catch (e: Exception) {
            log.warn("bad traceparent: {}", tp)
            Context.current()
        }
    }

    private fun setSeat(seatId: String, status: SeatStatus) {
        val seat = seatRepository.findById(seatId).orElse(null)
        if (seat == null) {
            log.warn("seat not found in read model: {} (read DB seeded?)", seatId)
            return
        }
        seat.status = status
        seatRepository.save(seat)
    }

    private fun upsertReservation(event: BookingEvent, status: ReservationStatus) {
        val existing = reservationRepository.findById(event.bookingId).orElse(null)
        if (existing != null) {
            existing.status = status
            reservationRepository.save(existing)
        } else {
            reservationRepository.save(
                ReservationEntity(
                    id = event.bookingId,
                    userId = event.userId,
                    seatId = event.seatId,
                    status = status,
                    createdAt = Instant.now(),
                ),
            )
        }
    }
}
