package com.example.queueservice.service

import com.example.queueservice.dto.QueueStatusResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

@Service
class QueueService(
    private val redisTemplate: StringRedisTemplate,
    // slot(동시 입장 허용 수) — env QUEUE_MAX_ACTIVE_CAP로 조절(기본 1000). admit이 활성 수 < slot일 때만 입장시킨다.
    @Value("\${queue.max-active-cap:5}") private val maxActiveCap: Int,
    // admit 동시성 제어 토글 — Before/After 실측용. env QUEUE_ADMIT_GUARD.
    //   optimistic(기본): ZADD 선점 + 초과 롤백 → 다중 인스턴스 동시 admit에도 slot 초과 없음.
    //   naive           : 옛 방식 재현(활성 수 읽고→가용분 계산→배치 pop). 계산~반영 사이 race로
    //                     여러 인스턴스가 같은 가용분을 중복으로 보고 → slot 초과(oversell) 발생 가능.
    @Value("\${queue.admit-guard:optimistic}") private val admitGuard: String,
) {
    companion object {
        const val ENTRY_TOKEN_TTL_SEC = 300L
        // 입장 후 결제까지 허용 시간. 이 시간을 넘겨 이탈(release 없이 사라짐)한 유저의 활성 슬롯을 자동 회수한다.
        const val ADMIT_TTL_SEC = 300L
        private const val QUEUE_KEY_PREFIX = "queue:sorted:"

        fun queueKey(eventId: String)     = "$QUEUE_KEY_PREFIX$eventId"
        // 활성(입장) 유저 집합 — score = 입장 만료 시각(ms). ZCARD = 현재 활성 수(= slot 사용량).
        fun activeZKey(eventId: String)   = "queue:active:z:$eventId"
        fun entryTokenKey(userId: String) = "queue:entry:$userId"
        fun admittedKey(userId: String)   = "queue:admitted:$userId"
    }

    fun enter(userId: String, eventId: String): QueueStatusResponse {
        val existingToken = redisTemplate.opsForValue().get(entryTokenKey(userId))
        if (existingToken != null) {
            return QueueStatusResponse(
                status = "READY",
                position = 0,
                total = zcard(queueKey(eventId)),
                entryToken = existingToken,
            )
        }

        val existingRank = redisTemplate.opsForZSet().rank(queueKey(eventId), userId)
        if (existingRank != null) {
            return QueueStatusResponse(
                status = "WAITING",
                position = existingRank + 1,
                total = zcard(queueKey(eventId)),
                entryToken = null,
            )
        }

        val score = System.currentTimeMillis()
        redisTemplate.opsForZSet().add(queueKey(eventId), userId, score.toDouble())

        val position = (redisTemplate.opsForZSet().rank(queueKey(eventId), userId) ?: 0) + 1
        val total    = zcard(queueKey(eventId))

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
        val entryToken = redisTemplate.opsForValue().get(entryTokenKey(userId))
        if (entryToken != null) {
            return QueueStatusResponse(
                status = "READY",
                position = 0,
                total = zcard(queueKey(eventId)),
                entryToken = entryToken,
            )
        }

        val rank = redisTemplate.opsForZSet().rank(queueKey(eventId), userId)
            ?: return QueueStatusResponse(
                status = "NOT_IN_QUEUE",
                position = null,
                total = zcard(queueKey(eventId)),
                entryToken = null,
            )

        return QueueStatusResponse(
            status = "WAITING",
            position = rank + 1,
            total = zcard(queueKey(eventId)),
            entryToken = null,
        )
    }

    fun release(userId: String, eventId: String) {
        val admittedEventId = redisTemplate.opsForValue().get(admittedKey(userId)) ?: return
        redisTemplate.delete(admittedKey(userId))
        redisTemplate.delete(entryTokenKey(userId))
        // 활성 집합에서 제거 → slot 반납. (ZREM은 없는 멤버엔 no-op이라 double-release도 안전)
        redisTemplate.opsForZSet().remove(activeZKey(admittedEventId), userId)
        println("[QUEUE] release  userId=$userId  active=${zcard(activeZKey(admittedEventId))}")
    }

    fun validateEntryToken(userId: String, entryToken: String): Boolean {
        val stored = redisTemplate.opsForValue().get(entryTokenKey(userId))
        return stored != null && stored == entryToken
    }

    fun waitingEventIds(): Set<String> =
        (redisTemplate.keys("$QUEUE_KEY_PREFIX*") ?: emptySet())
            .map { key -> key.removePrefix(QUEUE_KEY_PREFIX) }
            .filter { eventId -> zcard(queueKey(eventId)) > 0 }
            .toSet()

    // 낙관적 동시성 제어(낙관적 락) + TTL 자동 정리.
    //   활성 유저를 sorted set(score = 입장 만료 시각)으로 관리한다. ZCARD가 현재 활성 수(= slot 사용량).
    //   (1) admit 전에 만료된(결제시간 초과·이탈) 활성 유저를 ZREMRANGEBYSCORE로 자동 회수 → stale 슬롯 방지.
    //   (2) ZPOPMIN으로 큐 맨 앞 한 명 확보 → activeZ에 ZADD로 '선점' → ZCARD가 cap 초과면 롤백(활성 제거 + 큐 복원).
    //   ZPOPMIN/ZADD/ZCARD/ZREM이 각각 원자적이라, 리더 선출 없이 여러 인스턴스가 동시에 admit해도 slot을 넘지 않는다.
    fun admitFromQueue(eventId: String) {
        val now = System.currentTimeMillis()
        // TTL 자동 정리(공통): 만료된(결제시간 초과·이탈) 활성 유저 회수 → stale 슬롯 방지
        redisTemplate.opsForZSet().removeRangeByScore(activeZKey(eventId), Double.NEGATIVE_INFINITY, now.toDouble())
        if (admitGuard == "naive") admitNaive(eventId, now) else admitOptimistic(eventId, now)
    }

    // [optimistic] ZPOPMIN 확보 → ZADD 선점 → ZCARD 초과 시 롤백(활성 제거 + 큐 복원).
    //   ZPOPMIN/ZADD/ZCARD/ZREM이 각각 원자적이라, 여러 인스턴스가 동시에 admit해도 slot을 넘지 않는다.
    private fun admitOptimistic(eventId: String, now: Long) {
        while (true) {
            val popped = redisTemplate.opsForZSet().popMin(queueKey(eventId), 1)?.firstOrNull() ?: return
            val userId = popped.value ?: return
            val origScore = popped.score
            val expireAt = (now + ADMIT_TTL_SEC * 1000).toDouble()
            redisTemplate.opsForZSet().add(activeZKey(eventId), userId, expireAt)
            val active = zcard(activeZKey(eventId))
            if (active > maxActiveCap) {
                redisTemplate.opsForZSet().remove(activeZKey(eventId), userId)          // 초과 → 롤백
                if (origScore != null) redisTemplate.opsForZSet().add(queueKey(eventId), userId, origScore)
                return
            }
            issueToken(eventId, userId, active)
        }
    }

    // [naive] 옛 방식 재현: 활성 수를 먼저 읽어 '가용분'을 계산 → 그만큼 배치 pop → 초과 검사 없이 등록.
    //   계산(활성 수 조회)과 반영(pop+등록) 사이에 다른 인스턴스가 동시에 같은 가용분을 보면 둘 다 pop해
    //   slot을 초과(oversell)한다. Before/After 실측의 Before용 — 절대 운영에 쓰지 말 것.
    private fun admitNaive(eventId: String, now: Long) {
        val active = zcard(activeZKey(eventId))
        val slotsAvailable = (maxActiveCap - active).toInt()
        if (slotsAvailable <= 0) return
        val popped = redisTemplate.opsForZSet().popMin(queueKey(eventId), slotsAvailable.toLong()) ?: return
        val expireAt = (now + ADMIT_TTL_SEC * 1000).toDouble()
        for (t in popped) {
            val userId = t.value ?: continue
            redisTemplate.opsForZSet().add(activeZKey(eventId), userId, expireAt)       // 초과 검사 없음 → race 시 oversell
            issueToken(eventId, userId, zcard(activeZKey(eventId)))
        }
    }

    private fun issueToken(eventId: String, userId: String, active: Long) {
        val token = UUID.randomUUID().toString()
        redisTemplate.opsForValue().set(entryTokenKey(userId), token, Duration.ofSeconds(ENTRY_TOKEN_TTL_SEC))
        redisTemplate.opsForValue().set(admittedKey(userId), eventId, Duration.ofSeconds(ADMIT_TTL_SEC))
        println("[QUEUE] admitted  userId=$userId  token=$token  active=$active/$maxActiveCap  guard=$admitGuard")
    }

    private fun zcard(key: String): Long = redisTemplate.opsForZSet().zCard(key) ?: 0L
}
