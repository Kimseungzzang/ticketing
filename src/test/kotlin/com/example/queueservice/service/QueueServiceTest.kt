package com.example.queueservice.service

import com.example.myredisclient.MyRedisTemplate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class QueueServiceTest {

    @Mock lateinit var myRedisTemplate: MyRedisTemplate
    @InjectMocks lateinit var queueService: QueueService

    private val userId = "user1"
    private val eventId = "EVT2026-001"

    // enter

    @Test
    fun `enter - 이미 입장 토큰 있으면 READY 반환`() {
        whenever(myRedisTemplate.getKey(QueueService.entryTokenKey(userId))).thenReturn("token123")
        whenever(myRedisTemplate.zcard(QueueService.queueKey(eventId))).thenReturn(10L)

        val result = queueService.enter(userId, eventId)

        assertThat(result.status).isEqualTo("READY")
        assertThat(result.entryToken).isEqualTo("token123")
    }

    @Test
    fun `enter - 이미 대기 중이면 WAITING 반환`() {
        whenever(myRedisTemplate.getKey(QueueService.entryTokenKey(userId))).thenReturn(null)
        whenever(myRedisTemplate.zrank(QueueService.queueKey(eventId), userId)).thenReturn(2L)
        whenever(myRedisTemplate.zcard(QueueService.queueKey(eventId))).thenReturn(10L)

        val result = queueService.enter(userId, eventId)

        assertThat(result.status).isEqualTo("WAITING")
        assertThat(result.position).isEqualTo(3L)
        verify(myRedisTemplate, never()).zadd(any(), any(), any())
    }

    @Test
    fun `enter - 신규 진입 시 대기열에 추가되고 WAITING 반환`() {
        whenever(myRedisTemplate.getKey(QueueService.entryTokenKey(userId))).thenReturn(null)
        whenever(myRedisTemplate.zrank(QueueService.queueKey(eventId), userId)).thenReturn(null, 0L)
        whenever(myRedisTemplate.zcard(QueueService.queueKey(eventId))).thenReturn(1L)

        val result = queueService.enter(userId, eventId)

        assertThat(result.status).isEqualTo("WAITING")
        verify(myRedisTemplate).zadd(eq(QueueService.queueKey(eventId)), any(), eq(userId))
    }

    // status

    @Test
    fun `status - 입장 토큰 있으면 READY 반환`() {
        whenever(myRedisTemplate.getKey(QueueService.entryTokenKey(userId))).thenReturn("token123")
        whenever(myRedisTemplate.zcard(QueueService.queueKey(eventId))).thenReturn(5L)

        val result = queueService.status(userId, eventId)

        assertThat(result.status).isEqualTo("READY")
        assertThat(result.entryToken).isEqualTo("token123")
    }

    @Test
    fun `status - 대기열에 없으면 NOT_IN_QUEUE 반환`() {
        whenever(myRedisTemplate.getKey(QueueService.entryTokenKey(userId))).thenReturn(null)
        whenever(myRedisTemplate.zrank(QueueService.queueKey(eventId), userId)).thenReturn(null)
        whenever(myRedisTemplate.zcard(QueueService.queueKey(eventId))).thenReturn(0L)

        val result = queueService.status(userId, eventId)

        assertThat(result.status).isEqualTo("NOT_IN_QUEUE")
    }

    // validateEntryToken

    @Test
    fun `validateEntryToken - 저장된 토큰과 일치하면 true`() {
        whenever(myRedisTemplate.getKey(QueueService.entryTokenKey(userId))).thenReturn("tok")

        assertThat(queueService.validateEntryToken(userId, "tok")).isTrue()
    }

    @Test
    fun `validateEntryToken - 저장된 토큰 없으면 false`() {
        whenever(myRedisTemplate.getKey(QueueService.entryTokenKey(userId))).thenReturn(null)

        assertThat(queueService.validateEntryToken(userId, "tok")).isFalse()
    }

    // waitingEventIds

    @Test
    fun `waitingEventIds - 대기자가 있는 이벤트만 반환`() {
        val waitingEventId = "EVT2026-002"
        whenever(myRedisTemplate.keysAll()).thenReturn(
            setOf(
                QueueService.queueKey(eventId),
                QueueService.queueKey(waitingEventId),
                QueueService.activeCountKey(eventId),
                "queue:entry:$userId",
            )
        )
        whenever(myRedisTemplate.zcard(QueueService.queueKey(eventId))).thenReturn(0L)
        whenever(myRedisTemplate.zcard(QueueService.queueKey(waitingEventId))).thenReturn(2L)

        val result = queueService.waitingEventIds()

        assertThat(result).containsExactly(waitingEventId)
    }

    // release

    @Test
    fun `release - admittedKey 없으면 아무 것도 안함`() {
        whenever(myRedisTemplate.getKey(QueueService.admittedKey(userId))).thenReturn(null)

        queueService.release(userId, eventId)

        verify(myRedisTemplate, never()).decrKey(any())
    }

    @Test
    fun `release - activeCount가 0이면 decrKey 호출하지 않음`() {
        whenever(myRedisTemplate.getKey(QueueService.admittedKey(userId))).thenReturn(eventId)
        whenever(myRedisTemplate.getKey(QueueService.activeCountKey(eventId))).thenReturn("0")

        queueService.release(userId, eventId)

        verify(myRedisTemplate, never()).decrKey(any())
    }

    @Test
    fun `release - 정상 반납 시 decrKey 호출`() {
        whenever(myRedisTemplate.getKey(QueueService.admittedKey(userId))).thenReturn(eventId)
        whenever(myRedisTemplate.getKey(QueueService.activeCountKey(eventId))).thenReturn("3")
        whenever(myRedisTemplate.decrKey(QueueService.activeCountKey(eventId))).thenReturn(2L)

        queueService.release(userId, eventId)

        verify(myRedisTemplate).decrKey(QueueService.activeCountKey(eventId))
    }
}
