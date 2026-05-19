package com.example.authservice.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import jakarta.annotation.PreDestroy

@Service
class RedisTokenStore(
    @Value("\${redis.host:localhost}") private val host: String,
    @Value("\${redis.port:6379}") private val port: Int,
    @Value("\${redis.connect-timeout-ms:1000}") private val connectTimeoutMs: Int,
    @Value("\${redis.read-timeout-ms:1000}") private val readTimeoutMs: Int,
    @Value("\${redis.pool-size:4}") private val poolSize: Int,
) {
    init {
        require(poolSize > 0) { "redis.pool-size must be greater than 0" }
    }

    private val connections = List(poolSize) { RedisConnection() }
    private val pool = ArrayBlockingQueue<RedisConnection>(poolSize).apply {
        connections.forEach { offer(it) }
    }

    fun saveAccessToken(userId: String, token: String, ttlMs: Long) {
        saveToken(accessKey(userId), token, ttlMs, "액세스 토큰")
    }

    fun saveRefreshToken(userId: String, token: String, ttlMs: Long) {
        saveToken(refreshKey(userId), token, ttlMs, "리프레시 토큰")
    }

    fun getRefreshToken(userId: String): String? {
        val response = runCatching {
            sendCommandWithReconnect("GET", refreshKey(userId))
        }.getOrElse { cause ->
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis에서 리프레시 토큰 조회 실패", cause)
        }

        return when (response) {
            is RespValue.BulkString -> response.value
            RespValue.NullBulkString -> null
            else -> throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Redis 리프레시 토큰 조회 실패: ${response.describe()}",
            )
        }
    }

    fun deleteTokens(userId: String) {
        runCatching {
            sendCommandWithReconnect("DEL", accessKey(userId), refreshKey(userId))
        }.getOrElse { cause ->
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis 토큰 삭제 실패", cause)
        }
    }

    private fun saveToken(key: String, token: String, ttlMs: Long, label: String) {
        val response = runCatching {
            sendCommandWithReconnect("SET", key, token, "PX", ttlMs.toString())
        }.getOrElse { cause ->
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis에 $label 저장 실패", cause)
        }

        if (response !is RespValue.SimpleString || response.value != "OK") {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Redis에 $label 저장 실패: ${response.describe()}",
            )
        }
    }

    private fun accessKey(userId: String): String = "auth:jwt:access:$userId"

    private fun refreshKey(userId: String): String = "auth:jwt:refresh:$userId"

    private fun sendCommandWithReconnect(vararg args: String): RespValue {
        val connection = borrowConnection()
        return try {
            runCatching {
                connection.sendCommand(args)
            }.getOrElse {
                connection.close()
                connection.sendCommand(args)
            }
        } finally {
            releaseConnection(connection)
        }
    }

    private fun borrowConnection(): RedisConnection =
        try {
            pool.take()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for a Redis connection", e)
        }

    private fun releaseConnection(connection: RedisConnection) {
        check(pool.offer(connection)) { "Redis connection pool is full" }
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

    private fun readRespValue(input: BufferedInputStream): RespValue {
        return when (val type = input.read()) {
            '+'.code -> RespValue.SimpleString(readRespLine(input))
            '-'.code -> RespValue.Error(readRespLine(input))
            ':'.code -> RespValue.Integer(readRespLine(input).toLong())
            '$'.code -> readBulkString(input)
            '*'.code -> readArray(input)
            -1 -> throw EOFException("Redis closed the connection")
            else -> throw IOException("Unsupported RESP type: ${type.toChar()}")
        }
    }

    private fun readBulkString(input: BufferedInputStream): RespValue {
        val length = readRespLine(input).toInt()
        if (length < 0) return RespValue.NullBulkString

        val bytes = input.readNBytes(length)
        if (bytes.size != length) {
            throw EOFException("Unexpected end of stream while reading bulk string")
        }
        expectCrlf(input)
        return RespValue.BulkString(bytes.toString(Charsets.UTF_8))
    }

    private fun readArray(input: BufferedInputStream): RespValue {
        val length = readRespLine(input).toInt()
        if (length < 0) return RespValue.NullArray

        val items = MutableList(length) { readRespValue(input) }
        return RespValue.Array(items)
    }

    private fun readRespLine(input: BufferedInputStream): String {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val next = input.read()
            if (next == -1) throw EOFException("Unexpected end of stream while reading RESP line")
            if (next == '\r'.code) {
                expectLf(input)
                break
            }
            bytes.add(next.toByte())
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun expectCrlf(input: BufferedInputStream) {
        val cr = input.read()
        if (cr != '\r'.code) {
            throw IOException("Expected CR in RESP payload terminator")
        }
        expectLf(input)
    }

    private fun expectLf(input: BufferedInputStream) {
        val lf = input.read()
        if (lf != '\n'.code) {
            throw IOException("Expected LF in RESP line terminator")
        }
    }

    private sealed interface RespValue {
        data class SimpleString(val value: String) : RespValue
        data class Error(val message: String) : RespValue
        data class Integer(val value: Long) : RespValue
        data class BulkString(val value: String) : RespValue
        data class Array(val values: List<RespValue>) : RespValue
        data object NullBulkString : RespValue
        data object NullArray : RespValue
    }

    private fun RespValue.describe(): String =
        when (this) {
            is RespValue.SimpleString -> "+$value"
            is RespValue.Error -> "-$message"
            is RespValue.Integer -> ":$value"
            is RespValue.BulkString -> "\$${value.length}($value)"
            is RespValue.Array -> "*${values.size}${values.joinToString(prefix = "[", postfix = "]") { it.describe() }}"
            RespValue.NullBulkString -> "\$-1"
            RespValue.NullArray -> "*-1"
        }

    @PreDestroy
    fun close() {
        connections.forEach { it.close() }
    }

    private inner class RedisConnection {
        private var socket: Socket? = null
        private var input: BufferedInputStream? = null
        private var output: BufferedOutputStream? = null

        fun sendCommand(args: Array<out String>): RespValue {
            ensureConnected()
            val currentOutput = checkNotNull(output) { "Redis output stream is not connected" }
            val currentInput = checkNotNull(input) { "Redis input stream is not connected" }

            currentOutput.write(encodeRespArray(args).toByteArray(Charsets.UTF_8))
            currentOutput.flush()
            return readRespValue(currentInput)
        }

        private fun ensureConnected() {
            val currentSocket = socket
            if (currentSocket != null && currentSocket.isConnected && !currentSocket.isClosed) {
                return
            }

            close()
            val newSocket = Socket()
            newSocket.soTimeout = readTimeoutMs
            newSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket = newSocket
            input = BufferedInputStream(newSocket.getInputStream())
            output = BufferedOutputStream(newSocket.getOutputStream())
        }

        fun close() {
            input = null
            output = null
            socket?.close()
            socket = null
        }
    }
}
