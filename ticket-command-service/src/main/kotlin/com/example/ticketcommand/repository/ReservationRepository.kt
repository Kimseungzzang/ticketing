package com.example.ticketcommand.repository

import com.example.ticketcommand.entity.ReservationEntity
import org.springframework.data.jpa.repository.JpaRepository

interface ReservationRepository : JpaRepository<ReservationEntity, String>
