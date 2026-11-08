package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

/**
 * What a remote caller may do on this machine, at most one row: `cloud/RemotePolicy.kt`'s switches.
 *
 * Machine-local and never synced — whether this desk lets the internet run a script or fire a cannon
 * is a fact about the machine, not about a show. No row means every switch off.
 */
object DaoRemoteAccessSettingsTable : IntIdTable("remote_access_settings") {
    /** Whether a remote request may compile, run or save scripts. */
    val allowScripts = bool("allow_scripts").default(false)
    /**
     * Whether a remote request may arm the desk, fire a one-shot trigger or reload one: a confetti
     * cannon spends something physical, in a room the remote caller cannot see.
     */
    val allowEffects = bool("allow_effects").default(false)
    /**
     * Whether a remote request — MCP included — may run a fixture command: a reset, a lamp strike, a
     * lamp off. A lamp off is a dark head for eight minutes, in a room the remote caller cannot see.
     */
    val allowCommands = bool("allow_commands").default(false)
    val updatedAt = utcInstant("updated_at")
}

class DaoRemoteAccessSettings(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoRemoteAccessSettings>(DaoRemoteAccessSettingsTable)

    var allowScripts by DaoRemoteAccessSettingsTable.allowScripts
    var allowEffects by DaoRemoteAccessSettingsTable.allowEffects
    var allowCommands by DaoRemoteAccessSettingsTable.allowCommands
    var updatedAt by DaoRemoteAccessSettingsTable.updatedAt
}
