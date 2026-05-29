package com.example.myredis

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler

class CommandHandler : SimpleChannelInboundHandler<List<String>>() {

    override fun channelRead0(ctx: ChannelHandlerContext, args: List<String>) {
        if (args.isEmpty()) {
            ctx.writeAndFlush(RespEncoder.error("ERR empty command"))
            return
        }
        val response = try {
            when (args[0].uppercase()) {
                "PING"    -> if (args.size > 1) RespEncoder.bulkString(args[1]) else RespEncoder.simpleString("PONG")
                "SET"     -> handleSet(args)
                "GET"     -> handleGet(args)
                "DEL"     -> handleDel(args)
                "EXPIRE"  -> handleExpire(args)
                "TTL"     -> handleTtl(args)
                "EXISTS"  -> handleExists(args)
                "KEYS"    -> handleKeys()
                "TYPE"    -> handleType(args)
                "ZADD"    -> handleZadd(args)
                "ZRANK"   -> handleZrank(args)
                "ZCARD"   -> handleZcard(args)
                "ZPOPMIN" -> handleZpopmin(args)
                "ZRANGE"  -> handleZrange(args)
                "INCR"    -> handleIncr(args)
                "DECR"    -> handleDecr(args)
                "COMMAND" -> RespEncoder.simpleString("OK")
                else      -> RespEncoder.error("ERR unknown command '${args[0]}'")
            }
        } catch (e: WrongTypeException) {
            RespEncoder.error(e.message ?: "WRONGTYPE error")
        }
        ctx.writeAndFlush(response)
    }

    private fun handleSet(args: List<String>): String {
        if (args.size < 3) return RespEncoder.error("ERR wrong number of arguments for 'set'")
        val key = args[1]
        val value = args[2]
        var ttlMs: Long? = null
        var nx = false
        var i = 3
        while (i < args.size) {
            when (args[i].uppercase()) {
                "EX" -> ttlMs = (args.getOrNull(++i)?.toLongOrNull()
                    ?: return RespEncoder.error("ERR invalid expire time")) * 1000
                "PX" -> ttlMs = args.getOrNull(++i)?.toLongOrNull()
                    ?: return RespEncoder.error("ERR invalid expire time")
                "NX" -> nx = true
            }
            i++
        }
        return if (nx) {
            if (Store.setNx(key, value, ttlMs)) RespEncoder.simpleString("OK")
            else RespEncoder.nullBulk()
        } else {
            Store.set(key, value, ttlMs)
            RespEncoder.simpleString("OK")
        }
    }

    private fun handleGet(args: List<String>): String {
        if (args.size < 2) return RespEncoder.error("ERR wrong number of arguments for 'get'")
        val value = Store.get(args[1]) ?: return RespEncoder.nullBulk()
        return RespEncoder.bulkString(value)
    }

    private fun handleDel(args: List<String>): String {
        if (args.size < 2) return RespEncoder.error("ERR wrong number of arguments for 'del'")
        return RespEncoder.integer(Store.del(*args.drop(1).toTypedArray()).toLong())
    }

    private fun handleExpire(args: List<String>): String {
        if (args.size < 3) return RespEncoder.error("ERR wrong number of arguments for 'expire'")
        val ttlSec = args[2].toLongOrNull() ?: return RespEncoder.error("ERR value is not an integer")
        return RespEncoder.integer(if (Store.expire(args[1], ttlSec * 1000)) 1L else 0L)
    }

    private fun handleTtl(args: List<String>): String {
        if (args.size < 2) return RespEncoder.error("ERR wrong number of arguments for 'ttl'")
        return RespEncoder.integer(Store.ttl(args[1]))
    }

    private fun handleExists(args: List<String>): String {
        if (args.size < 2) return RespEncoder.error("ERR wrong number of arguments for 'exists'")
        return RespEncoder.integer(if (Store.exists(args[1])) 1L else 0L)
    }

    private fun handleKeys(): String = RespEncoder.array(Store.keys().toList())

    // ── Type ────────────────────────────────────────────────────────────────

    private fun handleType(args: List<String>): String {
        if (args.size < 2) return RespEncoder.error("ERR wrong number of arguments for 'type'")
        return RespEncoder.simpleString(Store.type(args[1]))
    }

    // ── Sorted Set ──────────────────────────────────────────────────────────

    // ZADD key score member
    private fun handleZadd(args: List<String>): String {
        if (args.size < 4) return RespEncoder.error("ERR wrong number of arguments for 'zadd'")
        val score = args[2].toDoubleOrNull() ?: return RespEncoder.error("ERR value is not a float")
        return RespEncoder.integer(Store.zadd(args[1], score, args[3]))
    }

    // ZRANK key member  →  integer(rank) or null bulk
    private fun handleZrank(args: List<String>): String {
        if (args.size < 3) return RespEncoder.error("ERR wrong number of arguments for 'zrank'")
        val rank = Store.zrank(args[1], args[2]) ?: return RespEncoder.nullBulk()
        return RespEncoder.integer(rank)
    }

    // ZCARD key  →  integer
    private fun handleZcard(args: List<String>): String {
        if (args.size < 2) return RespEncoder.error("ERR wrong number of arguments for 'zcard'")
        return RespEncoder.integer(Store.zcard(args[1]))
    }

    // ZPOPMIN key [count]  →  array of [member, score, member, score, ...]
    private fun handleZpopmin(args: List<String>): String {
        if (args.size < 2) return RespEncoder.error("ERR wrong number of arguments for 'zpopmin'")
        val count = args.getOrNull(2)?.toIntOrNull() ?: 1
        val popped = Store.zpopmin(args[1], count)
        val flat = popped.flatMap { (member, score) -> listOf(member, score.toLong().toString()) }
        return RespEncoder.array(flat)
    }

    // ZRANGE key 0 -1 [WITHSCORES]
    private fun handleZrange(args: List<String>): String {
        if (args.size < 4) return RespEncoder.error("ERR wrong number of arguments for 'zrange'")
        val withScores = args.getOrNull(4)?.uppercase() == "WITHSCORES"
        val members = Store.zrange(args[1])
        val flat = if (withScores) {
            members.flatMap { (member, score) -> listOf(member, score.toLong().toString()) }
        } else {
            members.map { it.first }
        }
        return RespEncoder.array(flat)
    }

    // ── INCR / DECR ─────────────────────────────────────────────────────────

    private fun handleIncr(args: List<String>): String {
        if (args.size < 2) return RespEncoder.error("ERR wrong number of arguments for 'incr'")
        return RespEncoder.integer(Store.incr(args[1]))
    }

    private fun handleDecr(args: List<String>): String {
        if (args.size < 2) return RespEncoder.error("ERR wrong number of arguments for 'decr'")
        return RespEncoder.integer(Store.decr(args[1]))
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        cause.printStackTrace()
        ctx.close()
    }
}
