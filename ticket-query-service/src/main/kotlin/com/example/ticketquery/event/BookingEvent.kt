package com.example.ticketquery.event

// booking-service가 결제 확정(confirm) 시 MyKafka `booking-events` 토픽에 발행하는 이벤트.
// 포맷: {"bookingId","userId","eventId","seatId","status":"CONFIRMED"}
// ticket-query는 이걸 소비해 read model을 갱신한다(booking이 write 측, ticket-query가 read 측).
data class BookingEvent(
    val bookingId: String,
    val userId: String,
    val eventId: String,
    val seatId: String,
    val status: String, // CONFIRMED (booking은 확정 시에만 발행)
    val traceparent: String? = null, // 분산추적: booking produce span의 W3C traceparent (있으면 같은 trace로 이음)
)
