package com.example.myredis

import java.util.concurrent.ConcurrentHashMap

object Store {
    private data class Entry(val value: String, val expiresAt: Long?)
    private data class DebugRow(val key: String, val value: String, val ttl: String)

    private val map = ConcurrentHashMap<String, Entry>()

    // Sorted Set: key -> (member -> score)
    private val sortedSets = ConcurrentHashMap<String, ConcurrentHashMap<String, Double>>()

    fun set(key: String, value: String, ttlMs: Long? = null) {
        map[key] = Entry(value, ttlMs?.let { System.currentTimeMillis() + it })
        printDebugTable("SET $key")
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

    fun del(vararg keys: String): Int {
        val deleted = keys.count { map.remove(it) != null }
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
        val stringKeys = map.entries
            .filter { (_, v) -> v.expiresAt == null || v.expiresAt > now }
            .map { it.key }
        val sortedSetKeys = sortedSets.keys.toList()
        return (stringKeys + sortedSetKeys).toSet()
    }

    /** 키 타입 반환: "string" | "zset" | "none" */
    fun type(key: String): String = when {
        sortedSets.containsKey(key) -> "zset"
        map.containsKey(key)        -> "string"
        else                        -> "none"
    }

    /** score 오름차순으로 전체 member 반환 */
    fun zrange(key: String): List<Pair<String, Double>> {
        val set = sortedSets[key] ?: return emptyList()
        return set.entries.sortedBy { it.value }.map { it.key to it.value }
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
        // computeIfAbsent is atomic; prevents two threads from creating separate maps for the same key
        val set = sortedSets.computeIfAbsent(key) { ConcurrentHashMap() }
        val isNew = set.put(member, score) == null
        println("[STORE] ZADD $key  score=$score  member=$member  (new=$isNew, size=${set.size})")
        return if (isNew) 1L else 0L
    }

    /** score 오름차순 기준 0-indexed 순위 반환, 없으면 null */
    fun zrank(key: String, member: String): Long? {
        val set = sortedSets[key] ?: return null
        val rank = set.entries.sortedBy { it.value }.indexOfFirst { it.key == member }
        return if (rank == -1) null else rank.toLong()
    }

    fun zcard(key: String): Long = sortedSets[key]?.size?.toLong() ?: 0L

    /** score 가장 낮은 count개를 꺼내서 반환 (제거됨)
     *  Note: sort-then-remove is not atomic. Concurrent ZPOPMIN calls may observe
     *  the same snapshot before removal completes. Acceptable for single-scheduler use. */
    fun zpopmin(key: String, count: Int = 1): List<Pair<String, Double>> {
        val set = sortedSets[key] ?: return emptyList()
        val popped = set.entries.sortedBy { it.value }.take(count)
        popped.forEach { set.remove(it.key) }
        println("[STORE] ZPOPMIN $key  count=$count  popped=${popped.map { it.key }}")
        return popped.map { it.key to it.value }
    }

    // ── INCR / DECR ─────────────────────────────────────────────────────────

    fun incr(key: String): Long {
        var result = 0L
        map.compute(key) { _, existing ->
            val current = existing?.value?.toLongOrNull() ?: 0L
            result = current + 1
            Entry(result.toString(), existing?.expiresAt)
        }
        println("[STORE] INCR $key  →  $result")
        return result
    }

    fun decr(key: String): Long {
        var result = 0L
        map.compute(key) { _, existing ->
            val current = existing?.value?.toLongOrNull() ?: 0L
            result = current - 1
            Entry(result.toString(), existing?.expiresAt)
        }
        println("[STORE] DECR $key  →  $result")
        return result
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
