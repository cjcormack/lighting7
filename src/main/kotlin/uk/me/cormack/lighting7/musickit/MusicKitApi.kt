@file:OptIn(kotlin.time.ExperimentalTime::class)

package uk.me.cormack.lighting7.musickit

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.core.*
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.interfaces.ECKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*
import kotlin.text.toByteArray
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaInstant

class MusicKitApi(private val issuer: String, private val keyId: String, private val secret: String) : Closeable {
    private val client: HttpClient
    private val authJwtSigner: Algorithm

    private var authExpiry = Clock.System.now()
    private var authToken = ""

    init {
        authJwtSigner = createSigner()

        client = HttpClient(CIO) {
            expectSuccess = true
            install(Logging)
            install(ContentNegotiation) {
                val jsonSettings = Json {
                    ignoreUnknownKeys = true
                }
                json(jsonSettings, ContentType.Application.Json)
            }
            defaultRequest {
                url("https://api.music.apple.com/v1/")
                if (!headers.contains(HttpHeaders.Authorization)) {
                    if (authExpiry < Clock.System.now() + 1.minutes || authToken == "") {
                        refreshAuth()
                    }
                    bearerAuth(authToken)
                }
            }
        }
    }

    private fun createSigner(): Algorithm {
        val factory = KeyFactory.getInstance("EC")
        val keySpec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(secret.toByteArray(Charsets.UTF_8)))
        val key = factory.generatePrivate(keySpec)

        return Algorithm.ECDSA256(key as ECKey)
    }

    private fun refreshAuth() {
        authExpiry = (Clock.System.now() + 1.hours)
        authToken = JWT.create()
            .withKeyId(keyId)
            .withIssuer(issuer)
            .withIssuedAt((Clock.System.now() - 1.minutes).toJavaInstant())
            .withExpiresAt(authExpiry.toJavaInstant())
            .sign(authJwtSigner)
    }

    override fun close() {
        client.close()
    }
}
