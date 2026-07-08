package com.example.myredis

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler

class CommandHandler : SimpleChannelInboundHandler<List<String>>() {

    override fun channelRead0(ctx: ChannelHandlerContext, args: List<String>) {
        if (args.isEmpty()) {
            ctx.writeAndFlush(RespEncoder.error("ERR empty command"))
            return
        }
        val response = when (args[0].uppercase()) {
            "PING"    -> if (args.size > 1) RespEncoder.bulkString(args[1]) else RespEncoder.simpleString("PONG")
            "SET"     -> handleSet(args)
            "GET"     -> handleGet(args)
            "DEL"     -> handleDel(args)
            "EXPIRE"  -> handleExpire(args)
            "TTL"     -> handleTtl(args)
            "EXISTS"  -> handleExists(args)
            "KEYS"    -> handleKeys()
            "ZADD"    -> handleZadd(args)
            "ZRANK"   -> handleZrank(args)
            "ZCARD"   -> handleZcard(args)
            "ZSCORE"  -> handleZscore(args)
            "ZREM"    -> handleZrem(args)
            "ZPOPMIN" -> handleZpopmin(args)
            "ZRANGE"  -> handleZrange(args)
            "COMMAND" -> RespEncoder.simpleString("OK")
            else      -> RespEncoder.error("ERR unknown command '${args[0]}'")
        }
        ctx.writeAndFlush(response)
    }

    private fun handleSet(args: List<String>): String {
        if (args.size < 3) return RespEncoder.error("ERR wrong number of arguments for 'set'")
        val key = args[1]
        val value = args[2]
        var ttlMs: Long? = null
        var i = 3
        while (i < args.size) {
            when (args[i].uppercase()) {
                "EX" -> ttlMs = (args.getOrNull(++i)?.toLongOrNull()
                    ?: return RespEncoder.error("ERR invalid expire time")) * 1000
                "PX" -> ttlMs = args.getOrNull(++i)?.toLongOrNull()
                    ?: return RespEncoder.error("ERR invalid expire time")
            }
            i++
        }
        Store.set(key, value, ttlMs)
        return RespEncoder.simpleString("OK")
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

    // ── Sorted Set 명령 (skip list 기반) ──
    private fun fmtScore(s: Double): String =
        if (s == s.toLong().toDouble()) s.toLong().toString() else s.toString()

    private fun handleZadd(args: List<String>): String {
        if (args.size < 4 || args.size % 2 != 0) return RespEncoder.error("ERR wrong number of arguments for 'zadd'")
        var added = 0
        var i = 2
        while (i + 1 < args.size) {
            val score = args[i].toDoubleOrNull() ?: return RespEncoder.error("ERR value is not a valid float")
            added += Store.zadd(args[1], score, args[i + 1])
            i += 2
        }
        return RespEncoder.integer(added.toLong())
    }

    private fun handleZrank(args: List<String>): String {
        if (args.size < 3) return RespEncoder.error("ERR wrong number of arguments for 'zrank'")
        val r = Store.zrank(args[1], args[2]) ?: return RespEncoder.nullBulk()
        return RespEncoder.integer(r)
    }

    private fun handleZcard(args: List<String>): String {
        if (args.size < 2) return RespEncoder.error("ERR wrong number of arguments for 'zcard'")
        return RespEncoder.integer(Store.zcard(args[1]).toLong())
    }

    private fun handleZscore(args: List<String>): String {
        if (args.size < 3) return RespEncoder.error("ERR wrong number of arguments for 'zscore'")
        val s = Store.zscore(args[1], args[2]) ?: return RespEncoder.nullBulk()
        return RespEncoder.bulkString(fmtScore(s))
    }

    private fun handleZrem(args: List<String>): String {
        if (args.size < 3) return RespEncoder.error("ERR wrong number of arguments for 'zrem'")
        return RespEncoder.integer(Store.zrem(args[1], args[2]).toLong())
    }

    private fun handleZpopmin(args: List<String>): String {
        if (args.size < 2) return RespEncoder.error("ERR wrong number of arguments for 'zpopmin'")
        val n = args.getOrNull(2)?.toIntOrNull() ?: 1
        val flat = Store.zpopmin(args[1], n).flatMap { listOf(it.first, fmtScore(it.second)) }
        return RespEncoder.array(flat)
    }

    private fun handleZrange(args: List<String>): String {
        if (args.size < 4) return RespEncoder.error("ERR wrong number of arguments for 'zrange'")
        val start = args[2].toLongOrNull() ?: return RespEncoder.error("ERR value is not an integer")
        val end = args[3].toLongOrNull() ?: return RespEncoder.error("ERR value is not an integer")
        return RespEncoder.array(Store.zrange(args[1], start, end).map { it.first })
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        cause.printStackTrace()
        ctx.close()
    }
}
