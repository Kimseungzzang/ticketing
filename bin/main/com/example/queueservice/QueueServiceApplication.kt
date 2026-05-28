package com.example.queueservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class QueueServiceApplication

fun main(args: Array<String>) {
    runApplication<QueueServiceApplication>(*args)
}
