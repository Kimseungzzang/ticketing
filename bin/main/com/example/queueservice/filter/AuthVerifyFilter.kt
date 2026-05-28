package com.example.queueservice.filter

import com.example.queueservice.dto.VerifyRequest
import com.example.queueservice.dto.VerifyResponse
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.filter.OncePerRequestFilter

@Component
class AuthVerifyFilter(
    @Value("\${auth.service.url}") private val authServiceUrl: String,
) : OncePerRequestFilter() {

    companion object {
        private const val BEARER_PREFIX = "Bearer "
    }

    private val restClient = RestClient.create()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authHeader = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid Authorization header")
            return
        }

        val token = authHeader.removePrefix(BEARER_PREFIX)

        val verifyResponse = runCatching {
            restClient.post()
                .uri("$authServiceUrl/api/auth/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(VerifyRequest(token))
                .retrieve()
                .body(VerifyResponse::class.java)
        }.getOrElse { cause ->
            // Differentiate: 4xx = invalid token, 5xx/connection = auth-service unavailable
            if (cause is HttpClientErrorException && cause.statusCode == HttpStatus.UNAUTHORIZED) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token")
            } else {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Auth service unavailable")
            }
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
