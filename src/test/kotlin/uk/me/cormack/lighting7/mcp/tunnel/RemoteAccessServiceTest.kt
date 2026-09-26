package uk.me.cormack.lighting7.mcp.tunnel

import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Test
import uk.me.cormack.lighting7.mcp.McpConfig
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Remote access end to end against a fake agent named by `mcp.tunnel.ngrokPath`. */
class RemoteAccessServiceTest : RouteIntegrationTest() {
    private val services = mutableListOf<RemoteAccessService>()

    @After
    fun cleanUp() {
        services.forEach { it.close() }
        runCatching { state.credentialStore.deleteBlob(RemoteAccessService.AUTHTOKEN_KEY) }
    }

    private fun fakeAgent(): Path {
        val dir = createTempDirectory("ra-agent")
        val fake = dir.resolve("ngrok")
        fake.writeText(
            "#!/bin/sh\nprintf '%s\\n' '{\"lvl\":\"info\",\"msg\":\"started tunnel\",\"url\":\"https://desk.example\"}'\nexec sleep 60\n",
        )
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"))
        return fake
    }

    private fun service(ngrokPath: String?, configure: (McpConfig) -> McpConfig = { it }): RemoteAccessService = RemoteAccessService(
        state.database,
        configure(state.mcpConfig.copy(ngrokPath = ngrokPath)),
        credentialStore = { state.credentialStore },
        workDir = createTempDirectory("ra-work"),
        build = null,
        pathLookup = { null },
    ).also { services += it }

    private fun waitFor(service: RemoteAccessService, what: String, check: (TunnelState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (check(service.state.value)) return
            Thread.sleep(20)
        }
        fail("timed out waiting for $what; state ${service.state.value}")
    }

    @Test
    fun `turning it on brings the tunnel up once the listener is, and off takes it down`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val service = service(fakeAgent().toString())
        service.update(enabled = true, domain = "desk.example", authtoken = "tok", hasAnyUser = true)
        waitFor(service, "waiting on the listener") { it == TunnelState.Starting }
        service.onListenerStarted()
        waitFor(service, "online") { it == TunnelState.Online("https://desk.example") }
        service.update(enabled = false, hasAnyUser = true)
        waitFor(service, "off") { it == TunnelState.Off }
    }

    @Test
    fun `a listener that failed to bind is the error, not a tunnel to nowhere`() {
        val service = service(null)
        service.update(enabled = true, domain = "desk.example", authtoken = "tok", hasAnyUser = true)
        service.onListenerStarted("Address already in use")
        waitFor(service, "the error") { it is TunnelState.Error }
        val error = service.state.value as TunnelState.Error
        assertTrue(error.message.contains("Address already in use"), error.message)
        assertEquals(false, error.retrying)
    }

    @Test
    fun `with no pinned build and no ngrok installed it says how to fix it`() {
        val service = service(null)
        service.update(enabled = true, domain = "desk.example", authtoken = "tok", hasAnyUser = true)
        service.onListenerStarted()
        waitFor(service, "no binary") { it is TunnelState.NoBinary }
        assertTrue((service.state.value as TunnelState.NoBinary).message.contains("mcp.tunnel.ngrokPath"))
    }

    @Test
    fun `settings persist, and local conf only seeds them`() {
        val first = service(null)
        first.update(domain = "desk.example", allowScripts = true, hasAnyUser = true)
        val second = service(null)
        assertEquals(RemoteAccessSettings(enabled = false, domain = "desk.example", allowScripts = true), second.settings)
    }

    @Test
    fun `local conf seeding it on with no authtoken does not survive the first save`() {
        val service = service(null) { it.copy(tunnelEnabled = true, tunnelDomain = "desk.example") }
        assertEquals(false, service.hasAuthtoken)
        assertEquals(true, service.settings.enabled)
        service.update(allowScripts = true, hasAnyUser = true)
        assertEquals(RemoteAccessSettings(enabled = false, domain = "desk.example", allowScripts = true), service.settings)
    }
}
