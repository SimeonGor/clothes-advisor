package ru.itmo.clothesadvisor.accountservice.controller.auth

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.accountservice.dto.auth.LoginRequest
import ru.itmo.clothesadvisor.accountservice.service.auth.AuthenticationService

@RestController
@RequestMapping("/api/auth")
internal class AuthController(private val authentication: AuthenticationService) {
    @PostMapping("/login")
    @Operation(summary = "Войти по логину и паролю")
    @SecurityRequirements
    fun login(@Valid @RequestBody request: LoginRequest) =
        authentication.login(request.login, request.password)
            .map { ResponseEntity.ok().header("Cache-Control", "no-store").body(it) }

    @GetMapping("/me")
    @Operation(summary = "Текущая учётная запись")
    fun me(@AuthenticationPrincipal jwt: Jwt) = authentication.currentAccount(jwt.subject!!.toLong())
}
