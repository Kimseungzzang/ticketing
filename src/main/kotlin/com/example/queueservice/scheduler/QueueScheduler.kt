package com.example.queueservice.scheduler

import com.example.myredisclient.MyRedisTemplate
import com.example.queueservice.service.QueueService
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * admit 스케줄러 — queue-service 다중화 시 중복 admit을 막는다.
 *
 * [현재: 낙관적 락 방식]
 *   리더 선출 없이 모든 인스턴스가 admit을 시도한다. 정합성은 QueueService.admitFromQueue가
 *   activeCount를 INCR로 원자적 선점 + slot(cap) 초과 시 DECR 롤백하여 보장한다.
 *   → 리더 대기가 없어 여러 인스턴스가 병렬로 admit할 수 있다(처리량↑).
 *
 * [이전: 분산락(리더 선출) 방식 — 아래 주석 처리]
 *   setNx로 리더락을 잡은 한 인스턴스만 admit을 실행하던 비관적 방식.
 */
@Component
class QueueScheduler(
    private val queueService: QueueService,
    // [분산락용 — 낙관적 락 전환으로 미사용, 주석 처리]
    // private val myRedisTemplate: MyRedisTemplate,
    // @Value("\${queue.admit-interval-ms:2000}") private val admitIntervalMs: Long,
) {
    // [분산락(리더 선출)용 필드 — 주석 처리]
    // 이 프로세스의 고유 ID. 락을 '내가' 잡았는지 확인해 남의 락을 풀지 않으려 썼다.
    // private val instanceId = UUID.randomUUID().toString()
    // 락 TTL — 리더가 admit 도중 죽었을 때 다음 리더가 넘겨받기까지의 최대 공백(주기×3).
    // private val leaderTtlSec = maxOf(2L, admitIntervalMs * 3 / 1000)

    // admit 주기 — env QUEUE_ADMIT_INTERVAL_MS로 조절(기본 2000ms).
    @Scheduled(fixedDelayString = "\${queue.admit-interval-ms:2000}")
    fun admitUsers() {
        // ── [분산락(리더 선출) 방식 — 주석 처리] ──────────────────────────────
        //   락을 잡은 한 인스턴스만 통과, 나머지는 이번 주기 skip.
        // if (!myRedisTemplate.setNx(LEADER_KEY, instanceId, leaderTtlSec)) return
        // try {
        //     queueService.waitingEventIds().forEach { eventId ->
        //         queueService.admitFromQueue(eventId)
        //     }
        // } finally {
        //     // 내가 잡은 락일 때만 해제(내 TTL 만료 후 다른 인스턴스가 새로 잡은 락을 지우지 않도록).
        //     if (myRedisTemplate.getKey(LEADER_KEY) == instanceId) {
        //         myRedisTemplate.delKey(LEADER_KEY)
        //     }
        // }
        // ─────────────────────────────────────────────────────────────────────

        // [낙관적 락 방식] 리더 없이 모든 인스턴스가 admit을 시도한다.
        //   admitFromQueue가 INCR 선점 + 초과 롤백으로 slot 정합성을 보장하므로 동시 실행돼도 안전.
        queueService.waitingEventIds().forEach { eventId ->
            queueService.admitFromQueue(eventId)
        }
    }

    // [분산락(리더 선출)용 — 주석 처리]
    // companion object {
    //     private const val LEADER_KEY = "queue:admit:leader"
    // }
}
