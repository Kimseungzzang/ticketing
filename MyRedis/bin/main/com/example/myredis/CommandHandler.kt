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

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        cause.printStackTrace()
        ctx.close()
    }
}
