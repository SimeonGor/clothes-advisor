package ru.itmo.clothesadvisor.accountservice.controller.auth

import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.accountservice.config.RefreshCookie
import ru.itmo.clothesadvisor.accountservice.dto.auth.LoginRequest
import ru.itmo.clothesadvisor.accountservice.service.auth.AuthenticationService
import ru.itmo.clothesadvisor.accountservice.service.auth.SessionAuthentication

@RestController
internal class AuthController(
    private val authentication: AuthenticationService,
    private val cookies: RefreshCookie,
) : AuthApi {
    override fun login(request: LoginRequest) =
        authentication.login(request.login, request.password)
            .map(::authenticated)

    override fun refresh(token: String?) =
        authentication.refresh(token).map(::authenticated)

    override fun logout(token: String?) =
        authentication.logout(token).thenReturn(ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store").build<Void>())

    private fun authenticated(result: SessionAuthentication) = ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookies.create(result.refreshToken, result.expiresAt).toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store").body(result.response)

    override fun me(jwt: Jwt) = authentication.currentAccount(jwt.subject!!.toLong())
}
