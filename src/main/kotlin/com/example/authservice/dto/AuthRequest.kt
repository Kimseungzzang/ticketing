package com.example.authservice.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class LoginRequest(
    @field:NotBlank(message = "아이디를 입력해 주세요")
    val id: String,

    @field:NotBlank(message = "비밀번호를 입력해 주세요")
    val password: String,
)

data class RegisterRequest(
    @field:NotBlank(message = "아이디를 입력해 주세요")
    @field:Size(min = 3, max = 20, message = "아이디는 3~20자여야 합니다")
    val id: String,

    @field:NotBlank(message = "이름을 입력해 주세요")
    @field:Size(max = 50, message = "이름은 50자 이하여야 합니다")
    val name: String,

    @field:NotBlank(message = "비밀번호를 입력해 주세요")
    @field:Size(min = 6, message = "비밀번호는 최소 6자여야 합니다")
    val password: String,
)
