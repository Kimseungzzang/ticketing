package com.example.queueservice.service

import com.example.myredisclient.MyRedisTemplate
import com.example.queueservice.dto.QueueStatusResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class QueueService(
    private val myRedisTemplate: MyRedisTemplate,
    // slot(동시 입장 허용 수) — env QUEUE_maxActiveCap로 조절(기본 5). 늘리면 admit이 한 번에 더 많이 빼가 큐가 빨리 빠진다.
    @Value("\${queue.max-active-cap:5}") private val maxActiveCap: Int,
) {
    companion object {
        const val ENTRY_TOKEN_TTL_SEC = 300L
        private const val QUEUE_KEY_PREFIX = "queue:sorted:"

        fun queueKey(eventId: String)       = "$QUEUE_KEY_PREFIX$eventId"
        fun activeCountKey(eventId: String) = "queue:active:count:$eventId"
        fun entryTokenKey(userId: String)   = "queue:entry:$userId"
        fun admittedKey(userId: String)     = "queue:admitted:$userId"
    }

    fun enter(userId: String, eventId: String): QueueStatusResponse {
        val existingToken = myRedisTemplate.getKey(entryTokenKey(userId))
        if (existingToken != null) {
            return QueueStatusResponse(
                status = "READY",
                position = 0,
                total = myRedisTemplate.zcard(queueKey(eventId)),
                entryToken = existingToken,
            )
        }

        val existingRank = myRedisTemplate.zrank(queueKey(eventId), userId)
        if (existingRank != null) {
            return QueueStatusResponse(
                status = "WAITING",
                position = existingRank + 1,
                total = myRedisTemplate.zcard(queueKey(eventId)),
                entryToken = null,
            )
        }

        val score = System.currentTimeMillis()
        myRedisTemplate.zadd(queueKey(eventId), score, userId)

        val position = (myRedisTemplate.zrank(queueKey(eventId), userId) ?: 0) + 1
        val total    = myRedisTemplate.zcard(queueKey(eventId))

        // 새 대기열 진입만 로그(이미 있는 유저의 재요청은 위에서 early-return되어 조용).
        //   ※ status(폴링)엔 로그가 없다 — 켜면 부하테스트 때 초당 수천 줄로 폭증하므로.
        println("[QUEUE] enter  userId=$userId  eventId=$eventId  position=$position/$total")

        return QueueStatusResponse(
            status = "WAITING",
            position = position,
            total = total,
            entryToken = null,
        )
    }

    fun status(userId: String, eventId: String): QueueStatusResponse {
        val entryToken = myRedisTemplate.getKey(entryTokenKey(userId))
        if (entryToken != null) {
            return QueueStatusResponse(
                status = "READY",
                position = 0,
                total = myRedisTemplate.zcard(queueKey(eventId)),
                entryToken = entryToken,
            )
        }

        val rank = myRedisTemplate.zrank(queueKey(eventId), userId)
            ?: return QueueStatusResponse(
                status = "NOT_IN_QUEUE",
                position = null,
                total = myRedisTemplate.zcard(queueKey(eventId)),
                entryToken = null,
            )

        return QueueStatusResponse(
            status = "WAITING",
            position = rank + 1,
            total = myRedisTemplate.zcard(queueKey(eventId)),
            entryToken = null,
        )
    }

    fun release(userId: String, eventId: String) {
        val admittedEventId = myRedisTemplate.getKey(admittedKey(userId)) ?: return
        myRedisTemplate.delKey(admittedKey(userId))
        myRedisTemplate.delKey(entryTokenKey(userId))
        // Guard against negative count from double-release races
        val current = myRedisTemplate.getKey(activeCountKey(admittedEventId))?.toLongOrNull() ?: 0L
        if (current > 0) {
            val newCount = myRedisTemplate.decrKey(activeCountKey(admittedEventId))
            println("[QUEUE] release  userId=$userId  activeCount=$newCount")
        }
    }

    fun validateEntryToken(userId: String, entryToken: String): Boolean {
        val stored = myRedisTemplate.getKey(entryTokenKey(userId))
        return stored != null && stored == entryToken
    }

    fun waitingEventIds(): Set<String> =
        myRedisTemplate.keysAll()
            .mapNotNull { key ->
                if (key.startsWith(QUEUE_KEY_PREFIX)) key.removePrefix(QUEUE_KEY_PREFIX) else null
            }
            .filter { eventId -> myRedisTemplate.zcard(queueKey(eventId)) > 0 }
            .toSet()

    // 낙관적 동시성 제어(낙관적 락) — 리더 선출(분산락) 없이 여러 인스턴스가 동시에 admit해도 안전하다.
    //   slot 하나를 activeCount INCR로 '먼저' 원자적 선점 → cap 초과면 즉시 DECR 롤백.
    //   INCR/DECR/ZPOPMIN이 각각 원자적이라, 동시에 돌아도 cap을 넘겨 입장시키지 않는다.
    //   (기존 비관적 배치: slotsAvailable 계산 후 zpopmin(N) — 계산~반영 사이 race로 초과 가능했음)
    fun admitFromQueue(eventId: String) {
        while (true) {
            // 1) slot 하나를 낙관적으로 선점 (원자적 INCR)
            val newCount = myRedisTemplate.incrKey(activeCountKey(eventId))
            if (newCount > maxActiveCap) {
                myRedisTemplate.decrKey(activeCountKey(eventId))   // slot 초과 → 선점 롤백, 종료
                return
            }
            // 2) 대기열 맨 앞 한 명을 뺀다 (원자적 ZPOPMIN)
            val userId = myRedisTemplate.zpopmin(queueKey(eventId), 1).firstOrNull()
            if (userId == null) {
                myRedisTemplate.decrKey(activeCountKey(eventId))   // 큐가 비었음 → 선점 롤백, 종료
                return
            }
            // 3) 입장 확정: 토큰 발급 + admitted 표시
            val token = UUID.randomUUID().toString()
            myRedisTemplate.setKey(entryTokenKey(userId), token, ENTRY_TOKEN_TTL_SEC)
            myRedisTemplate.setKey(admittedKey(userId), eventId, -1)
            println("[QUEUE] admitted  userId=$userId  token=$token  activeCount=$newCount")
        }
    }
}
