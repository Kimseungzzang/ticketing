package com.example.bookingservice.service

import com.example.bookingservice.domain.SeatStatus
import com.example.bookingservice.dto.BookingCreateRequest
import com.example.bookingservice.dto.BookingResponse
import com.example.bookingservice.exception.BookingNotFoundException
import com.example.bookingservice.exception.InvalidEntryTokenException
import com.example.bookingservice.exception.SeatAlreadyTakenException
import com.example.bookingservice.repository.SeatRepository
import com.example.mykafka.client.MyKafkaProducer
import com.example.myredisclient.MyRedisTemplate
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class BookingService(
    private val seatRepository: SeatRepository,
    private val myRedisTemplate: MyRedisTemplate,
    private val myKafkaProducer: MyKafkaProducer,
    @Value("\${booking.seat.lock-ttl-sec}") private val seatLockTtlSec: Long,
    @Value("\${booking.kafka.topic}") private val kafkaTopic: String,
) {
    companion object {
        fun entryTokenKey(userId: String) = "queue:entry:$userId"
        fun seatsTotalKey(eventId: String) = "booking:seats:total:$eventId"
        fun seatsRemainingKey(eventId: String) = "booking:seats:remaining:$eventId"
        fun seatLockKey(eventId: String, seatId: String) = "booking:lock:$eventId:$seatId"
        fun pendingBookingKey(bookingId: UUID) = "booking:pending:$bookingId"
    }

    fun create(userId: String, request: BookingCreateRequest): BookingResponse {
        val storedToken = myRedisTemplate.getKey(entryTokenKey(userId))
        if (storedToken == null || storedToken != request.entryToken) {
            throw InvalidEntryTokenException()
        }

        val lockKey = seatLockKey(request.eventId, request.seatId)
        val locked = myRedisTemplate.setNx(lockKey, userId, seatLockTtlSec)
        if (!locked) {
            throw SeatAlreadyTakenException()
        }

        val seat = seatRepository.findById("${request.eventId}:${request.seatId}").orElse(null)
        if (seat == null || seat.status == SeatStatus.TAKEN) {
            myRedisTemplate.delKey(lockKey)
            throw SeatAlreadyTakenException()
        }

        val bookingId = UUID.randomUUID()
        myRedisTemplate.setKey(
            pendingBookingKey(bookingId),
            "${userId}|${request.eventId}|${request.seatId}",
            seatLockTtlSec,
        )
        myRedisTemplate.decrKey(seatsRemainingKey(request.eventId))

        return BookingResponse(
            id = bookingId.toString(),
            userId = userId,
            eventId = request.eventId,
            seatId = request.seatId,
            status = "PENDING",
            createdAt = LocalDateTime.now().toString(),
        )
    }

    // rollbackFor: MyKafka 발행 실패(SocketException=checked)도 롤백해 dual-write 방지.
    //   기본 @Transactional은 unchecked만 롤백 → seat TAKEN만 커밋되고 이벤트 유실되던 버그 수정(RUN_LOG §19.3②).
    @Transactional(rollbackFor = [Exception::class])
    fun confirm(bookingId: UUID): BookingResponse {
        val pending = myRedisTemplate.getKey(pendingBookingKey(bookingId))
            ?: throw BookingNotFoundException()

        val (userId, eventId, seatId) = pending.split("|")

        seatRepository.findById("$eventId:$seatId").ifPresent {
            it.status = SeatStatus.TAKEN
            seatRepository.save(it)
        }

        // synchronized: MyKafkaProducer는 단일 소켓이라 thread-safe하지 않다. 동시 confirm이
        //   같은 소켓에 동시 write하면 Broken pipe로 깨지던 버그 수정(RUN_LOG §19.3①).
        //   ※ 발행을 직렬화하므로 처리량 천장이 낮다 — 근본 해법은 ticket-command식 ProducerPool/Outbox.
        synchronized(myKafkaProducer) {
            myKafkaProducer.produce(
                kafkaTopic,
                key = seatId,
                value = """{"bookingId":"$bookingId","userId":"$userId","eventId":"$eventId","seatId":"$seatId","status":"CONFIRMED"}""",
            )
        }

        myRedisTemplate.delKey(pendingBookingKey(bookingId))

        return BookingResponse(
            id = bookingId.toString(),
            userId = userId,
            eventId = eventId,
            seatId = seatId,
            status = "CONFIRMED",
            createdAt = LocalDateTime.now().toString(),
        )
    }

    // 결제 실패 시 호출 — Redis 카운터 복원, 잠금 해제
    fun cancel(bookingId: UUID) {
        val pending = myRedisTemplate.getKey(pendingBookingKey(bookingId)) ?: return
        val (_, eventId, seatId) = pending.split("|")
        myRedisTemplate.incrKey(seatsRemainingKey(eventId))
        myRedisTemplate.delKey(seatLockKey(eventId, seatId))
        myRedisTemplate.delKey(pendingBookingKey(bookingId))
    }
}
