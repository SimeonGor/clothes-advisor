package ru.itmo.clothesadvisor.accountservice.config

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.Base64
import java.util.Date

internal object TestJwt {
    val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val privateKeyLocation = pem("PRIVATE KEY", keyPair.private.encoded)
    val publicKeyLocation = pem("PUBLIC KEY", keyPair.public.encoded)

    fun sign(userId: Long): String = signClaims(claims(userId.toString()))

    fun claims(subject: String): JWTClaimsSet.Builder = JWTClaimsSet.Builder()
        .issuer(JwtConfiguration.ISSUER).audience(JwtConfiguration.AUDIENCE).subject(subject)
        .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(900)))

    fun signClaims(
        claims: JWTClaimsSet.Builder,
        key: RSAPrivateKey = keyPair.private as RSAPrivateKey,
        algorithm: JWSAlgorithm = JWSAlgorithm.RS256,
    ): String = SignedJWT(JWSHeader(algorithm), claims.build()).apply {
        sign(RSASSASigner(key))
    }.serialize()

    private fun pem(label: String, bytes: ByteArray): String {
        val path = Files.createTempFile("account-test-jwt-", ".pem")
        path.toFile().deleteOnExit()
        Files.writeString(path, "-----BEGIN $label-----\n" +
            Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(bytes) + "\n-----END $label-----\n")
        return path.toUri().toString()
    }
}
