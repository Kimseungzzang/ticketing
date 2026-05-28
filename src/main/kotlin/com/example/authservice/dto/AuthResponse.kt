package com.example.authservice.dto

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val user: UserInfo,
)

data class RefreshResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
)

data class UserInfo(
    val id: String,
    val name: String,
)

data class VerifyResponse(
    val valid: Boolean,
    val userId: String? = null,
    val name: String? = null,
)

data class LogoutResponse(val message: String = "로그아웃되었습니다")
