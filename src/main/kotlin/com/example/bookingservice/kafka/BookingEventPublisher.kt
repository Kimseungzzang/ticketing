package com.example.bookingservice.kafka

import com.example.bookingservice.domain.Booking
import tools.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class BookingEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    @Value("\${booking.kafka.topic}") private val topic: String,
) {
    fun publishCreated(booking: Booking) {
        val event = BookingCreatedEvent(
            bookingId = booking.id.toString(),
            userId = booking.userId,
            eventId = booking.eventId,
            seatId = booking.seatId,
        )
        kafkaTemplate.send(topic, booking.id.toString(), objectMapper.writeValueAsString(event))
    }

    fun publishConfirmed(booking: Booking) {
        val event = BookingConfirmedEvent(
            bookingId = booking.id.toString(),
            userId = booking.userId,
        )
        kafkaTemplate.send(topic, booking.id.toString(), objectMapper.writeValueAsString(event))
    }

    fun publishCancelled(booking: Booking) {
        val event = BookingCancelledEvent(
            bookingId = booking.id.toString(),
            userId = booking.userId,
        )
        kafkaTemplate.send(topic, booking.id.toString(), objectMapper.writeValueAsString(event))
    }
}
