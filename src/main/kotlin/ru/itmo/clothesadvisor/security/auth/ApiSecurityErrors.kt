package ru.itmo.clothesadvisor.security.auth

import jakarta.servlet.http.HttpServletResponse

internal object ApiSecurityErrors {
    fun unauthorized(response: HttpServletResponse) {
        response.setHeader("WWW-Authenticate", "Bearer")
        write(response, 401, "unauthorized")
    }

    fun forbidden(response: HttpServletResponse) = write(response, 403, "forbidden")

    private fun write(response: HttpServletResponse, status: Int, error: String) {
        response.status = status
        response.contentType = "application/json"
        response.writer.write("{\"error\":\"$error\"}")
    }
}
