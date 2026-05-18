package com.example.authservice.controller

import com.example.authservice.dto.LoginRequest
import com.example.authservice.dto.RegisterRequest
import com.example.authservice.service.AuthService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/register")
    fun register(@Valid @RequestBody req: RegisterRequest) =
        ResponseEntity.status(HttpStatus.CREATED).body(authService.register(req))

    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest) =
        ResponseEntity.ok(authService.login(req))

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal userId: String) =
        ResponseEntity.ok(authService.me(userId))

    @PostMapping("/verify")
    fun verify(@RequestBody body: Map<String, String>) =
        ResponseEntity.ok(authService.verify(body["token"] ?: ""))
}
