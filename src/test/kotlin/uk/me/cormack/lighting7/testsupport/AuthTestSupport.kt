package uk.me.cormack.lighting7.testsupport

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.setCookie
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import uk.me.cormack.lighting7.auth.SESSION_COOKIE
import uk.me.cormack.lighting7.auth.UserRecord
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.routes.LoginRequest
import uk.me.cormack.lighting7.state.State

/** Shared password for seeded test users — long enough for the policy, memorable in assertions. */
const val TEST_PASSWORD = "correct-horse"

/** Insert a user straight through [State.authService] (bcrypt cost 4 via [testAppConfig]). */
fun seedUser(
    state: State,
    username: String,
    role: UserRole = UserRole.ADMIN,
    password: String = TEST_PASSWORD,
): UserRecord = runBlocking {
    state.authService.createUser(username, "Test $username", role, password)
}

/**
 * JSON client with a cookie jar: log in once, every later request carries the session.
 * Use [jsonClient] + an explicit `Cookie` header instead when the test's point is
 * "exactly this cookie, or none".
 */
fun ApplicationTestBuilder.cookieClient(): HttpClient = createClient {
    install(ContentNegotiation) { json(TestJson) }
    install(HttpCookies)
}

/** POST /auth/login and return the raw `name=value` pair for an explicit `Cookie` header. */
suspend fun HttpClient.loginCookieHeader(username: String, password: String = TEST_PASSWORD): String {
    val response = post("/api/rest/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(LoginRequest(username, password))
    }
    check(response.status == HttpStatusCode.OK) { "login failed: ${response.status} ${response.bodyAsText()}" }
    val cookie = response.setCookie().first { it.name == SESSION_COOKIE }
    return "${cookie.name}=${cookie.value}"
}
