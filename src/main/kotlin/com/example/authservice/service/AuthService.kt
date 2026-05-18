package com.example.authservice.service

import com.example.authservice.dto.AuthResponse
import com.example.authservice.dto.LoginRequest
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
        return AuthResponse(
            accessToken = jwtService.generate(user.id, user.name),
            user = UserInfo(user.id, user.name),
        )
    }

    fun login(req: LoginRequest): AuthResponse {
        val user = userRepository.findById(req.id)
            .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "존재하지 않는 아이디입니다") }

        if (!passwordEncoder.matches(req.password, user.password)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "비밀번호가 올바르지 않습니다")
        }
        return AuthResponse(
            accessToken = jwtService.generate(user.id, user.name),
            user = UserInfo(user.id, user.name),
        )
    }

    fun me(userId: String): UserInfo {
        val user = userRepository.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다") }
        return UserInfo(user.id, user.name)
    }

    fun verify(token: String): VerifyResponse {
        if (!jwtService.isValid(token)) return VerifyResponse(valid = false)
        return VerifyResponse(
            valid = true,
            userId = jwtService.getUserId(token),
            name = jwtService.getName(token),
        )
    }
}
