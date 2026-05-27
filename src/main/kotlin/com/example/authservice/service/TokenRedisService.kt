package com.example.authservice.service

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class TokenRedisService(private val redisTokenStore: RedisTokenStore) {

    companion object {
        private fun accessKey(userId: String) = "auth:jwt:access:$userId"
        private fun refreshKey(userId: String) = "auth:jwt:refresh:$userId"
    }

    fun saveAccessToken(userId: String, token: String, ttlMs: Long) {
        val saved = runCatching {
            redisTokenStore.setKey(accessKey(userId), token, ttlMs / 1000)
        }.isSuccess
        if (!saved) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis에 액세스 토큰 저장 실패")
    }

    fun saveRefreshToken(userId: String, token: String, ttlMs: Long) {
        val saved = runCatching {
            redisTokenStore.setKey(refreshKey(userId), token, ttlMs / 1000)
        }.isSuccess
        if (!saved) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis에 리프레시 토큰 저장 실패")
    }

    fun getRefreshToken(userId: String): String? =
        redisTokenStore.getKey(refreshKey(userId))

    fun deleteTokens(userId: String) {
        redisTokenStore.delKey(accessKey(userId), refreshKey(userId))
    }
}
