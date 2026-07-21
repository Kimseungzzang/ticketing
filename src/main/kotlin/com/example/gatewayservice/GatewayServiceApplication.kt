package com.example.gatewayservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.reactive.function.client.WebClient

@SpringBootApplication
class GatewayServiceApplication {

    @Bean
    fun webClientBuilder(): WebClient.Builder = WebClient.builder()
}

fun main(args: Array<String>) {
    runApplication<GatewayServiceApplication>(*args)
}
