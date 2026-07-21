package com.example.myredis

import java.util.concurrent.ConcurrentHashMap

object Store {
    private data class Entry(val value: String, val expiresAt: Long?)
    private data class DebugRow(val key: String, val value: String, val ttl: String)

    private val map = ConcurrentHashMap<String, Entry>()
    private val zsets = ConcurrentHashMap<String, SortedSet>()

    // ── Sorted Set 명령 (skip list 기반) — 각 zset 인스턴스 단위로 synchronized ──
    fun zadd(key: String, score: Double, member: String): Int {
        val zs = zsets.getOrPut(key) { SortedSet() }
        return synchronized(zs) { if (zs.add(member, score)) 1 else 0 }
    }
    fun zrank(key: String, member: String): Long? {
        val zs = zsets[key] ?: return null
        return synchronized(zs) { zs.rank(member) }
    }
    fun zcard(key: String): Int {
        val zs = zsets[key] ?: return 0
        return synchronized(zs) { zs.size }
    }
    fun zscore(key: String, member: String): Double? {
        val zs = zsets[key] ?: return null
        return synchronized(zs) { zs.score(member) }
    }
    fun zrem(key: String, member: String): Int {
        val zs = zsets[key] ?: return 0
        return synchronized(zs) { if (zs.remove(member)) 1 else 0 }
    }
    fun zremrangebyscore(key: String, min: Double, max: Double): Int {
        val zs = zsets[key] ?: return 0
        return synchronized(zs) { zs.removeRangeByScore(min, max) }
    }
    fun zpopmin(key: String, n: Int): List<Pair<String, Double>> {
        val zs = zsets[key] ?: return emptyList()
        return synchronized(zs) { zs.popMin(n) }
    }
    fun zrange(key: String, start: Long, end: Long): List<Pair<String, Double>> {
        val zs = zsets[key] ?: return emptyList()
        return synchronized(zs) { zs.rangeByRank(start, end) }
    }

    fun set(key: String, value: String, ttlMs: Long? = null) {
        map[key] = Entry(value, ttlMs?.let { System.currentTimeMillis() + it })
        printDebugTable("SET $key")
    }

    /** SET ... NX — 키가 없을 때(또는 만료됐을 때)만 저장. compute()로 확인+저장을 한 번에 원자적으로 처리. */
    fun setNx(key: String, value: String, ttlMs: Long? = null): Boolean {
        val now = System.currentTimeMillis()
        var success = false
        map.compute(key) { _, existing ->
            val alive = existing != null && (existing.expiresAt == null || existing.expiresAt > now)
            if (alive) {
                existing
            } else {
                success = true
                Entry(value, ttlMs?.let { now + it })
            }
        }
        if (success) printDebugTable("SETNX $key")
        return success
    }

    fun get(key: String): String? {
        val entry = map[key] ?: return null
        if (entry.expiresAt != null && System.currentTimeMillis() > entry.expiresAt) {
            if (map.remove(key, entry)) {
                printDebugTable("EXPIRED $key")
            }
            return null
        }
        return entry.value
    }

    /** INCR/DECR 공용 — 원자적 증감. 값이 정수가 아니면 null(에러). 기존 TTL은 유지. */
    fun incrBy(key: String, delta: Long): Long? {
        var error = false
        var result = 0L
        map.compute(key) { _, existing ->
            val now = System.currentTimeMillis()
            val alive = existing != null && (existing.expiresAt == null || existing.expiresAt > now)
            val current = if (alive) existing!!.value.toLongOrNull() else 0L
            if (current == null) {
                error = true
                return@compute existing
            }
            result = current + delta
            Entry(result.toString(), if (alive) existing!!.expiresAt else null)
        }
        if (error) return null
        printDebugTable("INCRBY $key")
        return result
    }

    fun del(vararg keys: String): Int {
        val deleted = keys.count { (map.remove(it) != null) or (zsets.remove(it) != null) }
        if (deleted > 0) {
            printDebugTable("DEL ${keys.joinToString(" ")}")
        }
        return deleted
    }

    fun expire(key: String, ttlMs: Long): Boolean {
        val entry = map[key] ?: return false
        map[key] = entry.copy(expiresAt = System.currentTimeMillis() + ttlMs)
        printDebugTable("EXPIRE $key")
        return true
    }

    fun ttl(key: String): Long {
        val entry = map[key] ?: return -2L
        val expiresAt = entry.expiresAt ?: return -1L
        val remaining = expiresAt - System.currentTimeMillis()
        return if (remaining <= 0) {
            if (map.remove(key, entry)) {
                printDebugTable("EXPIRED $key")
            }
            -2L
        } else {
            remaining / 1000
        }
    }

    fun exists(key: String): Boolean = get(key) != null

    fun keys(): Set<String> {
        val now = System.currentTimeMillis()
        return (map.entries
            .filter { (_, v) -> v.expiresAt == null || v.expiresAt > now }
            .map { it.key } + zsets.keys).toSet()
    }

    private fun printDebugTable(action: String) {
        val now = System.currentTimeMillis()
        val rows = map.entries
            .map { (key, entry) ->
                DebugRow(
                    key = key,
                    value = entry.value,
                    ttl = entry.expiresAt?.let { ((it - now).coerceAtLeast(0) / 1000).toString() } ?: "-"
                )
            }
            .sortedBy { it.key }

        val keyWidth = maxOf("key".length, rows.maxOfOrNull { it.key.length } ?: 0)
        val valueWidth = maxOf("value".length, rows.maxOfOrNull { it.value.length } ?: 0)
        val ttlWidth = maxOf("ttl(s)".length, rows.maxOfOrNull { it.ttl.length } ?: 0)
        val border = "+-${"-".repeat(keyWidth)}-+-${"-".repeat(valueWidth)}-+-${"-".repeat(ttlWidth)}-+"

        println()
        println("[STORE DEBUG] $action")
        println(border)
        println("| ${"key".padEnd(keyWidth)} | ${"value".padEnd(valueWidth)} | ${"ttl(s)".padEnd(ttlWidth)} |")
        println(border)
        if (rows.isEmpty()) {
            println("| ${"(empty)".padEnd(keyWidth)} | ${"".padEnd(valueWidth)} | ${"".padEnd(ttlWidth)} |")
        } else {
            rows.forEach {
                println("| ${it.key.padEnd(keyWidth)} | ${it.value.padEnd(valueWidth)} | ${it.ttl.padEnd(ttlWidth)} |")
            }
        }
        println(border)
    }
}
