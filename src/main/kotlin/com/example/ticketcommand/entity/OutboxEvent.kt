package com.example.ticketcommand.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class OutboxStatus { PENDING, SENT }

// Transactional outbox: 발행할 이벤트를 비즈니스 데이터와 **같은 트랜잭션**으로 이 테이블에 커밋한다.
//   - 커밋되면 이벤트는 DB에 durable → 그 뒤 relay가 MyKafka로 발행 실패해도 행이 남아 재시도됨.
//   - "DB 커밋 성공 = 이벤트 유실 없음"을 보장 → dual-write 문제 해소.
// id는 BIGSERIAL(단조 증가) → relay가 id 순으로 발행해 (좌석별) 순서 보존.
@Entity
@Table(name = "outbox")
class OutboxEvent(
    @Column(name = "seat_id", nullable = false)
    val seatId: String, // 파티션 key (relay가 hash(seatId)로 파티션 결정)

    @Column(nullable = false, columnDefinition = "text")
    val payload: String, // 직렬화된 ReservationEvent(JSON). relay는 그대로 발행(재직렬화 X).

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OutboxStatus = OutboxStatus.PENDING,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "sent_at")
    var sentAt: Instant? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
)
