package com.example.ticketcommand.event

import java.time.Instant

// 좌석 상태 전이를 표현하는 도메인 이벤트.
//   - command 트랜잭션 내부에서 ApplicationEventPublisher로 발행되고,
//   - 커밋 후 ReservationEventPublisher가 JSON으로 직렬화해 MyKafka로 보낸다.
// 모든 필드는 원시값(불변)이라 커밋 후 엔티티가 detached 돼도 안전하다.
enum class ReservationEventType { SEAT_RESERVED, SEAT_SOLD, SEAT_RELEASED }

data class ReservationEvent(
    val type: ReservationEventType,
    val reservationId: String,
    val userId: String,
    val seatId: String,
    val reservationStatus: String, // PENDING | CONFIRMED | CANCELLED (read model이 그대로 반영)
    val createdAt: Instant,        // reservation 생성 시각 (read model row 재현용)
    val occurredAt: Instant,       // 이벤트 발생 시각
)

// 한 번의 비즈니스 작업(reserve N좌석 / confirm / cancel)이 만든 이벤트 묶음.
// 작업당 ApplicationEvent 1개만 발행 → 리스너가 파티션별로 묶어 produceBatch 한 번씩 보낸다.
data class ReservationEventBatch(val events: List<ReservationEvent>)
