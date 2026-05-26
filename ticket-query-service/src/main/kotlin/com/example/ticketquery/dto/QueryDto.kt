package com.example.ticketquery.dto

import com.example.ticketquery.entity.EventEntity
import com.example.ticketquery.entity.ReservationEntity
import com.example.ticketquery.entity.ReservationStatus
import com.example.ticketquery.entity.SeatEntity
import com.example.ticketquery.entity.SeatSectionEntity
import com.example.ticketquery.entity.SeatStatus
import java.time.Instant

data class EventView(
    val id: String,
    val title: String,
    val subtitle: String?,
    val artist: String,
    val venue: String,
    val startsAt: Instant,
    val doorsOpenAt: Instant?,
) {
    companion object {
        fun from(e: EventEntity) = EventView(e.id, e.title, e.subtitle, e.artist, e.venue, e.startsAt, e.doorsOpenAt)
    }
}

data class SeatView(
    val id: String,
    val rowLabel: String,
    val seatNumber: Int,
    val status: SeatStatus,
)

data class SectionView(
    val id: String,
    val name: String,
    val korName: String,
    val price: Int,
    val color: String,
    val seats: List<SeatView>,
)

data class SeatMapView(
    val eventId: String,
    val sections: List<SectionView>,
) {
    companion object {
        fun build(eventId: String, sections: List<SeatSectionEntity>, seatsBySection: Map<String, List<SeatEntity>>) =
            SeatMapView(
                eventId = eventId,
                sections = sections.map { section ->
                    SectionView(
                        id = section.id,
                        name = section.name,
                        korName = section.korName,
                        price = section.price,
                        color = section.color,
                        seats = (seatsBySection[section.id] ?: emptyList())
                            .sortedWith(compareBy({ it.rowLabel }, { it.seatNumber }))
                            .map { SeatView(it.id, it.rowLabel, it.seatNumber, it.status) },
                    )
                },
            )
    }
}

data class ReservationView(
    val id: String,
    val userId: String,
    val seatId: String,
    val status: ReservationStatus,
    val createdAt: Instant,
) {
    companion object {
        fun from(r: ReservationEntity) = ReservationView(r.id, r.userId, r.seatId, r.status, r.createdAt)
    }
}
