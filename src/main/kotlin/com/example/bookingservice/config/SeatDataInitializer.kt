package com.example.bookingservice.config

import com.example.bookingservice.domain.Seat
import com.example.bookingservice.repository.SeatRepository
import com.example.bookingservice.service.BookingService
import com.example.myredisclient.MyRedisTemplate
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class SeatDataInitializer(
    private val seatRepository: SeatRepository,
    private val myRedisTemplate: MyRedisTemplate,
) : ApplicationRunner {

    private data class SectionMeta(
        val id: String,
        val name: String,
        val price: Int,
        val rows: List<String>,
        val seatsPerRow: Int,
    )

    private val sections = listOf(
        SectionMeta("S", "S석", 176000, listOf("A", "B", "C"), 20),
        SectionMeta("R", "R석", 132000, listOf("A", "B", "C", "D", "E"), 24),
        SectionMeta("A", "A석", 99000,  listOf("A", "B", "C", "D"), 28),
    )

    override fun run(args: ApplicationArguments) {
        val eventId = "EVT2026-001"

        if (seatRepository.countByEventId(eventId) == 0L) {
            val seats = sections.flatMap { section ->
                section.rows.flatMap { row ->
                    (1..section.seatsPerRow).map { num ->
                        val seatId = "${section.id}-$row-$num"
                        Seat(
                            id = "$eventId:$seatId",
                            eventId = eventId,
                            seatId = seatId,
                            sectionId = section.id,
                            sectionName = section.name,
                            row = row,
                            number = num,
                            price = section.price,
                        )
                    }
                }
            }
            seatRepository.saveAll(seats)
        }

        // 재시작 시 DB 기준으로 Redis 동기화 (myRedis는 in-memory라 재시작 시 초기화됨)
        val total     = seatRepository.countByEventId(eventId)
        val available = seatRepository.countByEventIdAndStatus(eventId, com.example.bookingservice.domain.SeatStatus.AVAILABLE)
        myRedisTemplate.setKey(BookingService.seatsTotalKey(eventId),     total.toString(),     -1L)
        myRedisTemplate.setKey(BookingService.seatsRemainingKey(eventId), available.toString(), -1L)
    }
}
