package com.example.authservice.controller

import com.example.authservice.service.RedisTokenStore
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@CrossOrigin(origins = ["*"])
@RestController
@RequestMapping("/api/debug")
class RedisDebugController(private val store: RedisTokenStore) {

    @GetMapping("/redis")
    fun redisState(): ResponseEntity<Map<String, Any>> {
        val keys = store.keysAll()
        println("[DEBUG] keysAll() 결과: $keys")
        val result = mutableMapOf<String, Any>()

        for (key in keys.sorted()) {
            when (store.type(key)) {
                "zset" -> {
                    val members = store.zrangeWithScores(key)
                    result[key] = mapOf(
                        "type" to "zset",
                        "members" to members.mapIndexed { i, (member, score) ->
                            mapOf("rank" to i + 1, "member" to member, "score" to score)
                        }
                    )
                }
                "string" -> {
                    val value = store.getKey(key) ?: continue
                    val ttl = store.ttlSec(key)
                    result[key] = mapOf(
                        "type" to "string",
                        "value" to value,
                        "ttl" to ttl,
                    )
                }
            }
        }

        return ResponseEntity.ok(result)
    }
}
