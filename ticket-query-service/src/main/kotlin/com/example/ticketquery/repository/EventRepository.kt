package com.example.ticketquery.repository

import com.example.ticketquery.entity.EventEntity
import org.springframework.data.jpa.repository.JpaRepository

interface EventRepository : JpaRepository<EventEntity, String>
