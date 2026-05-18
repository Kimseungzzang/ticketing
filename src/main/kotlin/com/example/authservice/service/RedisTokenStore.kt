package com.example.authservice.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import jakarta.annotation.PreDestroy

@Service
class RedisTokenStore(
    @Value("\${redis.host:localhost}") private val host: String,
    @Value("\${redis.port:6379}") private val port: Int,
    @Value("\${redis.connect-timeout-ms:1000}") private val connectTimeoutMs: Int,
    @Value("\${redis.read-timeout-ms:1000}") private val readTimeoutMs: Int,
) {
    private val lock = Any()
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null

    fun saveJwt(userId: String, token: String, ttlMs: Long) {
        val key = "auth:jwt:$userId"
        val response = runCatching {
            sendCommandWithReconnect("SET", key, token, "PX", ttlMs.toString())
        }.getOrElse { cause ->
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis에 JWT 저장 실패", cause)
        }

        if (response != "+OK") {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Redis에 JWT 저장 실패: $response",
            )
        }
    }

    private fun sendCommandWithReconnect(vararg args: String): String =
        synchronized(lock) {
            runCatching {
                sendCommand(args)
            }.getOrElse {
                closeConnection()
                sendCommand(args)
            }
        }

    private fun sendCommand(args: Array<out String>): String {
        ensureConnected()
        val currentOutput = checkNotNull(output) { "Redis output stream is not connected" }
        val currentInput = checkNotNull(input) { "Redis input stream is not connected" }

        currentOutput.write(encodeRespArray(args).toByteArray(Charsets.UTF_8))
        currentOutput.flush()
        return readRespLine(currentInput)
    }

    private fun ensureConnected() {
        val currentSocket = socket
        if (currentSocket != null && currentSocket.isConnected && !currentSocket.isClosed) {
            return
        }

        closeConnection()
        val newSocket = Socket()
        newSocket.soTimeout = readTimeoutMs
        newSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket = newSocket
        input = BufferedInputStream(newSocket.getInputStream())
        output = BufferedOutputStream(newSocket.getOutputStream())
    }

    private fun encodeRespArray(args: Array<out String>): String =
        buildString {
            append("*").append(args.size).append("\r\n")
            args.forEach { arg ->
                val bytes = arg.toByteArray(Charsets.UTF_8)
                append("$").append(bytes.size).append("\r\n")
                append(arg).append("\r\n")
            }
        }

    private fun readRespLine(input: BufferedInputStream): String {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val next = input.read()
            if (next == -1) break
            if (next == '\r'.code) {
                input.read()
                break
            }
            bytes.add(next.toByte())
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    @PreDestroy
    fun close() {
        synchronized(lock) {
            closeConnection()
        }
    }

    private fun closeConnection() {
        input = null
        output = null
        socket?.close()
        socket = null
    }
}
