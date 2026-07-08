package com.example.ticketcommand.repository

import com.example.ticketcommand.entity.EventEntity
import org.springframework.data.jpa.repository.JpaRepository

interface EventRepository : JpaRepository<EventEntity, String>
