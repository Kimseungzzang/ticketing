package com.example.myredis

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 6379
    Server(port).start()
}
