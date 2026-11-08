package uk.me.cormack.lighting7.cloud

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.DaoRemoteAccessSettings
import uk.me.cormack.lighting7.models.nowUtc
import java.util.concurrent.atomic.AtomicReference

/** What a remote caller may do beyond running the show. Each is off until an admin at the desk turns it on. */
data class RemotePolicySettings(
    /** Compile, run or save a script. */
    val allowScripts: Boolean = false,
    /** Arm the desk, fire a one-shot trigger or reload one. */
    val allowEffects: Boolean = false,
    /** Run a fixture command (a reset, a lamp strike, a lamp off), MCP included. */
    val allowCommands: Boolean = false,
)

/**
 * The switches every remote caller answers to, through a remote session or the MCP relay alike
 * (FR-RA-9): the machine-local `remote_access_settings` row, read once and held in memory, written only
 * by the Cloud tab's *Remote access* on the desk's own listener (cloud-p1 plan D6, D-17). Every
 * `require*Access` and `McpProtocol`'s `run_fixture_command` read it here.
 */
class RemotePolicy(private val database: Database) {
    private val current = AtomicReference(load())

    val settings: RemotePolicySettings get() = current.get()

    val allowScripts: Boolean get() = settings.allowScripts
    val allowEffects: Boolean get() = settings.allowEffects
    val allowCommands: Boolean get() = settings.allowCommands

    /** Change what is named; null leaves a switch as it is. */
    fun update(allowScripts: Boolean? = null, allowEffects: Boolean? = null, allowCommands: Boolean? = null): RemotePolicySettings {
        synchronized(this) {
            val was = settings
            val next = RemotePolicySettings(
                allowScripts = allowScripts ?: was.allowScripts,
                allowEffects = allowEffects ?: was.allowEffects,
                allowCommands = allowCommands ?: was.allowCommands,
            )
            transaction(database) {
                val row = DaoRemoteAccessSettings.all().firstOrNull() ?: DaoRemoteAccessSettings.new { updatedAt = nowUtc() }
                row.allowScripts = next.allowScripts
                row.allowEffects = next.allowEffects
                row.allowCommands = next.allowCommands
                row.updatedAt = nowUtc()
            }
            current.set(next)
            return next
        }
    }

    private fun load(): RemotePolicySettings = transaction(database) {
        DaoRemoteAccessSettings.all().firstOrNull()?.let { RemotePolicySettings(it.allowScripts, it.allowEffects, it.allowCommands) }
    } ?: RemotePolicySettings()
}
