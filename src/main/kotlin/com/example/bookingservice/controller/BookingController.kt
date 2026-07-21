package com.example.bookingservice.controller

import com.example.bookingservice.dto.BookingCreateRequest
import com.example.bookingservice.dto.BookingResponse
import com.example.bookingservice.dto.ConfirmOrderRequest
import com.example.bookingservice.dto.MyBookingResponse
import com.example.bookingservice.service.BookingService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/booking")
class BookingController(private val bookingService: BookingService) {

    @PostMapping
    fun create(
        @AuthenticationPrincipal userId: String,
        @RequestBody request: BookingCreateRequest,
    ): ResponseEntity<BookingResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(bookingService.create(userId, request))

    @GetMapping("/my")
    fun myBookings(@AuthenticationPrincipal userId: String): ResponseEntity<List<MyBookingResponse>> =
        ResponseEntity.ok(bookingService.myBookings(userId))

    // 주문(선택한 좌석 전부)을 한 번에 결제 확정한다. PG 승인은 좌석별이 아니라 주문 전체에 대해
    // 한 번만 일어나므로, 여기서 승인 대기를 흉내낸 뒤 각 예약을 순서대로 확정한다.
    // spring.threads.virtual.enabled=true라 이 요청을 처리하는 스레드 자체가 이미 가상 스레드다.
    // 그래서 그냥 동기 코드처럼 짜도 Thread.sleep 동안 Tomcat 플랫폼 스레드를 붙잡지 않는다
    // (별도 executor/CompletableFuture로 감쌀 필요가 없음).
    @PostMapping("/confirm")
    fun confirmOrder(
        @RequestBody request: ConfirmOrderRequest,
    ): ResponseEntity<List<BookingResponse>> {
        Thread.sleep(1500) // PG 승인 대기 시뮬레이션 — 주문 전체에 대해 1회
        val results = request.bookingIds.map { bookingService.confirm(UUID.fromString(it)) }
        return ResponseEntity.ok(results)
    }

    @DeleteMapping("/{bookingId}")
    fun cancel(
        @PathVariable bookingId: UUID,
    ): ResponseEntity<Void> {
        bookingService.cancel(bookingId)
        return ResponseEntity.noContent().build()
    }
}
