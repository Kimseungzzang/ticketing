package com.example.ticketcommand.config

import com.example.ticketcommand.event.MyKafkaProducerPool
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MyKafkaConfig {
    companion object {
        const val TOPIC = "reservation-events"
        const val PARTITIONS = 3
    }

    // 단일 소켓 producer 대신 connection pool을 둔다(동시 발행 허용 → §11.4의 -23% 회복 목적).
    // destroyMethod="close"로 앱 종료 시 모든 소켓 정리.
    //
    // 주의: 풀 생성자가 즉시 N개의 TCP 연결을 맺고 createTopic도 호출하므로
    //   이 빈이 만들어지려면 MyKafka broker가 먼저 떠 있어야 한다(:9092).
    // mykafka.publisher.enabled=false 로 끄면 풀 자체를 안 만든다(벤치마크: 발행 오버헤드 측정용).
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = ["mykafka.publisher.enabled"], havingValue = "true", matchIfMissing = true)
    fun myKafkaProducerPool(
        @Value("\${mykafka.host:localhost}") host: String,
        @Value("\${mykafka.port:9092}") port: Int,
        @Value("\${mykafka.producer.pool-size:8}") poolSize: Int,
    ): MyKafkaProducerPool {
        val pool = MyKafkaProducerPool(host, port, poolSize)
        pool.withProducer { it.createTopic(TOPIC, PARTITIONS) } // 이미 있으면 ALREADY_EXISTS — 무시
        return pool
    }
}
