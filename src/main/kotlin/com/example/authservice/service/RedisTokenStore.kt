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

    // ── 범용 키 조작 (큐 서비스에서 사용) ────────────────────────────────────

    fun getKey(key: String): String? {
        val response = runCatching {
            sendCommandWithReconnect("GET", key)
        }.getOrElse { return null }
        return when (response) {
            is RespValue.BulkString -> response.value
            RespValue.NullBulkString -> null
            else -> null
        }
    }

    fun setKey(key: String, value: String, ttlSec: Long) {
        runCatching {
            if (ttlSec < 0) {
                // TTL 없이 저장
                sendCommandWithReconnect("SET", key, value)
            } else {
                sendCommandWithReconnect("SET", key, value, "EX", ttlSec.toString())
            }
        }
    }

    fun delKey(vararg keys: String) {
        runCatching {
            sendCommandWithReconnect("DEL", *keys)
        }
    }

    fun incrKey(key: String): Long {
        val response = runCatching {
            sendCommandWithReconnect("INCR", key)
        }.getOrElse { return 0L }
        return (response as? RespValue.Integer)?.value ?: 0L
    }

    fun decrKey(key: String): Long {
        val response = runCatching {
            sendCommandWithReconnect("DECR", key)
        }.getOrElse { return 0L }
        return (response as? RespValue.Integer)?.value ?: 0L
    }

    // ── Sorted Set (대기열) ───────────────────────────────────────────────────

    /** member가 이미 있으면 score만 갱신, 없으면 추가 후 1 반환 */
    fun zadd(key: String, score: Long, member: String): Long {
        val response = runCatching {
            sendCommandWithReconnect("ZADD", key, score.toString(), member)
        }.getOrElse { return 0L }
        return (response as? RespValue.Integer)?.value ?: 0L
    }

    /** 0-indexed 순위 반환, 없으면 null */
    fun zrank(key: String, member: String): Long? {
        val response = runCatching {
            sendCommandWithReconnect("ZRANK", key, member)
        }.getOrElse { return null }
        return when (response) {
            is RespValue.Integer -> response.value
            else -> null
        }
    }

    fun zcard(key: String): Long {
        val response = runCatching {
            sendCommandWithReconnect("ZCARD", key)
        }.getOrElse { return 0L }
        return (response as? RespValue.Integer)?.value ?: 0L
    }

    fun keysAll(): Set<String> {
        val response = runCatching {
            sendCommandWithReconnect("KEYS")
        }.getOrElse { e ->
            println("[DEBUG] keysAll 예외: ${e.message}")
            return emptySet()
        }
        println("[DEBUG] keysAll 응답 타입: ${response::class.simpleName}, 값: $response")
        return when (response) {
            is RespValue.Array -> response.values.filterIsInstance<RespValue.BulkString>().map { it.value }.toSet()
            else -> emptySet()
        }
    }

    fun type(key: String): String {
        val response = runCatching { sendCommandWithReconnect("TYPE", key) }.getOrElse { return "none" }
        return (response as? RespValue.SimpleString)?.value ?: "none"
    }

    /** score 오름차순 전체 멤버 반환 [member, score, member, score, ...] */
    fun zrangeWithScores(key: String): List<Pair<String, String>> {
        val response = runCatching {
            sendCommandWithReconnect("ZRANGE", key, "0", "-1", "WITHSCORES")
        }.getOrElse { return emptyList() }
        return when (response) {
            is RespValue.Array -> {
                val items = response.values.filterIsInstance<RespValue.BulkString>().map { it.value }
                items.chunked(2).mapNotNull { if (it.size == 2) it[0] to it[1] else null }
            }
            else -> emptyList()
        }
    }

    fun ttlSec(key: String): Long {
        val response = runCatching { sendCommandWithReconnect("TTL", key) }.getOrElse { return -2L }
        return (response as? RespValue.Integer)?.value ?: -2L
    }

    /** score 낮은 순으로 count개 꺼내서 member 이름 목록 반환 */
    fun zpopmin(key: String, count: Int): List<String> {
        val response = runCatching {
            sendCommandWithReconnect("ZPOPMIN", key, count.toString())
        }.getOrElse { return emptyList() }
        return when (response) {
            is RespValue.Array -> response.values
                .filterIsInstance<RespValue.BulkString>()
                .filterIndexed { index, _ -> index % 2 == 0 }  // 짝수 인덱스 = member (홀수 = score)
                .map { it.value }
            else -> emptyList()
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
