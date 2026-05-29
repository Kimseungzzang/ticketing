package com.example.ticketquery.config

import com.example.mykafka.client.MyKafkaConsumer
import com.example.mykafka.client.MyKafkaProducer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MyKafkaConfig {
    companion object {
        const val TOPIC = "reservation-events"
        const val PARTITIONS = 3
        const val GROUP = "ticket-query"
    }

    // 단일 소켓 consumer. 폴링 스레드 1개만 사용하므로 외부 동기화 불필요.
    //
    // 중요: consumer는 createTopic을 못 한다(producer 전용). 그런데 토픽이 없는 상태로
    //   fetch를 호출하면 MyKafka client의 에러 경로가 응답 프레임을 끝까지 안 읽고 throw해서
    //   단일 영속 소켓의 스트림이 어긋난다(desync) → 이후 모든 호출이 오염된다.
    //   따라서 consumer를 만들기 전에 단발 producer로 토픽 존재를 보장한다(idempotent).
    //   이렇게 하면 fetch는 항상 정상(빈 응답)이라 에러 경로를 아예 안 탄다 + 기동 순서에 무관.
    // mykafka.consumer.enabled=false 로 끄면 consumer를 안 만든다 → query가 이벤트와 무관하게
    // (DB_NAME으로 지정한) DB를 그냥 읽기만 한다. 비분리(ticket_db 직접 읽기) 벤치마크용.
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = ["mykafka.consumer.enabled"], havingValue = "true", matchIfMissing = true)
    fun myKafkaConsumer(
        @Value("\${mykafka.host:localhost}") host: String,
        @Value("\${mykafka.port:9092}") port: Int,
    ): MyKafkaConsumer {
        MyKafkaProducer(host, port).use { it.createTopic(TOPIC, PARTITIONS) } // 이미 있으면 ALREADY_EXISTS
        return MyKafkaConsumer(host, port, GROUP)
    }
}
