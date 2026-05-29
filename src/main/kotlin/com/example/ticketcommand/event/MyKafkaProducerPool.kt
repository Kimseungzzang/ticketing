package com.example.ticketcommand.event

import com.example.mykafka.client.MyKafkaProducer
import java.util.concurrent.ArrayBlockingQueue

// MyKafkaProducer는 단일 소켓이라 thread-safe하지 않다. 기존엔 `synchronized(producer)`로
// 발행을 직렬화했는데, 이게 동시 요청에서 병목이었다(§11.4: 발행 ON 시 쓰기 처리량 -23%).
//
// 이 풀은 독립 connection(소켓) N개를 두고 빌려쓰게 해서 **동시 발행 = 풀 크기**까지 허용한다.
// 각 producer는 한 번에 한 스레드만 사용하므로(borrow→사용→반납) 단일 소켓 제약은 그대로 지키면서
// 직렬화 지점만 없앤다.
//
// 학습용 단순화: 사용 중 소켓이 깨지면(예: wire desync) 그대로 풀에 반납돼 오염될 수 있다.
//   production이면 검증/eviction/재연결이 필요. 여기선 생략.
class MyKafkaProducerPool(
    host: String,
    port: Int,
    val size: Int,
) : AutoCloseable {
    private val all: List<MyKafkaProducer> = (1..size).map { MyKafkaProducer(host, port) }
    private val available = ArrayBlockingQueue<MyKafkaProducer>(size).apply { all.forEach { put(it) } }

    // 가용 connection을 하나 빌려 block 실행 후 반드시 반납. 없으면 빌 때까지 대기(blocking).
    fun <T> withProducer(block: (MyKafkaProducer) -> T): T {
        val producer = available.take()
        try {
            return block(producer)
        } finally {
            available.put(producer)
        }
    }

    override fun close() {
        all.forEach { runCatching { it.close() } }
    }
}
