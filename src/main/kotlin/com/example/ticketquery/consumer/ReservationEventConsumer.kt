package com.example.ticketquery.consumer

import com.example.mykafka.client.MyKafkaConsumer
import com.example.ticketquery.config.MyKafkaConfig
import com.example.ticketquery.event.ReservationEvent
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.atomic.AtomicBoolean

// MyKafka의 reservation-events를 폴링해 read model을 갱신하는 백그라운드 consumer.
//
// 동작
//   - 단일 데몬 스레드 1개가 파티션 0..N-1을 순회.
//   - 각 파티션: 마지막 commit offset 조회 → fetch → 이벤트 적용 → commit(nextOffset).
//   - commit은 "처리 성공 후"에만 → at-least-once (ReadModelUpdater가 멱등).
//
// 단일 소켓 consumer는 thread-safe하지 않지만, 이 스레드 하나만 사용하므로 안전하다.
// mykafka.consumer.enabled=false 면 폴링 스레드 자체가 안 뜬다(비분리 벤치마크).
@Component
@ConditionalOnProperty(name = ["mykafka.consumer.enabled"], havingValue = "true", matchIfMissing = true)
class ReservationEventConsumer(
    private val consumer: MyKafkaConsumer,
    private val updater: ReadModelUpdater,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @PostConstruct
    fun start() {
        running.set(true)
        thread = Thread(::loop, "reservation-event-consumer").apply {
            isDaemon = true
            start()
        }
        log.info("reservation-event-consumer started (group={}, topic={})", MyKafkaConfig.GROUP, MyKafkaConfig.TOPIC)
    }

    private fun loop() {
        while (running.get()) {
            var processedAny = false
            for (partition in 0 until MyKafkaConfig.PARTITIONS) {
                try {
                    val committed = consumer.fetchOffset(MyKafkaConfig.TOPIC, partition)
                    val start = if (committed < 0) 0L else committed
                    val result = consumer.fetch(MyKafkaConfig.TOPIC, partition, start)
                    if (result.records.isEmpty()) continue

                    for (record in result.records) {
                        val event = objectMapper.readValue(record.value, ReservationEvent::class.java)
                        updater.apply(event)
                    }
                    // 배치 전체 적용에 성공한 뒤에만 commit. 중간 실패 시 commit 생략 → 다음 라운드 재처리(멱등).
                    consumer.commitOffset(MyKafkaConfig.TOPIC, partition, result.nextOffset)
                    log.info("applied {} event(s) on partition {} → committed offset {}",
                        result.records.size, partition, result.nextOffset)
                    processedAny = true
                } catch (e: Exception) {
                    // 토픽 미존재(아직 producer가 안 만듦)·일시적 네트워크 오류 등 → 다음 라운드 재시도.
                    log.warn("poll failed on partition {}: {}", partition, e.message)
                }
            }
            if (!processedAny) {
                try {
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    @PreDestroy
    fun stop() {
        running.set(false)
        thread?.interrupt()
        runCatching { thread?.join(2000) }
        log.info("reservation-event-consumer stopped")
    }
}
