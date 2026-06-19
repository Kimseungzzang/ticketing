package com.example.bookingservice.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class HeaderAuthFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val userId = request.getHeader("X-User-Id")
        if (userId.isNullOrBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing X-User-Id header")
            return
        }
        val auth = UsernamePasswordAuthenticationToken(userId, null, emptyList())
        SecurityContextHolder.getContext().authentication = auth
        filterChain.doFilter(request, response)
    }
}
