package com.example.ticketquery.service

import com.example.ticketquery.dto.ReservationView
import com.example.ticketquery.repository.ReservationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

class ReservationNotFoundException(message: String) : RuntimeException(message)

@Service
@Transactional(readOnly = true)
class ReservationQueryService(
    private val reservationRepository: ReservationRepository,
) {
    fun getReservation(id: String): ReservationView = reservationRepository.findById(id)
        .map(ReservationView::from)
        .orElseThrow { ReservationNotFoundException("Reservation not found: $id") }

    fun listByUser(userId: String): List<ReservationView> =
        reservationRepository.findByUserId(userId).map(ReservationView::from)
}
