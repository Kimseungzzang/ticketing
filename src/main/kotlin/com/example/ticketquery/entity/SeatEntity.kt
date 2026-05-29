package com.example.ticketquery.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version

enum class SeatStatus { AVAILABLE, RESERVED, SOLD }

@Entity
@Table(name = "seats")
class SeatEntity(
    @Id
    val id: String,

    @Column(name = "section_id", nullable = false)
    val sectionId: String,

    @Column(name = "row_label", nullable = false)
    val rowLabel: String,

    @Column(name = "seat_number", nullable = false)
    val seatNumber: Int,

    // 이벤트 consumer가 갱신하는 projection이므로 가변(var).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: SeatStatus,

    // command의 seats 스키마(@Version 낙관적 락)와 컬럼을 맞춘다.
    // 공유 seed(mock-data.sql)가 version을 넣으므로 read DB에도 컬럼이 있어야 한다.
    // consumer는 단일 writer라 락 충돌은 없지만, 스키마 일치를 위해 둔다.
    @Version
    @Column(nullable = false)
    var version: Long = 0,
)
