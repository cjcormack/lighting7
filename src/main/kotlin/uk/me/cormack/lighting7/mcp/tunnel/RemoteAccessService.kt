package uk.me.cormack.lighting7.mcp.tunnel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.mcp.McpConfig
import uk.me.cormack.lighting7.models.DaoRemoteAccessSettings
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.sync.auth.CredentialStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** The operator-facing settings, as stored. The authtoken is never part of it — only whether one is set. */
data class RemoteAccessSettings(
    val enabled: Boolean,
    val domain: String?,
    val allowScripts: Boolean,
    /** Whether a remote caller may arm, fire or reload a one-shot trigger (stage-view plan session 9). */
    val allowEffects: Boolean = false,
)

class RemoteAccessException(message: String) : RuntimeException(message)

/**
 * Remote access: the ngrok tunnel that makes the desk's public listener (`mcp.port`) reachable at
 * `https://<domain>`, and the settings behind it.
 *
 * Owns three things. **Settings** — enabled, domain, "allow scripts" — in the machine-local
 * `remote_access_settings` row (`local.conf`'s `mcp.tunnel.*` until the first save). **The
 * authtoken**, in the [CredentialStore] under [AUTHTOKEN_KEY]; it is write-only through the API.
 * And **the tunnel**, whose [state] the Remote access tab and the `tunnel.state` frame report.
 *
 * It is also where the desk's public URL comes from ([publicUrl]): the OAuth issuer, the MCP
 * resource and the 401 challenge are all built from it, per request, so turning remote access on
 * needs no restart.
 */
class RemoteAccessService(
    private val database: Database,
    private val mcpConfig: McpConfig,
    private val credentialStore: () -> CredentialStore,
    private val workDir: Path,
    private val installer: NgrokInstaller = NgrokInstaller(workDir),
    private val build: NgrokDistribution.Build? = NgrokDistribution.forThisPlatform(),
    tunnelFactory: ((TunnelState) -> Unit, CoroutineScope) -> NgrokTunnel = { onState, scope ->
        NgrokTunnel(workDir, scope, onState)
    },
    private val pathLookup: (String) -> Path? = ::findOnPath,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val settingsRef = AtomicReference(loadSettings())

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Off)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val tunnel = tunnelFactory({ _state.value = it }, scope)

    /** Set once the public listener has bound; the tunnel has nothing to point at before that. */
    @Volatile private var listenerReady = false
    @Volatile private var listenerFailure: String? = null
    @Volatile private var closed = false

    val settings: RemoteAccessSettings get() = settingsRef.get()

    /** Whether a remote caller may compile, run or save scripts. */
    val allowRemoteScripts: Boolean get() = settings.allowScripts

    /** Whether a remote caller may arm the desk, fire a one-shot trigger or reload one. */
    val allowRemoteEffects: Boolean get() = settings.allowEffects

    val hasAuthtoken: Boolean
        get() = runCatching { credentialStore().containsBlob(AUTHTOKEN_KEY) }.getOrDefault(false)

    /**
     * The public base URL, no trailing slash: an explicit `mcp.publicUrl` (a tunnel the operator
     * runs themselves), else `https://<ngrok domain>`, else `http://localhost:<port>`. The domain
     * counts whether or not the tunnel is on, so switching it off and on again keeps the issuer —
     * and every connector — the same.
     */
    fun publicUrl(): String =
        mcpConfig.explicitPublicUrl
            ?: settings.domain?.let { "https://$it" }
            ?: mcpConfig.localUrl

    /** The version and platform the desk will download, or null when none is pinned for it. */
    val pinnedBuild: NgrokDistribution.Build? get() = build

    /**
     * Apply a change from the Remote access tab. Null leaves a field as it is; an empty
     * [authtoken] clears it. Validates before writing anything, then (re)starts or stops the
     * tunnel in the background. Throws [RemoteAccessException] with an operator-facing message.
     */
    fun update(
        enabled: Boolean? = null,
        domain: String? = null,
        authtoken: String? = null,
        allowScripts: Boolean? = null,
        allowEffects: Boolean? = null,
        hasAnyUser: Boolean,
    ): RemoteAccessSettings {
        val current = settings
        val newDomain = if (domain == null) current.domain else normaliseDomain(domain)
        val tokenWillExist = when {
            authtoken == null -> hasAuthtoken
            else -> authtoken.isNotBlank()
        }
        val newEnabled = enabled ?: current.enabled
        if (newEnabled && enabled == true) {
            if (!hasAnyUser) throw RemoteAccessException("Create a desk account before turning on remote access: a desk with no accounts is open to anyone who reaches it.")
            if (newDomain == null) throw RemoteAccessException("Enter your ngrok domain first.")
            if (!tokenWillExist) throw RemoteAccessException("Paste your ngrok authtoken first.")
        }
        if (authtoken != null) {
            val trimmed = authtoken.trim()
            if (trimmed.isEmpty()) {
                credentialStore().deleteBlob(AUTHTOKEN_KEY)
            } else {
                if (trimmed.length > 512 || trimmed.any { it.isWhitespace() || it.isISOControl() }) {
                    throw RemoteAccessException("That doesn't look like an ngrok authtoken.")
                }
                credentialStore().setBlob(AUTHTOKEN_KEY, trimmed)
            }
        }
        // No token or no domain leaves nothing to run, so remote access goes off with it rather
        // than sitting "on" in a state it can never reach. That covers a token cleared here and
        // one that was never there — `local.conf` can seed `enabled` without either.
        val effectiveEnabled = newEnabled && newDomain != null && tokenWillExist
        val next = RemoteAccessSettings(
            enabled = effectiveEnabled,
            domain = newDomain,
            allowScripts = allowScripts ?: current.allowScripts,
            allowEffects = allowEffects ?: current.allowEffects,
        )
        transaction(database) {
            val row = DaoRemoteAccessSettings.all().firstOrNull() ?: DaoRemoteAccessSettings.new { updatedAt = nowUtc() }
            row.enabled = next.enabled
            row.domain = next.domain
            row.allowScripts = next.allowScripts
            row.allowEffects = next.allowEffects
            row.updatedAt = nowUtc()
        }
        settingsRef.set(next)
        reconcileAsync()
        return next
    }

    /** Called once the public listener is up (or has failed to bind). Starts the tunnel if it is on. */
    fun onListenerStarted(failure: String? = null) {
        listenerReady = failure == null
        listenerFailure = failure
        reconcileAsync()
    }

    private fun reconcileAsync() {
        if (closed) return
        scope.launch { mutex.withLock { reconcile() } }
    }

    private fun reconcile() {
        if (closed) return
        val s = settings
        val token = if (s.enabled) runCatching { credentialStore().getBlob(AUTHTOKEN_KEY) }.getOrNull() else null
        if (!s.enabled || s.domain == null || token.isNullOrBlank()) {
            tunnel.stop()
            _state.value = TunnelState.Off
            return
        }
        if (!listenerReady) {
            tunnel.stop()
            _state.value = listenerFailure?.let {
                TunnelState.Error(null, "The desk's public listener isn't running on port ${mcpConfig.port}: $it", retrying = false)
            } ?: TunnelState.Starting
            return
        }
        val binary = resolveBinary() ?: return
        if (closed) return
        tunnel.start(NgrokTunnel.Spec(binary, token, s.domain, mcpConfig.port))
    }

    /** The agent to run, downloading the pinned one if needed; null (and the state set) when there is none. */
    private fun resolveBinary(): Path? {
        mcpConfig.ngrokPath?.let { configured ->
            val path = Path.of(configured)
            if (!Files.isRegularFile(path)) {
                _state.value = TunnelState.NoBinary("mcp.tunnel.ngrokPath is set to $configured, which isn't a file.")
                return null
            }
            return path
        }
        val pinned = build
        if (pinned == null) {
            pathLookup("ngrok")?.let { return it }
            _state.value = TunnelState.NoBinary(
                "There's no ngrok download pinned for this computer (${System.getProperty("os.name")} " +
                    "${System.getProperty("os.arch")}). Install ngrok yourself and set mcp.tunnel.ngrokPath in local.conf.",
            )
            return null
        }
        if (installer.isInstalled(pinned)) return installer.binaryPath(pinned)
        tunnel.stop()
        _state.value = TunnelState.Installing(0, null)
        var lastReport = 0L
        return try {
            installer.ensureInstalled(pinned) { read, total ->
                val now = System.currentTimeMillis()
                if (now - lastReport >= 250 || (total != null && read >= total)) {
                    lastReport = now
                    _state.value = TunnelState.Installing(read, total)
                }
            }
        } catch (e: Exception) {
            log.warn("ngrok download failed: {}", e.message)
            _state.value = TunnelState.Error(
                null,
                (e as? NgrokInstallException)?.message ?: "Couldn't install ngrok: ${e.message ?: e.javaClass.simpleName}",
                retrying = false,
            )
            null
        }
    }

    private fun loadSettings(): RemoteAccessSettings {
        val row = transaction(database) { DaoRemoteAccessSettings.all().firstOrNull()?.let { RemoteAccessSettings(it.enabled, it.domain, it.allowScripts, it.allowEffects) } }
        return row ?: RemoteAccessSettings(
            enabled = mcpConfig.tunnelEnabled,
            domain = mcpConfig.tunnelDomain?.let { runCatching { normaliseDomain(it) }.getOrNull() },
            allowScripts = false,
        )
    }

    /** Stop the tunnel for good. Idempotent. */
    fun close() {
        if (closed) return
        closed = true
        runCatching { tunnel.close() }
        _state.value = TunnelState.Off
        scope.cancel()
    }

    companion object {
        private val log = LoggerFactory.getLogger(RemoteAccessService::class.java)

        const val AUTHTOKEN_KEY = "ngrok:authtoken"

        private val hostname = Regex("^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")

        /**
         * `https://Desk.ngrok-free.app/` → `desk.ngrok-free.app`. Anything but a bare hostname
         * with at least one dot — a path, a port, a query — is refused rather than trimmed,
         * because this string becomes the OAuth issuer and must be exactly the address clients use.
         */
        fun normaliseDomain(input: String): String? {
            var d = input.trim().lowercase()
            if (d.isEmpty()) return null
            d = d.removePrefix("https://").removePrefix("http://").trimEnd('/')
            if (!hostname.matches(d)) throw RemoteAccessException("\"${input.trim()}\" isn't a domain. Enter it as ngrok shows it, like your-name.ngrok-free.app.")
            return d
        }

        private fun findOnPath(name: String): Path? {
            val exe = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "$name.exe" else name
            return System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .map { Path.of(it, exe) }
                .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        }
    }
}
