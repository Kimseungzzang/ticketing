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

    fun admitFromQueue(eventId: String) {
        val activeCount    = myRedisTemplate.getKey(activeCountKey(eventId))?.toLongOrNull() ?: 0L
        val slotsAvailable = (maxActiveCap - activeCount).toInt()

        if (slotsAvailable <= 0) {
            println("[QUEUE] scheduler  eventId=$eventId  active=$activeCount/$maxActiveCap  no slots")
            return
        }

        val admitted = myRedisTemplate.zpopmin(queueKey(eventId), slotsAvailable)
        if (admitted.isEmpty()) {
            println("[QUEUE] scheduler  eventId=$eventId  active=$activeCount/$maxActiveCap  queue empty")
            return
        }

        for (userId in admitted) {
            val alreadyHasSlot = myRedisTemplate.getKey(admittedKey(userId)) != null
            val token = UUID.randomUUID().toString()
            myRedisTemplate.setKey(entryTokenKey(userId), token, ENTRY_TOKEN_TTL_SEC)
            myRedisTemplate.setKey(admittedKey(userId), eventId, -1)
            if (alreadyHasSlot) {
                println("[QUEUE] re-admitted  userId=$userId  token=$token  (slot reused)")
            } else {
                val newCount = myRedisTemplate.incrKey(activeCountKey(eventId))
                println("[QUEUE] admitted  userId=$userId  token=$token  activeCount=$newCount")
            }
        }
    }
}
