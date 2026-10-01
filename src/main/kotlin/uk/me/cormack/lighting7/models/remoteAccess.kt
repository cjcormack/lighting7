package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

/**
 * Remote access (the ngrok tunnel) for this machine: at most one row.
 *
 * Machine-local and never synced — it names *this* desk's public address and whether this desk is
 * reachable from the internet, which is a fact about the machine, not about a show. The ngrok
 * authtoken is deliberately not a column: it is a secret and lives in the `CredentialStore`
 * (keychain or encrypted file), beside the GitHub tokens. See `docs/mcp-engineering.md`
 * §"Remote access".
 *
 * No row means "never saved from the desk": `local.conf`'s `mcp.tunnel.*` keys are the defaults
 * until the first save, after which this row wins.
 */
object DaoRemoteAccessSettingsTable : IntIdTable("remote_access_settings") {
    val enabled = bool("enabled").default(false)
    /** The ngrok domain without scheme, e.g. `desk-name.ngrok-free.app`. Null until set. */
    val domain = varchar("domain", 253).nullable()
    /** Whether a remote request may compile, run or save scripts. Off by default. */
    val allowScripts = bool("allow_scripts").default(false)
    /**
     * Whether a remote request may arm the desk, fire a one-shot trigger or reload one (stage-view
     * plan session 9, D16). Off by default: a confetti cannon spends something physical, in a room
     * the remote caller cannot see.
     */
    val allowEffects = bool("allow_effects").default(false)
    val updatedAt = utcInstant("updated_at")
}

class DaoRemoteAccessSettings(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoRemoteAccessSettings>(DaoRemoteAccessSettingsTable)

    var enabled by DaoRemoteAccessSettingsTable.enabled
    var domain by DaoRemoteAccessSettingsTable.domain
    var allowScripts by DaoRemoteAccessSettingsTable.allowScripts
    var allowEffects by DaoRemoteAccessSettingsTable.allowEffects
    var updatedAt by DaoRemoteAccessSettingsTable.updatedAt
}
