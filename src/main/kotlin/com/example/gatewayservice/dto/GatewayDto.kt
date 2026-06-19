package com.example.gatewayservice.dto

data class VerifyRequest(val token: String)

data class VerifyResponse(
    val valid: Boolean,
    val userId: String? = null,
)
