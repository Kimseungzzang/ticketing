package com.example.queueservice.scheduler

import com.example.queueservice.service.QueueService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class QueueScheduler(private val queueService: QueueService) {

    // admit 주기 — env QUEUE_ADMIT_INTERVAL_MS로 조절(기본 2000ms). 짧을수록 처리량↑(큐가 덜 쌓임).
    @Scheduled(fixedDelayString = "\${queue.admit-interval-ms:2000}")
    fun admitUsers() {
        queueService.waitingEventIds().forEach { eventId ->
            queueService.admitFromQueue(eventId)
        }
    }
}
