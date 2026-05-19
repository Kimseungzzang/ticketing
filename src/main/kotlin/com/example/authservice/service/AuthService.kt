package com.example.authservice.service

import com.example.authservice.dto.AuthResponse
import com.example.authservice.dto.LoginRequest
import com.example.authservice.dto.RefreshResponse
import com.example.authservice.dto.RegisterRequest
import com.example.authservice.dto.UserInfo
import com.example.authservice.dto.VerifyResponse
import com.example.authservice.entity.UserEntity
import com.example.authservice.repository.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val jwtService: JwtService,
    private val redisTokenStore: RedisTokenStore,
    private val passwordEncoder: PasswordEncoder,
) {
    @Transactional
    fun register(req: RegisterRequest): AuthResponse {
        if (userRepository.existsById(req.id)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다: ${req.id}")
        }
        val user = userRepository.save(
            UserEntity(
                id = req.id,
                name = req.name,
                password = checkNotNull(passwordEncoder.encode(req.password)) { "Password encoding failed" },
            )
        )
        return issueTokens(user)
    }

    fun login(req: LoginRequest): AuthResponse {
        val user = userRepository.findById(req.id)
            .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "존재하지 않는 아이디입니다") }

        if (!passwordEncoder.matches(req.password, user.password)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "비밀번호가 올바르지 않습니다")
        }
        return issueTokens(user)
    }

    fun refresh(refreshToken: String): RefreshResponse {
        val userId = jwtService.getUserIdAllowExpired(refreshToken)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다")

        if (!jwtService.isValidRefreshToken(refreshToken)) {
            redisTokenStore.deleteTokens(userId)
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "리프레시 토큰이 만료되었거나 유효하지 않습니다")
        }

        val storedRefreshToken = redisTokenStore.getRefreshToken(userId)
        if (storedRefreshToken == null || storedRefreshToken != refreshToken) {
            redisTokenStore.deleteTokens(userId)
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "리프레시 토큰이 일치하지 않습니다")
        }

        val user = userRepository.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다") }

        val newAccessToken = jwtService.generateAccessToken(user.id, user.name)
        redisTokenStore.saveAccessToken(user.id, newAccessToken, jwtService.accessExpirationMillis())
        return RefreshResponse(accessToken = newAccessToken)
    }

    fun logout(userId: String): Map<String, String> {
        redisTokenStore.deleteTokens(userId)
        return mapOf("message" to "로그아웃되었습니다")
    }

    fun me(userId: String): UserInfo {
        val user = userRepository.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다") }
        return UserInfo(user.id, user.name)
    }

    fun verify(token: String): VerifyResponse {
        if (!jwtService.isValidAccessToken(token)) return VerifyResponse(valid = false)
        return VerifyResponse(
            valid = true,
            userId = jwtService.getUserId(token),
            name = jwtService.getName(token),
        )
    }

    private fun issueTokens(user: UserEntity): AuthResponse {
        val accessToken = jwtService.generateAccessToken(user.id, user.name)
        val refreshToken = jwtService.generateRefreshToken(user.id, user.name)
        redisTokenStore.saveAccessToken(user.id, accessToken, jwtService.accessExpirationMillis())
        redisTokenStore.saveRefreshToken(user.id, refreshToken, jwtService.refreshExpirationMillis())

        return AuthResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            user = UserInfo(user.id, user.name),
        )
    }
}
