package uk.me.cormack.lighting7.dmx

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.models.DaoParkedChannel
import uk.me.cormack.lighting7.models.DaoParkedChannels
import uk.me.cormack.lighting7.models.DaoProject
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents a parked channel entry.
 */
data class ParkedChannel(
    val universe: Int,
    val channel: Int,
    val value: UByte,
)

/**
 * Hand-off sink invoked when park is released on a channel.
 *
 * Park sits above every other layer, so the value underneath it is whatever the
 * layers below happened to leave there — usually 0, or a stale value from before the
 * channel was parked. Dropping the override without doing anything else therefore
 * snaps the output from the parked value to that unrelated one. On a hard-powered
 * fixture hung off a dimmer that snap is the hazard park exists to prevent, so the
 * parked value is written *down* into the layers below before the override goes away:
 * unparking hands control back at the value the rig is already emitting, and
 * park → unpark → park is a no-op on the wire.
 *
 * Implementations must make the handed-off value the resolved output of the layers
 * below park — in production that means both the direct-write store (Layer 2) and the
 * controller's channel buffer, i.e. exactly what a manual `updateChannel` would do.
 */
fun interface UnparkValueSink {
    suspend fun handOff(values: List<ParkedChannel>)
}

/**
 * Manages parked DMX channels — channels locked at a fixed output value
 * that overrides all other sources (scenes, scripts, effects, manual control).
 *
 * Park is the highest-priority output layer, matching the behaviour of
 * professional lighting consoles (ETC Eos, ChamSys MagicQ, grandMA3).
 */
class ParkManager(
    private val database: Database,
    private val projectId: Int,
    private val unparkValueSink: UnparkValueSink? = null,
) : ParkSource {
    // In-memory park state: universe -> (channel -> value)
    private val parkedChannels = ConcurrentHashMap<Int, ConcurrentHashMap<Int, UByte>>()

    // Flow for notifying WebSocket clients of park state changes. A [MutableStateFlow] rather
    // than a replay-1 [kotlinx.coroutines.flow.MutableSharedFlow] so that "the park list is
    // empty" is a value a subscriber can observe, not the absence of one — that is what makes a
    // new WebSocket connection's park snapshot unconditional (see the one-snapshot rule in
    // docs/websocket-engineering.md) instead of contingent on something having parked first.
    private val _parkStateFlow = MutableStateFlow<List<ParkedChannel>>(emptyList())
    val parkStateFlow: StateFlow<List<ParkedChannel>> = _parkStateFlow.asStateFlow()

    /**
     * Load parked channels from the database. Call once after construction.
     */
    fun loadFromDatabase() {
        transaction(database) {
            DaoParkedChannel.find { DaoParkedChannels.project eq projectId }
                .forEach { row ->
                    parkedChannels
                        .getOrPut(row.universe) { ConcurrentHashMap() }[row.channel] = row.value.toUByte()
                }
        }
        emitState()
    }

    /**
     * Park a channel at the given value.
     */
    suspend fun park(universe: Int, channel: Int, value: UByte) {
        require(channel in 1..512) { "Channel must be between 1 and 512, got $channel" }

        parkedChannels.getOrPut(universe) { ConcurrentHashMap() }[channel] = value

        // Persist to database
        transaction(database) {
            val existing = DaoParkedChannel.find {
                (DaoParkedChannels.project eq projectId) and
                    (DaoParkedChannels.universe eq universe) and
                    (DaoParkedChannels.channel eq channel)
            }.firstOrNull()

            if (existing != null) {
                existing.value = value.toInt()
            } else {
                DaoParkedChannel.new {
                    this.project = DaoProject.findById(projectId)!!
                    this.universe = universe
                    this.channel = channel
                    this.value = value.toInt()
                }
            }
        }

        emitState()
    }

    /**
     * Unpark a channel, leaving the output where it was.
     *
     * The parked value is handed down to the layers below park *before* the override is
     * removed, so no transmit frame can land in the window where park is gone but the
     * old value underneath is still in place. See [UnparkValueSink].
     */
    suspend fun unpark(universe: Int, channel: Int) {
        parkedChannels[universe]?.get(channel)?.let { parkedValue ->
            unparkValueSink?.handOff(listOf(ParkedChannel(universe, channel, parkedValue)))
        }

        parkedChannels[universe]?.remove(channel)

        transaction(database) {
            DaoParkedChannel.find {
                (DaoParkedChannels.project eq projectId) and
                    (DaoParkedChannels.universe eq universe) and
                    (DaoParkedChannels.channel eq channel)
            }.firstOrNull()?.delete()
        }

        emitState()
    }

    /**
     * Unpark all channels, leaving every output where it was. Same hand-off ordering as
     * [unpark].
     */
    suspend fun unparkAll() {
        getAllParked().takeIf { it.isNotEmpty() }?.let { unparkValueSink?.handOff(it) }

        parkedChannels.clear()

        transaction(database) {
            DaoParkedChannel.find { DaoParkedChannels.project eq projectId }
                .forEach { it.delete() }
        }

        emitState()
    }

    /**
     * Check if a channel is parked.
     */
    override fun isParked(universe: Int, channel: Int): Boolean {
        return parkedChannels[universe]?.containsKey(channel) == true
    }

    /**
     * Get the parked value for a channel, or null if not parked.
     */
    override fun getParkedValue(universe: Int, channel: Int): UByte? {
        return parkedChannels[universe]?.get(channel)
    }

    override fun universeView(universe: Int): Map<Int, UByte>? = parkedChannels[universe]

    /**
     * Get all parked channels as a flat list.
     */
    fun getAllParked(): List<ParkedChannel> {
        return parkedChannels.flatMap { (universe, channels) ->
            channels.map { (channel, value) ->
                ParkedChannel(universe, channel, value)
            }
        }
    }

    private fun emitState() {
        _parkStateFlow.value = getAllParked()
    }
}
