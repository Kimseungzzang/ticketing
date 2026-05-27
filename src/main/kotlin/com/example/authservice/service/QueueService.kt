package com.example.authservice.service

import com.example.authservice.dto.QueueStatusResponse
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class QueueService(
    private val myRedisTemplate: MyRedisTemplate,
) {
    companion object {
        const val MAX_ACTIVE_CAP = 5          // 동시에 좌석 선택 가능한 최대 인원
        const val ENTRY_TOKEN_TTL_SEC = 300L  // 입장 토큰 유효 시간 (5분)

        fun queueKey(eventId: String)       = "queue:sorted:$eventId"
        fun activeCountKey(eventId: String) = "queue:active:count:$eventId"
        fun entryTokenKey(userId: String)   = "queue:entry:$userId"
        fun admittedKey(userId: String)     = "queue:admitted:$userId"  // 슬롯 점유 플래그 (TTL 없음)
    }

    /**
     * 대기열 진입.
     * - 이미 입장 토큰이 있으면 READY 반환
     * - 이미 대기열에 있으면 현재 순위 반환
     * - 처음이면 대기열에 추가 후 순위 반환
     */
    fun enter(userId: String, eventId: String): QueueStatusResponse {
        // 1. 이미 입장 토큰 있음 → 바로 입장 가능
        val existingToken = myRedisTemplate.getKey(entryTokenKey(userId))
        if (existingToken != null) {
            return QueueStatusResponse(
                status = "READY",
                position = 0,
                total = myRedisTemplate.zcard(queueKey(eventId)),
                entryToken = existingToken,
            )
        }

        // 2. 이미 대기열에 있음 → 현재 순위 반환 (중복 진입 방지)
        val existingRank = myRedisTemplate.zrank(queueKey(eventId), userId)
        if (existingRank != null) {
            return QueueStatusResponse(
                status = "WAITING",
                position = existingRank + 1,
                total = myRedisTemplate.zcard(queueKey(eventId)),
                entryToken = null,
            )
        }

        // 3. 대기열에 추가 (score = 진입 시각)
        val score = System.currentTimeMillis()
        myRedisTemplate.zadd(queueKey(eventId), score, userId)

        val position = (myRedisTemplate.zrank(queueKey(eventId), userId) ?: 0) + 1
        val total    = myRedisTemplate.zcard(queueKey(eventId))

        return QueueStatusResponse(
            status = "WAITING",
            position = position,
            total = total,
            entryToken = null,
        )
    }

    /**
     * 현재 대기 상태 조회 (폴링용).
     * - 입장 토큰 있으면 READY
     * - 대기열에 있으면 WAITING + 순위
     * - 둘 다 없으면 NOT_IN_QUEUE
     */
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

    /**
     * 좌석 선택 완료 or 이탈 시 슬롯 반납.
     * - 토큰이 TTL 만료돼도 admitted 플래그로 슬롯 반납 가능
     * - admitted 플래그 없으면 이미 반납된 것 → 중복 DECR 방지
     */
    fun release(userId: String, eventId: String) {
        val admittedEventId = myRedisTemplate.getKey(admittedKey(userId)) ?: return
        myRedisTemplate.delKey(admittedKey(userId))
        myRedisTemplate.delKey(entryTokenKey(userId))  // 토큰이 이미 만료됐어도 no-op
        val newCount = myRedisTemplate.decrKey(activeCountKey(admittedEventId))
        println("[QUEUE] release  userId=$userId  activeCount=$newCount")
    }

    /**
     * /seats 진입 시 호출.
     * Redis에 살아있는 토큰인지 + 요청한 토큰과 일치하는지 확인.
     */
    fun validateEntryToken(userId: String, entryToken: String): Boolean {
        val stored = myRedisTemplate.getKey(entryTokenKey(userId))
        return stored != null && stored == entryToken
    }

    /**
     * 스케줄러에서 호출 — 빈 슬롯만큼 대기열 앞에서 입장 토큰 발급.
     */
    fun admitFromQueue(eventId: String) {
        val activeCount    = myRedisTemplate.getKey(activeCountKey(eventId))?.toLongOrNull() ?: 0L
        val slotsAvailable = (MAX_ACTIVE_CAP - activeCount).toInt()

        if (slotsAvailable <= 0) {
            println("[QUEUE] scheduler  eventId=$eventId  active=$activeCount/$MAX_ACTIVE_CAP  no slots")
            return
        }

        val admitted = myRedisTemplate.zpopmin(queueKey(eventId), slotsAvailable)
        if (admitted.isEmpty()) {
            println("[QUEUE] scheduler  eventId=$eventId  active=$activeCount/$MAX_ACTIVE_CAP  queue empty")
            return
        }

        for (userId in admitted) {
            val alreadyHasSlot = myRedisTemplate.getKey(admittedKey(userId)) != null
            val token = UUID.randomUUID().toString()
            myRedisTemplate.setKey(entryTokenKey(userId), token, ENTRY_TOKEN_TTL_SEC)
            myRedisTemplate.setKey(admittedKey(userId), eventId, -1)
            if (alreadyHasSlot) {
                // 이전 슬롯이 남아있음 (entry만 만료된 경우) → INCR 스킵
                println("[QUEUE] re-admitted  userId=$userId  token=$token  (slot reused)")
            } else {
                val newCount = myRedisTemplate.incrKey(activeCountKey(eventId))
                println("[QUEUE] admitted  userId=$userId  token=$token  activeCount=$newCount")
            }
        }
    }
}
