package com.example.bookingservice.service

import com.example.bookingservice.domain.Booking
import com.example.bookingservice.domain.BookingStatus
import com.example.bookingservice.dto.BookingCreateRequest
import com.example.bookingservice.dto.BookingResponse
import com.example.bookingservice.exception.BookingAlreadyCancelledException
import com.example.bookingservice.exception.BookingNotFoundException
import com.example.bookingservice.exception.InvalidEntryTokenException
import com.example.bookingservice.exception.NoSeatsAvailableException
import com.example.bookingservice.exception.SeatAlreadyTakenException
import com.example.bookingservice.kafka.BookingEventPublisher
import com.example.bookingservice.repository.BookingRepository
import com.example.myredisclient.MyRedisTemplate
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class BookingService(
    private val bookingRepository: BookingRepository,
    private val myRedisTemplate: MyRedisTemplate,
    private val bookingEventPublisher: BookingEventPublisher,
    @Value("\${booking.seat.lock-ttl-sec}") private val seatLockTtlSec: Long,
) {
    companion object {
        fun entryTokenKey(userId: String) = "queue:entry:$userId"
        fun seatsRemainingKey(eventId: String) = "booking:seats:remaining:$eventId"
        fun seatLockKey(eventId: String, seatId: String) = "booking:lock:$eventId:$seatId"
    }

    @Transactional
    fun create(userId: String, request: BookingCreateRequest): BookingResponse {
        // 1. entryToken 검증
        val storedToken = myRedisTemplate.getKey(entryTokenKey(userId))
        if (storedToken == null || storedToken != request.entryToken) {
            throw InvalidEntryTokenException()
        }

        // 2. 좌석 잠금 (SET NX) — 중복 예약 방지
        val lockKey = seatLockKey(request.eventId, request.seatId)
        val locked = myRedisTemplate.setNx(lockKey, userId, seatLockTtlSec)
        if (!locked) {
            throw SeatAlreadyTakenException()
        }

        // 3. 잔여 좌석 DECR — 매진 체크
        val remaining = myRedisTemplate.decrKey(seatsRemainingKey(request.eventId))
        if (remaining < 0) {
            myRedisTemplate.incrKey(seatsRemainingKey(request.eventId))
            myRedisTemplate.delKey(lockKey)
            throw NoSeatsAvailableException()
        }

        // 4. 예약 저장 (PENDING)
        val booking = Booking(
            userId = userId,
            eventId = request.eventId,
            seatId = request.seatId,
        )
        bookingRepository.save(booking)

        // 5. Kafka 이벤트 발행
        bookingEventPublisher.publishCreated(booking)

        return BookingResponse.from(booking)
    }

    @Transactional(readOnly = true)
    fun get(userId: String, bookingId: UUID): BookingResponse {
        val booking = bookingRepository.findById(bookingId)
            .filter { it.userId == userId }
            .orElseThrow { BookingNotFoundException() }
        return BookingResponse.from(booking)
    }

    @Transactional
    fun confirm(bookingId: UUID): BookingResponse {
        val booking = bookingRepository.findById(bookingId)
            .orElseThrow { BookingNotFoundException() }

        booking.status = BookingStatus.CONFIRMED
        booking.updatedAt = LocalDateTime.now()
        bookingRepository.save(booking)

        bookingEventPublisher.publishConfirmed(booking)

        return BookingResponse.from(booking)
    }

    @Transactional
    fun cancel(userId: String, bookingId: UUID): BookingResponse {
        val booking = bookingRepository.findById(bookingId)
            .filter { it.userId == userId }
            .orElseThrow { BookingNotFoundException() }

        if (booking.status == BookingStatus.CANCELLED) {
            throw BookingAlreadyCancelledException()
        }

        booking.status = BookingStatus.CANCELLED
        booking.updatedAt = LocalDateTime.now()
        bookingRepository.save(booking)

        // Redis 롤백: 잔여 좌석 복구 + 좌석 잠금 해제
        myRedisTemplate.incrKey(seatsRemainingKey(booking.eventId))
        myRedisTemplate.delKey(seatLockKey(booking.eventId, booking.seatId))

        bookingEventPublisher.publishCancelled(booking)

        return BookingResponse.from(booking)
    }
}
