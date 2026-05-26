package com.example.authservice.scheduler

import com.example.authservice.service.QueueService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class QueueScheduler(private val queueService: QueueService) {

    companion object {
        // 현재는 단일 이벤트만 처리. 이후 이벤트 목록을 DB나 설정에서 읽어오도록 확장 가능.
        private const val EVENT_ID = "EVT2026-001"
    }

    /**
     * 2초마다 실행.
     * 빈 슬롯이 생기면 대기열 앞에서 입장 토큰 발급.
     * fixedDelay = 이전 실행 완료 후 2초 뒤에 다음 실행 (실행 시간 누적 방지)
     */
    @Scheduled(fixedDelay = 2000)
    fun admitUsers() {
        queueService.admitFromQueue(EVENT_ID)
    }
}
