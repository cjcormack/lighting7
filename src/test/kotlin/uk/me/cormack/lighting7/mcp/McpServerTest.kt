package uk.me.cormack.lighting7.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TEST_PASSWORD
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The MCP listener end to end: OAuth discovery, registration, the sign-in page, the token
 * endpoint, and JSON-RPC over `/mcp` — mounted as [mcpModule] on its own test application,
 * exactly as production mounts it on its own server.
 */
class McpServerTest : RouteIntegrationTest() {

    private val callback = "https://claude.ai/api/mcp/auth_callback"
    private val verifier = "a-pkce-verifier-that-is-long-enough-to-satisfy-rfc-7636-rules"

    private fun ApplicationTestBuilder.mountMcp() {
        environment { config = testAppConfig() }
        application { mcpModule(state) }
    }

    private fun ApplicationTestBuilder.rawClient(): HttpClient = createClient { followRedirects = false }

    private fun HttpResponse.json(): JsonObject = runBlocking { Json.parseToJsonElement(bodyAsText()).jsonObject }

    private suspend fun HttpClient.register(redirect: String = callback): HttpResponse = post("/oauth/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"redirect_uris":["$redirect"],"client_name":"Claude"}""")
    }

    private data class Tokens(val clientId: String, val access: String, val refresh: String)

    /** Register, sign in, allow, exchange: the whole connector setup a claude.ai user walks. */
    private suspend fun HttpClient.connect(username: String = "alice", password: String = TEST_PASSWORD): Tokens {
        val clientId = register().json()["client_id"]!!.jsonPrimitive.content
        val requestId = openSignIn(clientId)
        val allow = submitForm("/oauth/authorize", parameters {
            append("request_id", requestId)
            append("username", username)
            append("password", password)
            append("action", "allow")
        })
        assertEquals(HttpStatusCode.Found, allow.status, allow.bodyAsText())
        val location = Url(allow.headers[HttpHeaders.Location]!!)
        assertEquals("claude.ai", location.host)
        assertEquals("xyz", location.parameters["state"])
        val code = location.parameters["code"]!!

        val token = submitForm("/oauth/token", parameters {
            append("grant_type", "authorization_code")
            append("code", code)
            append("client_id", clientId)
            append("redirect_uri", callback)
            append("code_verifier", verifier)
            append("resource", "http://localhost:8414/mcp")
        })
        assertEquals(HttpStatusCode.OK, token.status, token.bodyAsText())
        val body = token.json()
        assertEquals("Bearer", body["token_type"]!!.jsonPrimitive.content)
        return Tokens(clientId, body["access_token"]!!.jsonPrimitive.content, body["refresh_token"]!!.jsonPrimitive.content)
    }

    private suspend fun HttpClient.openSignIn(clientId: String): String {
        val page = get("/oauth/authorize") {
            url {
                parameters.append("response_type", "code")
                parameters.append("client_id", clientId)
                parameters.append("redirect_uri", callback)
                parameters.append("state", "xyz")
                parameters.append("code_challenge", McpAuthService.s256(verifier))
                parameters.append("code_challenge_method", "S256")
            }
        }
        assertEquals(HttpStatusCode.OK, page.status, page.bodyAsText())
        assertEquals("DENY", page.headers["X-Frame-Options"])
        return Regex("""name="request_id" value="([^"]+)"""").find(page.bodyAsText())!!.groupValues[1]
    }

    private suspend fun HttpClient.rpc(token: String?, body: String): HttpResponse = post("/mcp") {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun `discovery documents name the public url as issuer and resource`() = testApplication {
        mountMcp()
        val client = rawClient()
        val resource = client.get("/.well-known/oauth-protected-resource/mcp").json()
        assertEquals("http://localhost:8414/mcp", resource["resource"]!!.jsonPrimitive.content)
        assertEquals("http://localhost:8414", resource["authorization_servers"]!!.jsonArray.single().jsonPrimitive.content)

        val server = client.get("/.well-known/oauth-authorization-server").json()
        assertEquals("http://localhost:8414", server["issuer"]!!.jsonPrimitive.content)
        assertEquals("S256", server["code_challenge_methods_supported"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("http://localhost:8414/oauth/register", server["registration_endpoint"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the listener serves nothing of the desk's own API`() = testApplication {
        mountMcp()
        seedUser(state, "alice")
        assertEquals(HttpStatusCode.NotFound, rawClient().get("/api/rest/projects").status)
        assertEquals(HttpStatusCode.NotFound, rawClient().get("/api/rest/auth/status").status)
    }

    @Test
    fun `an unauthenticated MCP call is 401 and points at the resource metadata`() = testApplication {
        mountMcp()
        seedUser(state, "alice")
        val response = rawClient().rpc(null, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val challenge = response.headers[HttpHeaders.WWWAuthenticate]!!
        assertTrue(challenge.contains("resource_metadata=\"http://localhost:8414/.well-known/oauth-protected-resource/mcp\""))

        val forged = rawClient().rpc("not-a-token", """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertEquals(HttpStatusCode.Unauthorized, forged.status)
        assertTrue(forged.headers[HttpHeaders.WWWAuthenticate]!!.contains("invalid_token"))
    }

    @Test
    fun `full connect then initialize, list and call tools`() = testApplication {
        mountMcp()
        seedUser(state, "alice", role = UserRole.OPERATOR)
        val client = rawClient()
        val tokens = client.connect()

        val init = client.rpc(
            tokens.access,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""",
        ).json()["result"]!!.jsonObject
        assertEquals("2025-06-18", init["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue(init["instructions"]!!.jsonPrimitive.content.contains("describe_rig"))
        assertFalse(init["instructions"]!!.jsonPrimitive.content.contains("run_lighting_script"))

        assertEquals(
            HttpStatusCode.Accepted,
            client.rpc(tokens.access, """{"jsonrpc":"2.0","method":"notifications/initialized"}""").status,
        )

        val tools = client.rpc(tokens.access, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            .json()["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals("describe_rig", tools.first())
        assertTrue("get_current_state" in tools && "go_cue_stack" in tools && "set_bpm" in tools)
        assertFalse("run_lighting_script" in tools, "the script tool is not offered over MCP")

        val rig = client.rpc(tokens.access, """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"describe_rig","arguments":{}}}""")
            .json()["result"]!!.jsonObject
        assertFalse(rig["isError"]!!.jsonPrimitive.boolean)
        assertTrue(rig["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content.contains("## Available Fixtures"))

        val bpm = client.rpc(tokens.access, """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"set_bpm","arguments":{"bpm":97}}}""")
            .json()["result"]!!.jsonObject
        assertFalse(bpm["isError"]!!.jsonPrimitive.boolean, bpm.toString())
        assertEquals(97.0, state.show.fxEngine.masterClock.bpm.value)

        val script = client.rpc(
            tokens.access,
            """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"run_lighting_script","arguments":{"script":"1","description":"x"}}}""",
        ).json()
        assertEquals(-32602, script["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)

        val unknown = client.rpc(tokens.access, """{"jsonrpc":"2.0","id":6,"method":"nope"}""").json()
        assertEquals(-32601, unknown["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a code is single use and bound to its PKCE verifier`() = testApplication {
        mountMcp()
        seedUser(state, "alice")
        val client = rawClient()
        val clientId = client.register().json()["client_id"]!!.jsonPrimitive.content
        val requestId = client.openSignIn(clientId)
        val code = Url(
            client.submitForm("/oauth/authorize", parameters {
                append("request_id", requestId); append("username", "alice")
                append("password", TEST_PASSWORD); append("action", "allow")
            }).headers[HttpHeaders.Location]!!,
        ).parameters["code"]!!

        val wrongVerifier = client.submitForm("/oauth/token", parameters {
            append("grant_type", "authorization_code"); append("code", code)
            append("client_id", clientId); append("code_verifier", "wrong-verifier-wrong-verifier-wrong-verifier")
        })
        assertEquals(HttpStatusCode.BadRequest, wrongVerifier.status)
        assertEquals("invalid_grant", wrongVerifier.json()["error"]!!.jsonPrimitive.content)

        // Burned by the failed attempt, so the right verifier is too late.
        val replay = client.submitForm("/oauth/token", parameters {
            append("grant_type", "authorization_code"); append("code", code)
            append("client_id", clientId); append("code_verifier", verifier)
        })
        assertEquals(HttpStatusCode.BadRequest, replay.status)
    }

    @Test
    fun `refresh rotates, and replaying the old refresh token revokes the grant`() = testApplication {
        mountMcp()
        seedUser(state, "alice")
        val client = rawClient()
        val first = client.connect()

        val rotated = client.submitForm("/oauth/token", parameters {
            append("grant_type", "refresh_token"); append("refresh_token", first.refresh); append("client_id", first.clientId)
        })
        assertEquals(HttpStatusCode.OK, rotated.status, rotated.bodyAsText())
        val second = rotated.json()
        val newAccess = second["access_token"]!!.jsonPrimitive.content
        assertNotEquals(first.access, newAccess)
        assertEquals(HttpStatusCode.Unauthorized, client.rpc(first.access, """{"jsonrpc":"2.0","id":1,"method":"ping"}""").status)
        assertEquals(HttpStatusCode.OK, client.rpc(newAccess, """{"jsonrpc":"2.0","id":1,"method":"ping"}""").status)

        val replay = client.submitForm("/oauth/token", parameters {
            append("grant_type", "refresh_token"); append("refresh_token", first.refresh); append("client_id", first.clientId)
        })
        assertEquals(HttpStatusCode.BadRequest, replay.status)
        assertEquals(HttpStatusCode.Unauthorized, client.rpc(newAccess, """{"jsonrpc":"2.0","id":1,"method":"ping"}""").status)
    }

    @Test
    fun `registration only accepts allowlisted redirect uris`() = testApplication {
        mountMcp()
        val client = rawClient()
        assertEquals(HttpStatusCode.BadRequest, client.register("https://evil.example/cb").status)
        assertEquals(HttpStatusCode.Created, client.register("http://localhost:33418/callback").status)
        assertEquals(HttpStatusCode.Created, client.register("http://127.0.0.1:5555/cb").status)
    }

    @Test
    fun `wrong password re-renders the form, and repeated failures lock the page`() = testApplication {
        mountMcp()
        seedUser(state, "alice")
        val client = rawClient()
        val clientId = client.register().json()["client_id"]!!.jsonPrimitive.content
        val requestId = client.openSignIn(clientId)
        val attempt = suspend { password: String ->
            client.submitForm("/oauth/authorize", parameters {
                append("request_id", requestId); append("username", "alice")
                append("password", password); append("action", "allow")
            })
        }
        val wrong = attempt("nope")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertTrue(wrong.bodyAsText().contains("Incorrect username or password"))

        repeat(McpAuthService.SIGN_IN_LOCKOUT_FAILURES - 1) { attempt("nope") }
        assertEquals(HttpStatusCode.TooManyRequests, attempt(TEST_PASSWORD).status)
    }

    @Test
    fun `spraying junk usernames does not reset a real account's lockout`() = testApplication {
        mountMcp()
        seedUser(state, "alice")
        val auth = state.mcpAuthService
        repeat(McpAuthService.SIGN_IN_LOCKOUT_FAILURES - 1) { auth.recordSignInFailure("alice") }
        repeat(1_200) { auth.recordSignInFailure("junk-$it") }
        auth.recordSignInFailure("alice")
        assertTrue(auth.signInLockedOut("alice"))
    }

    @Test
    fun `a flood of sign-ins or registrations evicts the oldest rather than refusing`() = testApplication {
        mountMcp()
        seedUser(state, "alice")
        val auth = state.mcpAuthService
        val client = auth.registerClient(listOf(callback), "Claude")
        val begin = {
            auth.beginAuthorization(client, callback, null, "code", McpAuthService.s256(verifier), "S256", null)
        }
        val first = begin()
        repeat(1_000) { begin() }
        assertEquals(null, auth.pendingAuthorization(first.id))
        assertNotNull(auth.pendingAuthorization(begin().id))

        repeat(1_000) { auth.registerClient(listOf(callback), "junk") }
        // The client with a sign-in in progress survives the flood, and a new one still registers.
        assertNotNull(auth.findClient(client.clientId))
        assertNotNull(auth.findClient(auth.registerClient(listOf(callback), "Claude").clientId))
    }

    @Test
    fun `deny sends access_denied back to the client`() = testApplication {
        mountMcp()
        seedUser(state, "alice")
        val client = rawClient()
        val clientId = client.register().json()["client_id"]!!.jsonPrimitive.content
        val requestId = client.openSignIn(clientId)
        val deny = client.submitForm("/oauth/authorize", parameters {
            append("request_id", requestId); append("action", "deny")
        })
        assertEquals(HttpStatusCode.Found, deny.status)
        val location = Url(deny.headers[HttpHeaders.Location]!!)
        assertEquals("access_denied", location.parameters["error"])
        assertEquals("xyz", location.parameters["state"])
    }

    @Test
    fun `an unregistered redirect uri gets an error page, never a redirect`() = testApplication {
        mountMcp()
        seedUser(state, "alice")
        val client = rawClient()
        val clientId = client.register().json()["client_id"]!!.jsonPrimitive.content
        val page = client.get("/oauth/authorize?response_type=code&client_id=$clientId&redirect_uri=https://claude.com/api/mcp/auth_callback&code_challenge=x&code_challenge_method=S256")
        assertEquals(HttpStatusCode.BadRequest, page.status)
        assertEquals(null, page.headers[HttpHeaders.Location])
    }

    @Test
    fun `a desk with no accounts refuses to authorize`() = testApplication {
        mountMcp()
        val client = rawClient()
        val clientId = client.register().json()["client_id"]!!.jsonPrimitive.content
        val page = client.get("/oauth/authorize?response_type=code&client_id=$clientId&redirect_uri=$callback&code_challenge=x&code_challenge_method=S256")
        assertEquals(HttpStatusCode.Forbidden, page.status)
    }

    @Test
    fun `disabling the account or changing its password revokes the grant`() = testApplication {
        mountMcp()
        val alice = seedUser(state, "alice", role = UserRole.OPERATOR)
        seedUser(state, "admin")
        val client = rawClient()
        val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""

        val first = client.connect()
        state.authService.setUserDisabled(alice.userId, true)
        assertEquals(HttpStatusCode.Unauthorized, client.rpc(first.access, ping).status)
        state.authService.setUserDisabled(alice.userId, false)
        // Re-enabling does not bring a revoked grant back.
        assertEquals(HttpStatusCode.Unauthorized, client.rpc(first.access, ping).status)

        val second = client.connect()
        assertEquals(HttpStatusCode.OK, client.rpc(second.access, ping).status)
        runBlocking { state.authService.setPasswordAsAdmin(alice.userId, "another-password") }
        assertEquals(HttpStatusCode.Unauthorized, client.rpc(second.access, ping).status)
        val refresh = client.submitForm("/oauth/token", parameters {
            append("grant_type", "refresh_token"); append("refresh_token", second.refresh); append("client_id", second.clientId)
        })
        assertEquals(HttpStatusCode.BadRequest, refresh.status)
    }

    @Test
    fun `connected apps lists and revokes the caller's own grants over the desk API`() = testApplication {
        mountTestApp(state)
        seedUser(state, "alice")
        seedUser(state, "bob")
        // A grant made through the service directly: this test is about the desk-side REST pair.
        val client = McpOAuthClient("c1", "Claude", listOf(callback))
        val aliceId = state.authService.findUserByUsername("alice")!!.userId
        val pending = state.mcpAuthService.beginAuthorization(client, callback, null, "code", McpAuthService.s256(verifier), "S256", null)
        runBlocking { state.mcpAuthService.registerClient(listOf(callback), "unused") }
        val code = state.mcpAuthService.issueCode(pending, aliceId)
        val tokens = state.mcpAuthService.exchangeCode(code, "c1", callback, verifier, null)
        assertNotNull(state.mcpAuthService.resolveAccessToken(tokens.accessToken))

        val http = jsonClient()
        val aliceCookie = http.loginCookieHeader("alice")
        val bobCookie = http.loginCookieHeader("bob")
        val list = http.get("/api/rest/auth/connected-apps") { header(HttpHeaders.Cookie, aliceCookie) }
        val apps = Json.parseToJsonElement(list.bodyAsText()).jsonArray
        assertEquals(1, apps.size)
        assertEquals("Claude", apps.single().jsonObject["clientName"]!!.jsonPrimitive.content)
        val id = apps.single().jsonObject["id"]!!.jsonPrimitive.int

        assertEquals("[]", http.get("/api/rest/auth/connected-apps") { header(HttpHeaders.Cookie, bobCookie) }.bodyAsText())
        assertEquals(
            HttpStatusCode.NotFound,
            http.delete("/api/rest/auth/connected-apps/$id") { header(HttpHeaders.Cookie, bobCookie) }.status,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            http.delete("/api/rest/auth/connected-apps/$id") { header(HttpHeaders.Cookie, aliceCookie) }.status,
        )
        assertEquals(null, state.mcpAuthService.resolveAccessToken(tokens.accessToken))
    }
}
