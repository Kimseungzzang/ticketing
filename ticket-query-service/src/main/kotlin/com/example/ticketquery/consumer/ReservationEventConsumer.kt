package com.example.ticketquery.consumer

import com.example.mykafka.client.MyKafkaConsumer
import com.example.ticketquery.config.MyKafkaConfig
import com.example.ticketquery.event.BookingEvent
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
//   - 시작 시 group에 joinGroup → broker가 담당 파티션 할당. 주기적으로 재join(=heartbeat/lease 갱신).
//   - 단일 데몬 스레드 1개가 **할당받은 파티션만** 순회.
//   - 각 파티션: 마지막 commit offset 조회 → fetch → 이벤트 적용 → commit(nextOffset).
//   - commit은 "처리 성공 후"에만 → at-least-once (ReadModelUpdater가 멱등).
//
// 단일 인스턴스면 전 파티션을 혼자 할당받아 기존과 동일하게 동작.
// 같은 group의 인스턴스를 2대 이상 띄우면 broker가 파티션을 겹치지 않게 분배한다.
//
// 단일 소켓 consumer는 thread-safe하지 않지만, 이 스레드 하나만 join/fetch/commit를 순차 호출하므로 안전.
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

    private val sessionTimeoutMs = 10_000
    private val rejoinIntervalMs = sessionTimeoutMs / 3L // lease가 만료되기 전에 갱신
    private var assigned: List<Int> = emptyList()
    private var generation = -1
    private var lastJoinMs = 0L
    private var needsReconnect = false // 소켓 오류 발생 시 다음 루프에서 재연결

    @PostConstruct
    fun start() {
        running.set(true)
        thread = Thread(::loop, "reservation-event-consumer").apply {
            isDaemon = true
            start()
        }
        log.info("reservation-event-consumer started (group={}, topic={})", MyKafkaConfig.GROUP, MyKafkaConfig.TOPIC)
    }

    // 주기적으로 group에 (재)가입해 lease를 갱신하고 할당 파티션을 최신화한다.
    private fun maybeRejoin() {
        val now = System.currentTimeMillis()
        if (now - lastJoinMs < rejoinIntervalMs) return
        val a = consumer.joinGroup(MyKafkaConfig.TOPIC, sessionTimeoutMs)
        lastJoinMs = now
        if (a.generation != generation || a.partitions != assigned) {
            log.info("group rebalance: member={} gen {}→{}, partitions {}→{} (전체 {})",
                a.memberId, generation, a.generation, assigned, a.partitions, a.partitionCount)
            generation = a.generation
            assigned = a.partitions
        }
    }

    private fun loop() {
        while (running.get()) {
            // 직전 라운드에 소켓 오류가 났으면 먼저 재연결(broker 재시작/소켓 깨짐/wire desync 자가복구).
            if (needsReconnect) {
                try {
                    consumer.reconnect()
                    needsReconnect = false
                    lastJoinMs = 0L          // 즉시 재join (broker 재시작 시 코디네이터 멤버십이 초기화됐을 수 있음)
                    assigned = emptyList()
                    log.info("consumer reconnected")
                } catch (e: Exception) {
                    log.warn("reconnect failed (will retry): {}", e.message)
                    sleepQuietly(500); continue
                }
            }

            var processedAny = false
            try {
                maybeRejoin()
            } catch (e: Exception) {
                log.warn("joinGroup failed: {}", e.message)
                needsReconnect = true // 소켓 오류일 수 있음 → 재연결로 자가복구 (topic 미존재면 재연결해도 무해)
            }
            for (partition in assigned) {
                try {
                    val committed = consumer.fetchOffset(MyKafkaConfig.TOPIC, partition)
                    val start = if (committed < 0) 0L else committed
                    val result = consumer.fetch(MyKafkaConfig.TOPIC, partition, start)
                    if (result.records.isEmpty()) continue

                    for (record in result.records) {
                        val event = objectMapper.readValue(record.value, BookingEvent::class.java)
                        updater.apply(event)
                    }
                    // 배치 전체 적용에 성공한 뒤에만 commit. 중간 실패 시 commit 생략 → 다음 라운드 재처리(멱등).
                    consumer.commitOffset(MyKafkaConfig.TOPIC, partition, result.nextOffset)
                    log.info("applied {} event(s) on partition {} → committed offset {}",
                        result.records.size, partition, result.nextOffset)
                    processedAny = true
                } catch (e: Exception) {
                    // 소켓 오류면 다음 라운드에 재연결. (commit/fetch 도중 broker가 죽은 경우 등)
                    log.warn("poll failed on partition {}: {}", partition, e.message)
                    needsReconnect = true
                    break // 소켓이 의심되므로 이번 라운드 나머지 파티션은 건너뜀
                }
            }
            if (!processedAny && !sleepQuietly(500)) break
        }
    }

    // 지정 시간 sleep. 인터럽트되면 false 반환(루프 종료 신호).
    private fun sleepQuietly(ms: Long): Boolean = try {
        Thread.sleep(ms); true
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt(); false
    }

    @PreDestroy
    fun stop() {
        running.set(false)
        thread?.interrupt()
        runCatching { thread?.join(2000) }
        log.info("reservation-event-consumer stopped")
    }
}
