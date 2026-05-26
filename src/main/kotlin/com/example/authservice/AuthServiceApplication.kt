package com.example.authservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class AuthServiceApplication

fun main(args: Array<String>) {
	runApplication<AuthServiceApplication>(*args)
}
