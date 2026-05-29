package com.example.queueservice.scheduler

import com.example.queueservice.service.QueueService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class QueueScheduler(private val queueService: QueueService) {

    @Scheduled(fixedDelay = 2000)
    fun admitUsers() {
        queueService.waitingEventIds().forEach { eventId ->
            queueService.admitFromQueue(eventId)
        }
    }
}
