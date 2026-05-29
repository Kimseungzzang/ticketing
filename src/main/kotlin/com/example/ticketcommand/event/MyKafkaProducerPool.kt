package com.example.ticketcommand.event

import com.example.mykafka.client.MyKafkaProducer
import java.util.concurrent.ArrayBlockingQueue

// MyKafkaProducer는 단일 소켓이라 thread-safe하지 않다. 기존 `synchronized(producer)`는 동시 요청에서
// 병목이었다(§11.4). 이 풀은 독립 connection N개를 빌려쓰게 해서 동시 발행 = 풀 크기까지 허용한다.
//
// 재연결(reconnect):
//   각 슬롯은 producer를 **지연 생성**한다. 사용 중 예외(소켓 깨짐: broker 다운/재시작/wire 오류)가 나면
//   그 슬롯을 invalidate(닫고 비움) → **다음 사용 때 새 connection으로 재연결**한다.
//   슬롯 자체는 성공/실패 무관하게 항상 풀에 반납하므로 풀 크기가 유지되고, take()가 영영 막히지 않는다.
//   → broker가 죽었다 살아나도 OutboxRelay가 다음 폴링에서 자동으로 다시 발행할 수 있다(§14의 한계 해소).
class MyKafkaProducerPool(
    private val host: String,
    private val port: Int,
    val size: Int,
) : AutoCloseable {
    // 한 connection을 지연 생성/재생성하는 슬롯. 큐에서 꺼낸 한 스레드만 만지므로 동기화 불필요.
    private inner class Slot {
        private var producer: MyKafkaProducer? = null
        fun get(): MyKafkaProducer = producer ?: MyKafkaProducer(host, port).also { producer = it }
        fun invalidate() {
            producer?.let { runCatching { it.close() } }
            producer = null
        }
        fun close() = invalidate()
    }

    private val slots: List<Slot> = (1..size).map { Slot() }
    private val available = ArrayBlockingQueue<Slot>(size).apply { slots.forEach { put(it) } }

    // 가용 슬롯을 빌려 (필요 시 연결하고) block 실행. 예외 시 슬롯을 무효화해 다음에 재연결되게 한다.
    fun <T> withProducer(block: (MyKafkaProducer) -> T): T {
        val slot = available.take()
        try {
            val result = block(slot.get())
            available.put(slot) // 정상 → 그대로 반납(재사용)
            return result
        } catch (e: Throwable) {
            slot.invalidate()   // 소켓이 깨졌을 수 있음 → 다음 get()에서 재연결
            available.put(slot) // 슬롯은 항상 반납(풀 크기 유지)
            throw e
        }
    }

    override fun close() {
        slots.forEach { it.close() }
    }
}
