package com.example.authservice.filter

import com.example.authservice.service.JwtService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthFilter(private val jwtService: JwtService) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ")) {
            val token = header.removePrefix("Bearer ")
            if (jwtService.isValidAccessToken(token)) {
                val auth = UsernamePasswordAuthenticationToken(
                    jwtService.getUserId(token),
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_USER")),
                ).also { it.details = mapOf("name" to jwtService.getName(token)) }
                SecurityContextHolder.getContext().authentication = auth
            }
        }
        chain.doFilter(request, response)
    }
}
