package com.example.ticketcommand.event

import com.example.ticketcommand.config.MyKafkaConfig
import com.example.ticketcommand.entity.OutboxStatus
import com.example.ticketcommand.repository.OutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.Limit
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

// Transactional outbox의 relay.
//   주기적으로 PENDING outbox 행을 id(=발행 순서)대로 읽어 MyKafka로 발행하고 SENT로 표시한다.
//
// 보장:
//   - 이벤트는 비즈니스 트랜잭션에서 이미 커밋됨 → relay가 죽거나 broker가 죽어도 행은 PENDING으로 남아
//     다음 폴링에 재시도된다. = at-least-once (consumer는 멱등).
//   - "커밋 성공 → 발행 실패 → 유실" (dual-write) 문제가 사라진다.
//
// 발행 실패 시: markSent를 안 하므로 다음 라운드에 같은 행을 다시 발행(중복 가능, consumer 멱등으로 흡수).
//
// mykafka.publisher.enabled=false 면 relay가 안 뜬다(벤치/오프라인 모드). 그땐 outbox에 쌓이기만 함.
@Component
@ConditionalOnProperty(name = ["mykafka.publisher.enabled"], havingValue = "true", matchIfMissing = true)
class OutboxRelay(
    private val outboxRepository: OutboxRepository,
    private val producerPool: MyKafkaProducerPool,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${mykafka.outbox.poll-ms:200}")
    fun relay() {
        val batch = outboxRepository.findByStatusOrderByIdAsc(OutboxStatus.PENDING, Limit.of(BATCH_SIZE))
        if (batch.isEmpty()) return

        // broker partitioner( floorMod(key.contentHashCode(), N) ) 복제 → 좌석별 파티션 보존.
        // id 순서를 유지한 채 파티션별로 묶어 produceBatch (groupBy는 입력 순서 보존).
        val byPartition = batch.groupBy { partitionFor(it.seatId) }
        try {
            producerPool.withProducer { producer ->
                for ((partition, rows) in byPartition) {
                    val records: List<Pair<ByteArray?, ByteArray>> = rows.map { row ->
                        row.seatId.toByteArray() as ByteArray? to row.payload.toByteArray()
                    }
                    producer.produceBatch(MyKafkaConfig.TOPIC, records, partition)
                }
            }
        } catch (e: Exception) {
            // 발행 실패(예: broker 다운) → SENT 표시 안 함 → 행 보존 → 다음 폴링에 재시도.
            log.warn("outbox relay publish failed (will retry): {}", e.message)
            return
        }
        outboxRepository.markSent(batch.map { it.id }, Instant.now())
        log.info("outbox relayed {} event(s) (ids {}..{})", batch.size, batch.first().id, batch.last().id)
    }

    private fun partitionFor(seatId: String): Int =
        Math.floorMod(seatId.toByteArray().contentHashCode(), MyKafkaConfig.PARTITIONS)

    companion object {
        // 병목 처방①(RUN_LOG §18.3): 500→2000. poll 1회당 더 많은 행을 발행해 poll 간격 오버헤드를 분산,
        //   relay 처리율 천장(≈2K/s)을 끌어올린다. poll-ms는 200→50으로(application.yml/OUTBOX_POLL_MS) 함께 낮춤.
        private const val BATCH_SIZE = 2000
    }
}
