package com.example.ticketcommand.repository

import com.example.ticketcommand.entity.OutboxEvent
import com.example.ticketcommand.entity.OutboxStatus
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface OutboxRepository : JpaRepository<OutboxEvent, Long> {
    // 아직 발행 안 된 이벤트를 id(=발행 순서) 오름차순으로. relay가 배치로 가져간다.
    fun findByStatusOrderByIdAsc(status: OutboxStatus, limit: Limit): List<OutboxEvent>

    // 발행 성공한 행들을 SENT로 일괄 표시 (자체 트랜잭션).
    @Modifying
    @Transactional
    @Query("update OutboxEvent o set o.status = com.example.ticketcommand.entity.OutboxStatus.SENT, o.sentAt = :now where o.id in :ids")
    fun markSent(@Param("ids") ids: List<Long>, @Param("now") now: Instant)
}
