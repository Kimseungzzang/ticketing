package com.example.ticketquery.event

import java.time.Instant

// command-service가 MyKafka로 발행한 이벤트의 역직렬화 대상.
// command 쪽 동일 이름 DTO와 필드가 일치해야 한다 (서비스 독립성을 위해 각자 정의).
enum class ReservationEventType { SEAT_RESERVED, SEAT_SOLD, SEAT_RELEASED }

data class ReservationEvent(
    val type: ReservationEventType,
    val reservationId: String,
    val userId: String,
    val seatId: String,
    val reservationStatus: String,
    val createdAt: Instant,
    val occurredAt: Instant,
)
