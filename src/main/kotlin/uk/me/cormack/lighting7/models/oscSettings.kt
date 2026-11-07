package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

/**
 * This machine's OSC listener (OSC plan D5, D13): at most one row; no row is "off, every interface,
 * UDP 8415". Machine-local and never synced — which network the desk listens on is a fact about the
 * computer, not the show. See `docs/osc-engineering.md`.
 */
object DaoOscSettingsTable : IntIdTable("osc_settings") {
    val enabled = bool("enabled").default(false)
    /** An IP literal to bind to; null for every interface. */
    val bindAddress = varchar("bind_address", 64).nullable()
    val port = integer("port").default(OSC_DEFAULT_PORT)
    val updatedAt = utcInstant("updated_at")
}

class DaoOscSettings(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoOscSettings>(DaoOscSettingsTable)

    var enabled by DaoOscSettingsTable.enabled
    var bindAddress by DaoOscSettingsTable.bindAddress
    var port by DaoOscSettingsTable.port
    var updatedAt by DaoOscSettingsTable.updatedAt
}

/**
 * The senders this desk hears OSC from (D5): one row each, an IP address and the name the log and
 * History show. A packet from any other address is dropped before it is parsed. Machine-local.
 */
object DaoOscSendersTable : IntIdTable("osc_senders") {
    val name = varchar("name", 100)
    /** The sender's IP literal, in the canonical form `InetAddress.hostAddress` gives. */
    val address = varchar("address", 64)
}

class DaoOscSender(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoOscSender>(DaoOscSendersTable)

    var name by DaoOscSendersTable.name
    var address by DaoOscSendersTable.address
}

/** UDP 8415, beside the desk's 8413 and the public listener's 8414 (D13). */
const val OSC_DEFAULT_PORT = 8415
