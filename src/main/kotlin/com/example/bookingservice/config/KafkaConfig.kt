package com.example.bookingservice.config

import com.example.mykafka.client.MyKafkaProducer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class KafkaConfig(
    @Value("\${mykafka.host}") private val host: String,
    @Value("\${mykafka.port}") private val port: Int,
    @Value("\${booking.kafka.topic}") private val topic: String,
) {

    @Bean(destroyMethod = "close")
    fun myKafkaProducer(): MyKafkaProducer {
        val producer = MyKafkaProducer(host = host, port = port)
        producer.createTopic(topic, partitionCount = 1)
        return producer
    }
}
