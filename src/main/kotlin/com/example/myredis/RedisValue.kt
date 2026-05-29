package com.example.myredis

import java.util.concurrent.ConcurrentHashMap

sealed class RedisValue

data class StringValue(val raw: String) : RedisValue()

class ZSetValue(
    val members: ConcurrentHashMap<String, Double> = ConcurrentHashMap()
) : RedisValue()

class WrongTypeException : RuntimeException("WRONGTYPE Operation against a key holding the wrong kind of value")
