package com.example.ticketcommand

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling // OutboxRelay의 @Scheduled 폴링용
class TicketCommandApplication

fun main(args: Array<String>) {
    runApplication<TicketCommandApplication>(*args)
}
