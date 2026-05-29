package com.example.myredis

import java.util.concurrent.ConcurrentHashMap

object Store {
    private data class Entry(val value: RedisValue, val expiresAt: Long?)
    private data class DebugRow(val key: String, val value: String, val ttl: String)

    private val map = ConcurrentHashMap<String, Entry>()

    // ── String ──────────────────────────────────────────────────────────────

    fun set(key: String, value: String, ttlMs: Long? = null) {
        map[key] = Entry(StringValue(value), ttlMs?.let { System.currentTimeMillis() + it })
        printDebugTable("SET $key")
    }

    /** NX: 키가 없을 때만 저장. 성공하면 true, 이미 존재하면 false */
    fun setNx(key: String, value: String, ttlMs: Long? = null): Boolean {
        var isSet = false
        // compute is atomic — only one thread succeeds on concurrent requests
        map.compute(key) { _, existing ->
            val isExpired = existing?.expiresAt != null && System.currentTimeMillis() > existing.expiresAt
            if (existing == null || isExpired) {
                isSet = true
                Entry(StringValue(value), ttlMs?.let { System.currentTimeMillis() + it })
            } else {
                existing
            }
        }
        if (isSet) printDebugTable("SET $key NX")
        return isSet
    }

    fun get(key: String): String? {
        val entry = getEntry(key) ?: return null
        if (entry.value !is StringValue) throw WrongTypeException()
        return entry.value.raw
    }

    fun del(vararg keys: String): Int {
        val deleted = keys.count { map.remove(it) != null }
        if (deleted > 0) printDebugTable("DEL ${keys.joinToString(" ")}")
        return deleted
    }

    fun expire(key: String, ttlMs: Long): Boolean {
        var updated = false
        // compute is atomic — prevents read-then-write race; also filters expired keys
        map.compute(key) { _, existing ->
            val live = if (existing?.expiresAt != null && System.currentTimeMillis() > existing.expiresAt) null else existing
            if (live == null) null else {
                updated = true
                live.copy(expiresAt = System.currentTimeMillis() + ttlMs)
            }
        }
        if (updated) printDebugTable("EXPIRE $key")
        return updated
    }

    fun ttl(key: String): Long {
        val entry = map[key] ?: return -2L
        val expiresAt = entry.expiresAt ?: return -1L
        val remaining = expiresAt - System.currentTimeMillis()
        return if (remaining <= 0) {
            if (map.remove(key, entry)) printDebugTable("EXPIRED $key")
            -2L
        } else {
            remaining / 1000
        }
    }

    fun exists(key: String): Boolean = getEntry(key) != null

    fun keys(): Set<String> {
        val now = System.currentTimeMillis()
        return map.entries
            .filter { (_, v) -> v.expiresAt == null || v.expiresAt > now }
            .map { it.key }
            .toSet()
    }

    /** 키 타입 반환: "string" | "zset" | "none" */
    fun type(key: String): String {
        val entry = getEntry(key) ?: return "none"
        return when (entry.value) {
            is StringValue -> "string"
            is ZSetValue   -> "zset"
        }
    }

    fun ttlMs(key: String): Long {
        val entry = map[key] ?: return -2L
        val expiresAt = entry.expiresAt ?: return -1L
        val remaining = expiresAt - System.currentTimeMillis()
        return if (remaining <= 0) -2L else remaining
    }

    // ── Sorted Set ──────────────────────────────────────────────────────────

    /** member가 새로 추가되면 1, 이미 있으면 score만 갱신하고 0 반환 */
    fun zadd(key: String, score: Double, member: String): Long {
        val entry = map.compute(key) { _, existing ->
            val live = if (existing != null && existing.expiresAt != null && System.currentTimeMillis() > existing.expiresAt) null else existing
            when {
                live == null -> Entry(ZSetValue(), null)
                live.value is ZSetValue -> live
                else -> throw WrongTypeException()
            }
        }!!
        val zset = entry.value as? ZSetValue ?: throw WrongTypeException()
        // computeIfAbsent on the inner map is atomic for the same key
        val isNew = zset.members.put(member, score) == null
        println("[STORE] ZADD $key  score=$score  member=$member  (new=$isNew, size=${zset.members.size})")
        return if (isNew) 1L else 0L
    }

    /** score 오름차순 기준 0-indexed 순위 반환, 없으면 null */
    fun zrank(key: String, member: String): Long? {
        val entry = getEntry(key) ?: return null
        if (entry.value !is ZSetValue) throw WrongTypeException()
        val rank = entry.value.members.entries.sortedBy { it.value }.indexOfFirst { it.key == member }
        return if (rank == -1) null else rank.toLong()
    }

    fun zcard(key: String): Long {
        val entry = getEntry(key) ?: return 0L
        if (entry.value !is ZSetValue) throw WrongTypeException()
        return entry.value.members.size.toLong()
    }

    /** score 가장 낮은 count개를 꺼내서 반환 (제거됨)
     *  Note: sort-then-remove is not atomic. Concurrent ZPOPMIN calls may observe
     *  the same snapshot before removal completes. Acceptable for single-scheduler use. */
    fun zpopmin(key: String, count: Int = 1): List<Pair<String, Double>> {
        val entry = getEntry(key) ?: return emptyList()
        if (entry.value !is ZSetValue) throw WrongTypeException()
        val popped = entry.value.members.entries.sortedBy { it.value }.take(count)
        popped.forEach { entry.value.members.remove(it.key) }
        println("[STORE] ZPOPMIN $key  count=$count  popped=${popped.map { it.key }}")
        return popped.map { it.key to it.value }
    }

    /** score 오름차순으로 전체 member 반환 */
    fun zrange(key: String): List<Pair<String, Double>> {
        val entry = getEntry(key) ?: return emptyList()
        if (entry.value !is ZSetValue) throw WrongTypeException()
        return entry.value.members.entries.sortedBy { it.value }.map { it.key to it.value }
    }

    // ── INCR / DECR ─────────────────────────────────────────────────────────

    fun incr(key: String): Long {
        var result = 0L
        map.compute(key) { _, existing ->
            val live = if (existing != null && existing.expiresAt != null && System.currentTimeMillis() > existing.expiresAt) null else existing
            if (live != null && live.value !is StringValue) throw WrongTypeException()
            val current = (live?.value as? StringValue)?.raw?.toLongOrNull() ?: 0L
            result = current + 1
            Entry(StringValue(result.toString()), live?.expiresAt)
        }
        println("[STORE] INCR $key  →  $result")
        return result
    }

    fun decr(key: String): Long {
        var result = 0L
        map.compute(key) { _, existing ->
            val live = if (existing != null && existing.expiresAt != null && System.currentTimeMillis() > existing.expiresAt) null else existing
            if (live != null && live.value !is StringValue) throw WrongTypeException()
            val current = (live?.value as? StringValue)?.raw?.toLongOrNull() ?: 0L
            result = current - 1
            Entry(StringValue(result.toString()), live?.expiresAt)
        }
        println("[STORE] DECR $key  →  $result")
        return result
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private fun getEntry(key: String): Entry? {
        val entry = map[key] ?: return null
        if (entry.expiresAt != null && System.currentTimeMillis() > entry.expiresAt) {
            if (map.remove(key, entry)) printDebugTable("EXPIRED $key")
            return null
        }
        return entry
    }

    private fun printDebugTable(action: String) {
        val now = System.currentTimeMillis()
        val rows = map.entries
            .mapNotNull { (key, entry) ->
                val sv = entry.value as? StringValue ?: return@mapNotNull null
                DebugRow(
                    key = key,
                    value = sv.raw,
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
