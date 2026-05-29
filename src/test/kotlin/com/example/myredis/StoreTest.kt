package com.example.myredis

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {

    @BeforeEach
    fun clearStore() {
        // DEL all keys between tests via the public API
        Store.keys().forEach { Store.del(it) }
    }

    // ── String ──────────────────────────────────────────────────────────────

    @Test
    fun `set and get happy path`() {
        Store.set("k", "v")
        assertEquals("v", Store.get("k"))
    }

    @Test
    fun `get returns null for missing key`() {
        assertNull(Store.get("missing"))
    }

    @Test
    fun `get throws WrongTypeException on zset key`() {
        Store.zadd("zk", 1.0, "m")
        assertThrows<WrongTypeException> { Store.get("zk") }
    }

    @Test
    fun `set overwrites existing string key`() {
        Store.set("k", "old")
        Store.set("k", "new")
        assertEquals("new", Store.get("k"))
    }

    // ── SET NX ──────────────────────────────────────────────────────────────

    @Test
    fun `setNx succeeds when key absent`() {
        assertTrue(Store.setNx("nx", "v"))
        assertEquals("v", Store.get("nx"))
    }

    @Test
    fun `setNx fails when key present`() {
        Store.set("nx", "original")
        assertFalse(Store.setNx("nx", "other"))
        assertEquals("original", Store.get("nx"))
    }

    @Test
    fun `setNx succeeds after key has expired`() {
        Store.set("nx", "v", ttlMs = 1)
        Thread.sleep(5)
        assertTrue(Store.setNx("nx", "fresh"))
        assertEquals("fresh", Store.get("nx"))
    }

    // ── DEL ─────────────────────────────────────────────────────────────────

    @Test
    fun `del removes existing key`() {
        Store.set("k", "v")
        assertEquals(1, Store.del("k"))
        assertNull(Store.get("k"))
    }

    @Test
    fun `del returns 0 for missing key`() {
        assertEquals(0, Store.del("missing"))
    }

    // ── EXPIRE / TTL ─────────────────────────────────────────────────────────

    @Test
    fun `expire returns false for expired key`() {
        Store.set("k", "v", ttlMs = 1)
        Thread.sleep(5)
        assertFalse(Store.expire("k", 5000))
    }

    @Test
    fun `expire returns false for missing key`() {
        assertFalse(Store.expire("missing", 5000))
    }

    @Test
    fun `expire updates ttl on live key`() {
        Store.set("k", "v")
        assertTrue(Store.expire("k", 5000))
        val ttl = Store.ttl("k")
        assertTrue(ttl in 1..5)
    }

    @Test
    fun `get returns null after key expires`() {
        Store.set("k", "v", ttlMs = 1)
        Thread.sleep(5)
        assertNull(Store.get("k"))
    }

    // ── TYPE ─────────────────────────────────────────────────────────────────

    @Test
    fun `type returns string for string key`() {
        Store.set("k", "v")
        assertEquals("string", Store.type("k"))
    }

    @Test
    fun `type returns zset for zadd key`() {
        Store.zadd("z", 1.0, "m")
        assertEquals("zset", Store.type("z"))
    }

    @Test
    fun `type returns none for missing key`() {
        assertEquals("none", Store.type("missing"))
    }

    // ── EXISTS / KEYS ────────────────────────────────────────────────────────

    @Test
    fun `exists returns true for live key`() {
        Store.set("k", "v")
        assertTrue(Store.exists("k"))
    }

    @Test
    fun `keys includes both string and zset keys`() {
        Store.set("s", "v")
        Store.zadd("z", 1.0, "m")
        val keys = Store.keys()
        assertTrue("s" in keys)
        assertTrue("z" in keys)
    }

    // ── Sorted Set ───────────────────────────────────────────────────────────

    @Test
    fun `zadd returns 1 for new member`() {
        assertEquals(1L, Store.zadd("z", 1.0, "a"))
    }

    @Test
    fun `zadd returns 0 for updated member`() {
        Store.zadd("z", 1.0, "a")
        assertEquals(0L, Store.zadd("z", 2.0, "a"))
    }

    @Test
    fun `zadd throws WrongTypeException on string key`() {
        Store.set("k", "v")
        assertThrows<WrongTypeException> { Store.zadd("k", 1.0, "m") }
    }

    @Test
    fun `zrank returns correct rank`() {
        Store.zadd("z", 1.0, "a")
        Store.zadd("z", 2.0, "b")
        assertEquals(0L, Store.zrank("z", "a"))
        assertEquals(1L, Store.zrank("z", "b"))
    }

    @Test
    fun `zrank returns null for missing member`() {
        Store.zadd("z", 1.0, "a")
        assertNull(Store.zrank("z", "missing"))
    }

    @Test
    fun `zcard returns correct count`() {
        Store.zadd("z", 1.0, "a")
        Store.zadd("z", 2.0, "b")
        assertEquals(2L, Store.zcard("z"))
    }

    @Test
    fun `zpopmin removes and returns lowest score members`() {
        Store.zadd("z", 3.0, "c")
        Store.zadd("z", 1.0, "a")
        Store.zadd("z", 2.0, "b")
        val popped = Store.zpopmin("z", 2)
        assertEquals(listOf("a" to 1.0, "b" to 2.0), popped)
        assertEquals(1L, Store.zcard("z"))
    }

    @Test
    fun `zrange returns all members in score order`() {
        Store.zadd("z", 2.0, "b")
        Store.zadd("z", 1.0, "a")
        val members = Store.zrange("z")
        assertEquals(listOf("a" to 1.0, "b" to 2.0), members)
    }

    @Test
    fun `zrank throws WrongTypeException on string key`() {
        Store.set("k", "v")
        assertThrows<WrongTypeException> { Store.zrank("k", "m") }
    }

    // ── INCR / DECR ──────────────────────────────────────────────────────────

    @Test
    fun `incr initializes to 1 for missing key`() {
        assertEquals(1L, Store.incr("counter"))
    }

    @Test
    fun `incr increments existing value`() {
        Store.set("counter", "5")
        assertEquals(6L, Store.incr("counter"))
    }

    @Test
    fun `decr decrements existing value`() {
        Store.set("counter", "5")
        assertEquals(4L, Store.decr("counter"))
    }

    @Test
    fun `incr throws WrongTypeException on zset key`() {
        Store.zadd("z", 1.0, "m")
        assertThrows<WrongTypeException> { Store.incr("z") }
    }

    @Test
    fun `decr throws WrongTypeException on zset key`() {
        Store.zadd("z", 1.0, "m")
        assertThrows<WrongTypeException> { Store.decr("z") }
    }
}
