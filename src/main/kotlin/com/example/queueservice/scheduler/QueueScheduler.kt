package com.example.queueservice.scheduler

import com.example.queueservice.service.QueueService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class QueueScheduler(private val queueService: QueueService) {

    companion object {
        private const val EVENT_ID = "EVT2026-001"
    }

    @Scheduled(fixedDelay = 2000)
    fun admitUsers() {
        queueService.admitFromQueue(EVENT_ID)
    }
}
