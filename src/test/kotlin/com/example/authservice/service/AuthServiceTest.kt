package com.example.authservice.service

import com.example.authservice.dto.LoginRequest
import com.example.authservice.dto.RegisterRequest
import com.example.authservice.entity.UserEntity
import com.example.authservice.exception.DuplicateUserException
import com.example.authservice.exception.InvalidCredentialsException
import com.example.authservice.exception.InvalidTokenException
import com.example.authservice.exception.UserNotFoundException
import com.example.authservice.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class AuthServiceTest {

    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var jwtService: JwtService
    @Mock lateinit var tokenRedisService: TokenRedisService
    @Mock lateinit var passwordEncoder: PasswordEncoder

    @InjectMocks lateinit var authService: AuthService

    private val user = UserEntity(id = "user1", name = "테스터", password = "encoded")

    // register

    @Test
    fun `register - 새 사용자 정상 등록`() {
        whenever(userRepository.existsById("user1")).thenReturn(false)
        whenever(passwordEncoder.encode("pass123")).thenReturn("encoded")
        whenever(userRepository.save(any())).thenReturn(user)
        whenever(jwtService.generateAccessToken(any(), any())).thenReturn("access")
        whenever(jwtService.generateRefreshToken(any(), any())).thenReturn("refresh")
        whenever(jwtService.accessExpirationMillis()).thenReturn(300_000L)
        whenever(jwtService.refreshExpirationMillis()).thenReturn(900_000L)

        val result = authService.register(RegisterRequest("user1", "테스터", "pass123"))

        assertThat(result.accessToken).isEqualTo("access")
        assertThat(result.user.id).isEqualTo("user1")
    }

    @Test
    fun `register - 중복 아이디면 DuplicateUserException`() {
        whenever(userRepository.existsById("user1")).thenReturn(true)

        assertThatThrownBy { authService.register(RegisterRequest("user1", "테스터", "pass123")) }
            .isInstanceOf(DuplicateUserException::class.java)
    }

    // login

    @Test
    fun `login - 정상 로그인`() {
        whenever(userRepository.findById("user1")).thenReturn(Optional.of(user))
        whenever(passwordEncoder.matches("pass123", "encoded")).thenReturn(true)
        whenever(jwtService.generateAccessToken(any(), any())).thenReturn("access")
        whenever(jwtService.generateRefreshToken(any(), any())).thenReturn("refresh")
        whenever(jwtService.accessExpirationMillis()).thenReturn(300_000L)
        whenever(jwtService.refreshExpirationMillis()).thenReturn(900_000L)

        val result = authService.login(LoginRequest("user1", "pass123"))

        assertThat(result.accessToken).isEqualTo("access")
    }

    @Test
    fun `login - 존재하지 않는 아이디면 InvalidCredentialsException`() {
        whenever(userRepository.findById("unknown")).thenReturn(Optional.empty())

        assertThatThrownBy { authService.login(LoginRequest("unknown", "pass")) }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    @Test
    fun `login - 비밀번호 불일치면 InvalidCredentialsException`() {
        whenever(userRepository.findById("user1")).thenReturn(Optional.of(user))
        whenever(passwordEncoder.matches("wrong", "encoded")).thenReturn(false)

        assertThatThrownBy { authService.login(LoginRequest("user1", "wrong")) }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }

    // refresh

    @Test
    fun `refresh - 유효하지 않은 토큰이면 InvalidTokenException`() {
        whenever(jwtService.getUserIdAllowExpired("bad")).thenReturn(null)

        assertThatThrownBy { authService.refresh("bad") }
            .isInstanceOf(InvalidTokenException::class.java)
    }

    @Test
    fun `refresh - Redis 저장 토큰과 불일치하면 InvalidTokenException`() {
        whenever(jwtService.getUserIdAllowExpired("token")).thenReturn("user1")
        whenever(jwtService.isValidRefreshToken("token")).thenReturn(true)
        whenever(tokenRedisService.getRefreshToken("user1")).thenReturn("other")

        assertThatThrownBy { authService.refresh("token") }
            .isInstanceOf(InvalidTokenException::class.java)
    }

    // verify

    @Test
    fun `verify - 유효한 액세스 토큰이면 valid=true 반환`() {
        whenever(jwtService.isValidAccessToken("token")).thenReturn(true)
        whenever(jwtService.getUserId("token")).thenReturn("user1")
        whenever(jwtService.getName("token")).thenReturn("테스터")

        val result = authService.verify("token")

        assertThat(result.valid).isTrue()
        assertThat(result.userId).isEqualTo("user1")
    }

    @Test
    fun `verify - 유효하지 않은 토큰이면 valid=false 반환`() {
        whenever(jwtService.isValidAccessToken("bad")).thenReturn(false)

        val result = authService.verify("bad")

        assertThat(result.valid).isFalse()
        assertThat(result.userId).isNull()
    }

    // logout

    @Test
    fun `logout - 정상 로그아웃`() {
        val result = authService.logout("user1")
        assertThat(result.message).isNotBlank()
    }

    // me

    @Test
    fun `me - 존재하지 않는 사용자면 UserNotFoundException`() {
        whenever(userRepository.findById("ghost")).thenReturn(Optional.empty())

        assertThatThrownBy { authService.me("ghost") }
            .isInstanceOf(UserNotFoundException::class.java)
    }
}
