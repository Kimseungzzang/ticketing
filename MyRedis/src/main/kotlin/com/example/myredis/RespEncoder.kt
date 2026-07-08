package com.example.myredis

object RespEncoder {
    fun simpleString(value: String) = "+$value\r\n"
    fun error(message: String) = "-$message\r\n"
    fun integer(value: Long) = ":$value\r\n"
    fun bulkString(value: String) = "\$${value.length}\r\n$value\r\n"
    fun nullBulk() = "\$-1\r\n"
    fun array(items: List<String>): String {
        val sb = StringBuilder("*${items.size}\r\n")
        items.forEach { sb.append(bulkString(it)) }
        return sb.toString()
    }
}
