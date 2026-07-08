package com.example.myredis

import java.util.concurrent.ConcurrentHashMap

object Store {
    private data class Entry(val value: String, val expiresAt: Long?)
    private data class DebugRow(val key: String, val value: String, val ttl: String)

    private val map = ConcurrentHashMap<String, Entry>()

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
        return map.entries
            .filter { (_, v) -> v.expiresAt == null || v.expiresAt > now }
            .map { it.key }
            .toSet()
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
