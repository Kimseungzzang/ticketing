package com.example.queueservice.filter

import com.example.queueservice.dto.VerifyResponse
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.filter.OncePerRequestFilter

@Component
class AuthVerifyFilter(
    @Value("\${auth.service.url}") private val authServiceUrl: String,
) : OncePerRequestFilter() {

    private val restClient = RestClient.create()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authHeader = request.getHeader("Authorization")
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid Authorization header")
            return
        }

        val token = authHeader.substring(7)

        val verifyResponse = runCatching {
            restClient.get()
                .uri("$authServiceUrl/api/auth/verify?token=$token")
                .retrieve()
                .body(VerifyResponse::class.java)
        }.getOrElse {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Auth service unavailable")
            return
        }

        if (verifyResponse?.valid != true || verifyResponse.userId == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token")
            return
        }

        val authentication = UsernamePasswordAuthenticationToken(
            verifyResponse.userId, null, emptyList()
        )
        SecurityContextHolder.getContext().authentication = authentication
        filterChain.doFilter(request, response)
    }
}
