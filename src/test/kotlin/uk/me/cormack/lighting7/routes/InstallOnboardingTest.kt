package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
import uk.me.cormack.lighting7.testsupport.cookieClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedUser
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstallOnboardingTest : RouteIntegrationTest() {

    private fun newProject(name: String): Int = transaction(state.database) {
        DaoProject.new {
            this.name = name
            description = null
        }.id.value
    }

    private fun exists(id: Int): Boolean = transaction(state.database) { DaoProject.findById(id) != null }

    @Test
    fun `the first admin's setup leaves onboarding pending until it is finished`() = testApplication {
        mountTestApp(state)
        val client = cookieClient()

        val setup = client.post("/api/rest/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(SetupRequest("boss", "The Boss", "a-password"))
        }
        assertEquals(HttpStatusCode.OK, setup.status, setup.bodyAsText())
        assertTrue(client.get("/api/rest/install").body<InstallDto>().onboardingPending)

        val finish = client.post("/api/rest/install/onboarding/finish") {
            contentType(ContentType.Application.Json)
            setBody(FinishOnboardingRequest())
        }
        assertEquals(HttpStatusCode.OK, finish.status, finish.bodyAsText())
        val body = finish.body<FinishOnboardingResponse>()
        assertFalse(body.onboardingPending)
        assertEquals(projectId, body.currentProjectId)
        assertNull(body.removedPlaceholder)
        assertFalse(client.get("/api/rest/install").body<InstallDto>().onboardingPending)

        val again = client.post("/api/rest/install/onboarding/finish") {
            contentType(ContentType.Application.Json)
            setBody(FinishOnboardingRequest())
        }
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals(CODE_ONBOARDING_DONE, again.body<ErrorResponse>().code)
    }

    @Test
    fun `an install that never ran setup is not onboarding`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss")
        val client = jsonClient()
        val cookie = client.loginCookieHeader("boss")
        val install = client.get("/api/rest/install") { header(HttpHeaders.Cookie, cookie) }.body<InstallDto>()
        assertFalse(install.onboardingPending)
    }

    @Test
    fun `opening an imported project removes the empty placeholder the desk booted on`() = testApplication {
        mountTestApp(state)
        val placeholder = newProject("Default")
        runBlocking { state.projectManager.switchProject(placeholder) }
        val imported = newProject("Imported show")
        seedUser(state, "boss")
        markOnboardingPending(state)
        val client = jsonClient()
        val cookie = client.loginCookieHeader("boss")

        val finish = client.post("/api/rest/install/onboarding/finish") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.Json)
            setBody(FinishOnboardingRequest(openProjectId = imported))
        }
        assertEquals(HttpStatusCode.OK, finish.status, finish.bodyAsText())
        val body = finish.body<FinishOnboardingResponse>()
        assertEquals(imported, body.currentProjectId)
        assertEquals("Default", body.removedPlaceholder)
        assertEquals(imported, state.projectManager.currentProject.id.value)
        assertFalse(exists(placeholder), "the empty placeholder is gone")
    }

    @Test
    fun `a previous project with anything in it is kept`() = testApplication {
        mountTestApp(state)
        // The base project carries a universe config, so it is not empty.
        val imported = newProject("Imported show")
        seedUser(state, "boss")
        markOnboardingPending(state)
        val client = jsonClient()
        val cookie = client.loginCookieHeader("boss")

        val finish = client.post("/api/rest/install/onboarding/finish") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.Json)
            setBody(FinishOnboardingRequest(openProjectId = imported))
        }
        assertEquals(HttpStatusCode.OK, finish.status, finish.bodyAsText())
        val body = finish.body<FinishOnboardingResponse>()
        assertEquals(imported, body.currentProjectId)
        assertNull(body.removedPlaceholder)
        assertTrue(exists(projectId))
    }

    @Test
    fun `an unknown project answers 404 and leaves onboarding pending`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss")
        markOnboardingPending(state)
        val client = jsonClient()
        val cookie = client.loginCookieHeader("boss")

        val finish = client.post("/api/rest/install/onboarding/finish") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.Json)
            setBody(FinishOnboardingRequest(openProjectId = 9999))
        }
        assertEquals(HttpStatusCode.NotFound, finish.status)
        val install = client.get("/api/rest/install") { header(HttpHeaders.Cookie, cookie) }.body<InstallDto>()
        assertTrue(install.onboardingPending)
        assertNotNull(state.projectManager.currentProject)
    }

    @Test
    fun `an operator cannot finish onboarding`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss")
        seedUser(state, "op", role = UserRole.OPERATOR)
        markOnboardingPending(state)
        val client = jsonClient()
        val cookie = client.loginCookieHeader("op")

        val finish = client.post("/api/rest/install/onboarding/finish") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.Json)
            setBody(FinishOnboardingRequest())
        }
        assertEquals(HttpStatusCode.Forbidden, finish.status)
    }

    @Test
    fun `a placeholder someone has described is kept`() = testApplication {
        mountTestApp(state)
        val placeholder = newProject("Default")
        transaction(state.database) { DaoProject.findById(placeholder)!!.description = "my notes" }
        runBlocking { state.projectManager.switchProject(placeholder) }
        val imported = newProject("Imported show")
        seedUser(state, "boss")
        markOnboardingPending(state)
        val client = jsonClient()
        val cookie = client.loginCookieHeader("boss")

        val finish = client.post("/api/rest/install/onboarding/finish") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.Json)
            setBody(FinishOnboardingRequest(openProjectId = imported))
        }
        assertEquals(HttpStatusCode.OK, finish.status, finish.bodyAsText())
        assertNull(finish.body<FinishOnboardingResponse>().removedPlaceholder)
        assertTrue(exists(placeholder))
    }

    @Test
    fun `the current project answers a rename made since it was opened`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss")
        val client = jsonClient()
        val cookie = client.loginCookieHeader("boss")

        val rename = client.put("/api/rest/projects/$projectId") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Spring Gala"}""")
        }
        assertEquals(HttpStatusCode.OK, rename.status, rename.bodyAsText())
        val current = client.get("/api/rest/projects/current") { header(HttpHeaders.Cookie, cookie) }
        assertEquals("Spring Gala", TestJson.parseToJsonElement(current.bodyAsText()).jsonObject["name"]?.jsonPrimitive?.content)
    }
}
