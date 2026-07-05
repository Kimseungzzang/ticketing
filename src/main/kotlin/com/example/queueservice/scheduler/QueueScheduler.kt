package com.example.queueservice.scheduler

import com.example.myredisclient.MyRedisTemplate
import com.example.queueservice.service.QueueService
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * admit 스케줄러 — queue-service가 여러 대로 뜰 때 '단 한 대'만 admit을 돌리도록 리더 선출을 건다.
 *
 * 리더 선출 = Redis 분산락(SET NX EX). 매 주기 락을 잡은 인스턴스만 admit을 실행하고 끝나면 해제한다.
 * 여러 대가 동시에 admit하면 같은 유저를 중복 zpopmin 하거나 activeCount(incr/decr)가 어긋나므로,
 * "한 순간엔 한 대만 admit"을 보장해야 한다. SET NX는 원자적이라 두 인스턴스가 동시에 락을 잡을 수 없다.
 * 리더가 admit 도중 죽어도 TTL이 만료돼 다음 주기에 다른 인스턴스가 자동으로 이어받는다(단일 장애점 없음).
 */
@Component
class QueueScheduler(
    private val queueService: QueueService,
    private val myRedisTemplate: MyRedisTemplate,
    @Value("\${queue.admit-interval-ms:2000}") private val admitIntervalMs: Long,
) {
    // 이 프로세스의 고유 ID. 락을 '내가' 잡았는지 확인해 남이 새로 잡은 락을 실수로 풀지 않기 위해 쓴다.
    private val instanceId = UUID.randomUUID().toString()

    // 락 TTL — 리더가 admit 도중 죽었을 때 다음 리더가 넘겨받기까지의 최대 공백.
    // 주기보다 넉넉히(×3) 잡아 정상 흐름에선 만료되지 않게 하고, 프로세스가 죽었을 때의 안전망으로만 둔다.
    private val leaderTtlSec = maxOf(2L, admitIntervalMs * 3 / 1000)

    // admit 주기 — env QUEUE_ADMIT_INTERVAL_MS로 조절(기본 2000ms). 짧을수록 처리량↑(큐가 덜 쌓임).
    @Scheduled(fixedDelayString = "\${queue.admit-interval-ms:2000}")
    fun admitUsers() {
        // 리더 선출: 락을 잡은 한 대만 통과. 나머지 인스턴스는 이번 주기를 건너뛴다.
        if (!myRedisTemplate.setNx(LEADER_KEY, instanceId, leaderTtlSec)) return
        try {
            queueService.waitingEventIds().forEach { eventId ->
                queueService.admitFromQueue(eventId)
            }
        } finally {
            // 내가 잡은 락일 때만 해제(내 TTL이 만료된 뒤 다른 인스턴스가 새로 잡은 락을 지우지 않도록).
            // get→del 사이의 극단적 race는 TTL이 안전망이라 학습 범위에선 무시(완전 원자화는 Lua eval 필요).
            if (myRedisTemplate.getKey(LEADER_KEY) == instanceId) {
                myRedisTemplate.delKey(LEADER_KEY)
            }
        }
    }

    companion object {
        private const val LEADER_KEY = "queue:admit:leader"
    }
}
