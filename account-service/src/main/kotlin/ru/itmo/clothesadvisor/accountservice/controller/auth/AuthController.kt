package ru.itmo.clothesadvisor.accountservice.controller.auth

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpHeaders
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.accountservice.dto.auth.LoginRequest
import ru.itmo.clothesadvisor.accountservice.service.auth.AuthenticationService
import ru.itmo.clothesadvisor.accountservice.service.auth.SessionAuthentication
import ru.itmo.clothesadvisor.accountservice.config.RefreshCookie

@RestController
@RequestMapping("/api/auth")
internal class AuthController(
    private val authentication: AuthenticationService,
    private val cookies: RefreshCookie,
) {
    @PostMapping("/login")
    @Operation(summary = "Войти по логину и паролю")
    @Parameter(name = "X-Auth-Request", `in` = ParameterIn.HEADER, required = true,
        schema = Schema(type = "string", allowableValues = ["1"]))
    @ApiResponse(responseCode = "200", headers = [Header(name = "Set-Cookie",
        description = "HttpOnly refresh cookie; Secure; SameSite=Strict; Path=/api/auth")])
    @SecurityRequirements
    fun login(@Valid @RequestBody request: LoginRequest) =
        authentication.login(request.login, request.password)
            .map(::authenticated)

    @PostMapping("/refresh")
    @Operation(summary = "Обновить сессию")
    @Parameter(name = "X-Auth-Request", `in` = ParameterIn.HEADER, required = true,
        schema = Schema(type = "string", allowableValues = ["1"]))
    @ApiResponse(responseCode = "200", headers = [Header(name = "Set-Cookie",
        description = "Rotated HttpOnly refresh cookie with the original session expiry")])
    @SecurityRequirements
    fun refresh(@Parameter(description = "HttpOnly refresh cookie set by login")
        @CookieValue(name = RefreshCookie.NAME, required = false) token: String?) =
        authentication.refresh(token).map(::authenticated)

    @PostMapping("/logout")
    @Operation(summary = "Завершить текущую сессию")
    @Parameter(name = "X-Auth-Request", `in` = ParameterIn.HEADER, required = true,
        schema = Schema(type = "string", allowableValues = ["1"]))
    @ApiResponse(responseCode = "204", headers = [Header(name = "Set-Cookie",
        description = "Clears the HttpOnly refresh cookie")])
    @SecurityRequirements
    fun logout(@Parameter(description = "HttpOnly refresh cookie identifying the session to revoke")
        @CookieValue(name = RefreshCookie.NAME, required = false) token: String?) =
        authentication.logout(token).thenReturn(ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store").build<Void>())

    private fun authenticated(result: SessionAuthentication) = ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookies.create(result.refreshToken, result.expiresAt).toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store").body(result.response)

    @GetMapping("/me")
    @Operation(summary = "Текущая учётная запись")
    fun me(@AuthenticationPrincipal jwt: Jwt) = authentication.currentAccount(jwt.subject!!.toLong())
}
