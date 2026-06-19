package com.example.gatewayservice.filter

import com.example.gatewayservice.dto.VerifyRequest
import com.example.gatewayservice.dto.VerifyResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class AuthGlobalFilter(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${auth.service.url}") private val authServiceUrl: String,
    @Value("\${auth.public-paths}") private val publicPaths: List<String>,
) : GlobalFilter, Ordered {

    override fun getOrder(): Int = -1

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val path = exchange.request.path.value()

        if (publicPaths.any { path.startsWith(it) }) {
            return chain.filter(exchange)
        }

        val authHeader = exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange)
        }

        val token = authHeader.removePrefix("Bearer ")

        return webClientBuilder.build()
            .post()
            .uri("$authServiceUrl/api/auth/verify")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(VerifyRequest(token))
            .retrieve()
            .bodyToMono(VerifyResponse::class.java)
            .flatMap { verifyResponse ->
                if (verifyResponse?.valid == true && verifyResponse.userId != null) {
                    val mutatedRequest = exchange.request.mutate()
                        .header("X-User-Id", verifyResponse.userId)
                        .build()
                    chain.filter(exchange.mutate().request(mutatedRequest).build())
                } else {
                    unauthorized(exchange)
                }
            }
            .onErrorResume {
                exchange.response.statusCode = HttpStatus.SERVICE_UNAVAILABLE
                exchange.response.setComplete()
            }
    }

    private fun unauthorized(exchange: ServerWebExchange): Mono<Void> {
        exchange.response.statusCode = HttpStatus.UNAUTHORIZED
        return exchange.response.setComplete()
    }
}
