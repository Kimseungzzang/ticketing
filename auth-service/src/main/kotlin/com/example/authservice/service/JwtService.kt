package com.example.authservice.service

import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Date

@Service
class JwtService(
    @Value("\${jwt.secret}") secret: String,
    @Value("\${jwt.access-expiration-ms:300000}") private val accessExpirationMs: Long,
    @Value("\${jwt.refresh-expiration-ms:900000}") private val refreshExpirationMs: Long,
) {
    companion object {
        private const val CLAIM_NAME = "name"
        private const val CLAIM_TYPE = "type"
        private const val ACCESS = "access"
        private const val REFRESH = "refresh"
    }

    private val key = Keys.hmacShaKeyFor(secret.toByteArray(Charsets.UTF_8))

    fun generateAccessToken(userId: String, name: String): String =
        generateToken(userId, name, ACCESS, accessExpirationMs)

    fun generateRefreshToken(userId: String, name: String): String =
        generateToken(userId, name, REFRESH, refreshExpirationMs)

    private fun generateToken(userId: String, name: String, type: String, expirationMs: Long): String =
        Jwts.builder()
            .subject(userId)
            .claim(CLAIM_NAME, name)
            .claim(CLAIM_TYPE, type)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + expirationMs))
            .signWith(key)
            .compact()

    fun getClaims(token: String): Claims =
        Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload

    fun isValid(token: String): Boolean = runCatching { getClaims(token) }.isSuccess

    fun isValidAccessToken(token: String): Boolean =
        runCatching { getTokenType(token) == ACCESS }.getOrDefault(false)

    fun isValidRefreshToken(token: String): Boolean =
        runCatching { getTokenType(token) == REFRESH }.getOrDefault(false)

    fun getUserId(token: String): String = getClaims(token).subject

    fun getUserIdAllowExpired(token: String): String? =
        runCatching { getUserId(token) }
            .recoverCatching { cause ->
                if (cause is ExpiredJwtException) {
                    cause.claims.subject
                } else {
                    throw cause
                }
            }
            .getOrNull()

    fun getName(token: String): String = getClaims(token)[CLAIM_NAME, String::class.java]

    fun getTokenType(token: String): String = getClaims(token)[CLAIM_TYPE, String::class.java]

    fun accessExpirationMillis(): Long = accessExpirationMs

    fun refreshExpirationMillis(): Long = refreshExpirationMs
}
