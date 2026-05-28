package com.example.authservice.exception

sealed class AuthException(message: String) : RuntimeException(message)

class DuplicateUserException(id: String) : AuthException("이미 사용 중인 아이디입니다: $id")
class InvalidCredentialsException(message: String = "아이디 또는 비밀번호가 올바르지 않습니다") : AuthException(message)
class InvalidTokenException(message: String = "유효하지 않은 토큰입니다") : AuthException(message)
class UserNotFoundException(id: String) : AuthException("사용자를 찾을 수 없습니다: $id")
