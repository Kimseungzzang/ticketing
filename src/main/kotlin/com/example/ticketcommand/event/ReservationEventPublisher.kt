package com.example.ticketcommand.event

import com.example.ticketcommand.config.MyKafkaConfig
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import tools.jackson.databind.ObjectMapper

// mykafka.publisher.enabled=false 면 이 리스너가 안 뜬다 → service의 publishEvent는 no-op이 되어
// command가 순수 쓰기 경로만 타게 된다(벤치마크 baseline).
@Component
@ConditionalOnProperty(name = ["mykafka.publisher.enabled"], havingValue = "true", matchIfMissing = true)
class ReservationEventPublisher(
    private val pool: MyKafkaProducerPool,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // AFTER_COMMIT: 트랜잭션이 실제로 커밋된 뒤에만 발행한다.
    //   → 롤백된 예약의 유령 이벤트가 절대 나가지 않는다.
    //
    // 발행 최적화(§11.4의 -23% 회복):
    //   ① pool에서 connection을 빌려 → 동시 요청들이 서로 다른 소켓으로 병렬 발행(직렬화 제거).
    //   ② 한 작업의 이벤트들을 **파티션별로 묶어 produceBatch** → 다좌석 reserve의 round-trip 최소화.
    //
    // 남은 한계(의도적): "커밋 성공 → 발행 실패"는 여전히 이벤트를 유실시킨다 (dual-write 문제).
    //   완전한 해결은 transactional outbox 패턴 — 다음 학습 단계로 남김.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(batch: ReservationEventBatch) {
        if (batch.events.isEmpty()) return

        // broker의 partitioner( Math.floorMod(key.contentHashCode(), N) )를 클라이언트에서 그대로 복제해
        // 좌석별 파티션 배정을 보존한 채로 파티션별 배치를 만든다.
        // 같은 좌석은 항상 같은 파티션 → reserve→confirm→cancel 순서 보장 유지.
        val byPartition = batch.events.groupBy { partitionFor(it.seatId) }

        pool.withProducer { producer ->
            for ((partition, events) in byPartition) {
                val records: List<Pair<ByteArray?, ByteArray>> = events.map { e ->
                    e.seatId.toByteArray() as ByteArray? to objectMapper.writeValueAsBytes(e)
                }
                val result = producer.produceBatch(MyKafkaConfig.TOPIC, records, partition)
                log.info(
                    "published {} record(s) → partition={} baseOffset={}",
                    result.count, result.partition, result.baseOffset,
                )
            }
        }
    }

    private fun partitionFor(seatId: String): Int =
        Math.floorMod(seatId.toByteArray().contentHashCode(), MyKafkaConfig.PARTITIONS)
}
