package com.example.bookingservice.service

import com.example.bookingservice.domain.SeatStatus
import com.example.bookingservice.dto.SeatAvailabilityResponse
import com.example.bookingservice.dto.SeatResponse
import com.example.bookingservice.dto.SeatSectionResponse
import com.example.bookingservice.repository.SeatRepository
import com.example.bookingservice.service.BookingService
import com.example.myredisclient.MyRedisTemplate
import org.springframework.stereotype.Service

@Service
class SeatService(
    private val seatRepository: SeatRepository,
    private val myRedisTemplate: MyRedisTemplate,
) {

    fun getSections(eventId: String): List<SeatSectionResponse> =
        seatRepository.findByEventIdOrderBySectionIdAscRowAscNumberAsc(eventId)
            .groupBy { it.sectionId }
            .map { (sectionId, seats) ->
                SeatSectionResponse(
                    sectionId = sectionId,
                    sectionName = seats.first().sectionName,
                    price = seats.first().price,
                    seats = seats.map { SeatResponse.from(it) },
                )
            }
            .sortedBy { listOf("S", "R", "A").indexOf(it.sectionId) }

    fun getAvailability(eventId: String): SeatAvailabilityResponse {
        val total = seatRepository.countByEventId(eventId)
        val available = myRedisTemplate.getKey(BookingService.seatsRemainingKey(eventId))?.toLongOrNull()
            ?: seatRepository.countByEventIdAndStatus(eventId, SeatStatus.AVAILABLE)
        return SeatAvailabilityResponse(total = total, available = available)
    }
}
