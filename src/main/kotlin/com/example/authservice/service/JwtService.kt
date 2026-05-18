package com.example.authservice.service

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Date

@Service
class JwtService(
    @Value("\${jwt.secret}") secret: String,
    @Value("\${jwt.expiration-ms:86400000}") private val expirationMs: Long,
) {
    private val key = Keys.hmacShaKeyFor(secret.toByteArray(Charsets.UTF_8))

    fun generate(userId: String, name: String): String =
        Jwts.builder()
            .subject(userId)
            .claim("name", name)
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

    fun getUserId(token: String): String = getClaims(token).subject

    fun getName(token: String): String = getClaims(token)["name", String::class.java]
}
