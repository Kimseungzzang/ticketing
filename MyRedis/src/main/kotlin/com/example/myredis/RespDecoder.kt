package com.example.myredis

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder

class RespDecoder : ByteToMessageDecoder() {

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        buf.markReaderIndex()
        val result = parseValue(buf)
        if (result == null) {
            buf.resetReaderIndex()
            return
        }
        @Suppress("UNCHECKED_CAST")
        out.add(result as List<String>)
    }

    private fun parseValue(buf: ByteBuf): Any? {
        if (!buf.isReadable) return null
        return when (buf.readByte().toInt().toChar()) {
            '*' -> parseArray(buf)
            '$' -> listOf(parseBulkString(buf) ?: return null)
            '+' -> listOf(readLine(buf) ?: return null)
            else -> null
        }
    }

    private fun parseArray(buf: ByteBuf): List<String>? {
        val count = readLine(buf)?.toIntOrNull() ?: return null
        if (count <= 0) return emptyList()
        val list = mutableListOf<String>()
        repeat(count) {
            if (!buf.isReadable) return null
            val type = buf.readByte().toInt().toChar()
            if (type != '$') return null
            list.add(parseBulkString(buf) ?: return null)
        }
        return list
    }

    private fun parseBulkString(buf: ByteBuf): String? {
        val length = readLine(buf)?.toIntOrNull() ?: return null
        if (length < 0) return ""
        if (buf.readableBytes() < length + 2) return null
        val bytes = ByteArray(length)
        buf.readBytes(bytes)
        buf.skipBytes(2)
        return String(bytes)
    }

    private fun readLine(buf: ByteBuf): String? {
        val sb = StringBuilder()
        while (buf.isReadable) {
            val ch = buf.readByte().toInt().toChar()
            if (ch == '\r') {
                if (!buf.isReadable) return null
                buf.readByte()
                return sb.toString()
            }
            sb.append(ch)
        }
        return null
    }
}
