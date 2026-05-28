package com.example.authservice.service

import com.example.authservice.dto.AuthResponse
import com.example.authservice.dto.LoginRequest
import com.example.authservice.dto.LogoutResponse
import com.example.authservice.dto.RefreshResponse
import com.example.authservice.dto.RegisterRequest
import com.example.authservice.dto.UserInfo
import com.example.authservice.dto.VerifyResponse
import com.example.authservice.entity.UserEntity
import com.example.authservice.exception.DuplicateUserException
import com.example.authservice.exception.InvalidCredentialsException
import com.example.authservice.exception.InvalidTokenException
import com.example.authservice.exception.UserNotFoundException
import com.example.authservice.repository.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val jwtService: JwtService,
    private val tokenRedisService: TokenRedisService,
    private val passwordEncoder: PasswordEncoder,
) {
    @Transactional
    fun register(req: RegisterRequest): AuthResponse {
        // check-then-insert: duplicate userId throws DuplicateUserException.
        // Concurrent registrations with the same id are caught by the PK constraint (DataIntegrityViolationException),
        // which propagates as-is and is handled by GlobalExceptionHandler.
        if (userRepository.existsById(req.id)) throw DuplicateUserException(req.id)
        val user = userRepository.save(
            UserEntity(
                id = req.id,
                name = req.name,
                password = checkNotNull(passwordEncoder.encode(req.password)),
            )
        )
        return issueTokens(user)
    }

    @Transactional(readOnly = true)
    fun login(req: LoginRequest): AuthResponse {
        val user = userRepository.findById(req.id)
            .orElseThrow { InvalidCredentialsException("존재하지 않는 아이디입니다") }
        if (!passwordEncoder.matches(req.password, user.password)) {
            throw InvalidCredentialsException("비밀번호가 올바르지 않습니다")
        }
        return issueTokens(user)
    }

    fun refresh(refreshToken: String): RefreshResponse {
        val userId = jwtService.getUserIdAllowExpired(refreshToken)
            ?: throw InvalidTokenException("유효하지 않은 리프레시 토큰입니다")

        if (!jwtService.isValidRefreshToken(refreshToken)) {
            tokenRedisService.deleteTokens(userId)
            throw InvalidTokenException("리프레시 토큰이 만료되었거나 유효하지 않습니다")
        }

        val storedRefreshToken = tokenRedisService.getRefreshToken(userId)
        if (storedRefreshToken == null || storedRefreshToken != refreshToken) {
            tokenRedisService.deleteTokens(userId)
            throw InvalidTokenException("리프레시 토큰이 일치하지 않습니다")
        }

        val user = userRepository.findById(userId)
            .orElseThrow { UserNotFoundException(userId) }

        val newAccessToken = jwtService.generateAccessToken(user.id, user.name)
        val newRefreshToken = jwtService.generateRefreshToken(user.id, user.name)
        tokenRedisService.saveAccessToken(user.id, newAccessToken, jwtService.accessExpirationMillis())
        tokenRedisService.saveRefreshToken(user.id, newRefreshToken, jwtService.refreshExpirationMillis())
        return RefreshResponse(accessToken = newAccessToken)
    }

    fun logout(userId: String): LogoutResponse {
        tokenRedisService.deleteTokens(userId)
        return LogoutResponse()
    }

    @Transactional(readOnly = true)
    fun me(userId: String): UserInfo {
        val user = userRepository.findById(userId)
            .orElseThrow { UserNotFoundException(userId) }
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
        tokenRedisService.saveAccessToken(user.id, accessToken, jwtService.accessExpirationMillis())
        tokenRedisService.saveRefreshToken(user.id, refreshToken, jwtService.refreshExpirationMillis())
        return AuthResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            user = UserInfo(user.id, user.name),
        )
    }
}
