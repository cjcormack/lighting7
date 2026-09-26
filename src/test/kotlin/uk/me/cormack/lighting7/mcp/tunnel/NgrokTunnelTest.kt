package uk.me.cormack.lighting7.mcp.tunnel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The supervisor against a fake `ngrok`: a shell script that prints what the real agent logs and
 * then behaves as the test's `mode` file says. POSIX only — the script is `sh`.
 */
class NgrokTunnelTest {
    private lateinit var dir: Path
    private lateinit var fake: Path
    private lateinit var scope: CoroutineScope
    private val states = CopyOnWriteArrayList<TunnelState>()
    private val launches = AtomicInteger()
    private val launched = CopyOnWriteArrayList<Process>()

    @Before
    fun setUp() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        dir = createTempDirectory("ngrok-test")
        fake = dir.resolve("ngrok")
        fake.writeText(
            """
            #!/bin/sh
            here=${'$'}(dirname "${'$'}0")
            echo "${'$'}@" > "${'$'}here/args"
            mode=${'$'}(cat "${'$'}here/mode")
            case "${'$'}mode" in
              online)
                printf '%s\n' '{"lvl":"info","msg":"starting web service","obj":"web"}'
                printf '%s\n' '{"lvl":"info","msg":"started tunnel","url":"https://desk.example"}'
                exec sleep 60 ;;
              crash)
                printf '%s\n' '{"lvl":"info","msg":"started tunnel","url":"https://desk.example"}'
                exit 3 ;;
              badtoken)
                printf '%s\n' '{"lvl":"eror","msg":"session closing","err":"authentication failed: The authtoken you specified does not look like a proper ngrok authtoken.\n\nERR_NGROK_105\n"}'
                exec sleep 60 ;;
              plainerror)
                printf '%s\n' 'ERROR:  failed to start tunnel: The endpoint is already online. ERR_NGROK_334'
                exec sleep 60 ;;
            esac
            """.trimIndent() + "\n",
        )
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
        launched.forEach { it.destroyForcibly() }
    }

    private fun mode(m: String) = dir.resolve("mode").writeText(m)

    private fun tunnel(backoffMs: Long = 50) = NgrokTunnel(
        workDir = dir.resolve("work"),
        scope = scope,
        onState = { states += it },
        launcher = { args ->
            launches.incrementAndGet()
            ProcessBuilder(args).redirectErrorStream(true).start().also { launched += it }
        },
        backoffMs = { backoffMs },
    )

    private val spec get() = NgrokTunnel.Spec(fake, "tok\"en: {x}", "desk.example", 8414)

    private fun waitFor(what: String, timeoutMs: Long = 10_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(20)
        }
        fail("timed out waiting for $what; states: $states")
    }

    @Test
    fun `comes online, then stops the agent and forgets it`() {
        mode("online")
        val t = tunnel()
        t.start(spec)
        waitFor("online") { states.lastOrNull() == TunnelState.Online("https://desk.example") }
        assertEquals(TunnelState.Starting, states.first())
        val pidFile = dir.resolve("work/ngrok.pid")
        assertTrue(Files.exists(pidFile))
        val args = dir.resolve("args").readText().trim()
        assertTrue(args.startsWith("start --all --config "), args)

        val config = dir.resolve("work/ngrok.yml")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(config)))
        val yaml = config.readText()
        // Quoted as a JSON string: the token can't break out of its scalar.
        assertTrue(yaml.contains("authtoken: \"tok\\\"en: {x}\""), yaml)
        assertTrue(yaml.contains("url: \"https://desk.example\""), yaml)
        assertTrue(yaml.contains("url: \"http://127.0.0.1:8414\""), yaml)

        val before = states.size
        t.close()
        assertFalse(launched.single().isAlive)
        assertFalse(Files.exists(pidFile))
        Thread.sleep(200)
        assertEquals(before, states.size, "a stopped run must report nothing more: $states")
    }

    @Test
    fun `a crash after coming online restarts by itself`() {
        mode("crash")
        val t = tunnel()
        t.start(spec)
        waitFor("a restart") { launches.get() >= 3 }
        val retry = states.filterIsInstance<TunnelState.Error>().first()
        assertTrue(retry.retrying)
        assertTrue(retry.message.contains("exit 3"), retry.message)
        t.close()
    }

    @Test
    fun `a bad authtoken stops for good and says what to fix`() {
        mode("badtoken")
        val t = tunnel()
        t.start(spec)
        waitFor("the error") { states.lastOrNull() is TunnelState.Error }
        val error = states.last() as TunnelState.Error
        assertEquals("ERR_NGROK_105", error.code)
        assertFalse(error.retrying)
        assertTrue(error.message.contains("authtoken", ignoreCase = true), error.message)
        waitFor("the agent to be stopped") { !launched.single().isAlive }
        Thread.sleep(300)
        assertEquals(1, launches.get(), "an operator's mistake is not retried")
        t.close()
    }

    @Test
    fun `a plain-text coded error is understood too`() {
        mode("plainerror")
        val t = tunnel()
        t.start(spec)
        waitFor("the error") { states.lastOrNull() is TunnelState.Error }
        assertEquals("ERR_NGROK_334", (states.last() as TunnelState.Error).code)
        t.close()
    }

    @Test
    fun `an agent left behind by a crashed desk is swept, but nothing else is`() {
        val work = dir.resolve("work").also { Files.createDirectories(it) }
        val sleep = listOf("/bin/sleep", "/usr/bin/sleep").map(Path::of).firstOrNull { Files.isExecutable(it) }
        assumeFalse(sleep == null)
        // A copy of `sleep` standing in for the agent binary, so the process's command is it.
        val agent = dir.resolve("agent")
        Files.copy(sleep!!, agent)
        agent.toFile().setExecutable(true)
        val orphan = ProcessBuilder(agent.toString(), "60").start().also { launched += it }
        val stranger = ProcessBuilder(sleep.toString(), "60").start().also { launched += it }

        val t = tunnel()
        work.resolve("ngrok.pid").writeText("${stranger.pid()}\n$agent\n")
        t.sweepOrphan(agent)
        assertTrue(stranger.isAlive, "a PID now running something else is left alone")

        work.resolve("ngrok.pid").writeText("${orphan.pid()}\n$agent\n")
        t.sweepOrphan(agent)
        assertTrue(orphan.waitFor(5, TimeUnit.SECONDS))
        assertFalse(Files.exists(work.resolve("ngrok.pid")))
        t.close()
    }

    @Test
    fun `log lines`() {
        val online = assertNotNull(NgrokTunnel.parseLogLine("""{"lvl":"info","msg":"started tunnel","url":"https://a.b"}"""))
        assertTrue(online.online)
        assertEquals("https://a.b", online.url)
        val endpoint = assertNotNull(NgrokTunnel.parseLogLine("""{"lvl":"info","msg":"started endpoint","url":"https://a.b"}"""))
        assertTrue(endpoint.online)
        val err = assertNotNull(NgrokTunnel.parseLogLine("""{"lvl":"eror","msg":"x","err":"no ERR_NGROK_320 here"}"""))
        assertTrue(err.isError)
        assertEquals("ERR_NGROK_320", err.code)
        val nilErr = assertNotNull(NgrokTunnel.parseLogLine("""{"lvl":"info","msg":"heartbeat","err":"<nil>"}"""))
        assertFalse(nilErr.isError)
        assertEquals(null, NgrokTunnel.parseLogLine("   "))
        assertTrue(NgrokTunnel.friendlyMessage("ERR_NGROK_320", "raw").contains("domain", ignoreCase = true))
    }
}
