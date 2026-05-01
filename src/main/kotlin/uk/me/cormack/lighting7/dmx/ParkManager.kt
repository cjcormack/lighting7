package uk.me.cormack.lighting7.dmx

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
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
 * Manages parked DMX channels — channels locked at a fixed output value
 * that overrides all other sources (scenes, scripts, effects, manual control).
 *
 * Park is the highest-priority output layer, matching the behaviour of
 * professional lighting consoles (ETC Eos, ChamSys MagicQ, grandMA3).
 */
class ParkManager(
    private val database: Database,
    private val projectId: Int,
) : ParkSource {
    // In-memory park state: universe -> (channel -> value)
    private val parkedChannels = ConcurrentHashMap<Int, ConcurrentHashMap<Int, UByte>>()

    // Flow for notifying WebSocket clients of park state changes
    private val _parkStateFlow = MutableSharedFlow<List<ParkedChannel>>(replay = 1)
    val parkStateFlow = _parkStateFlow.asSharedFlow()

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
        // Emit initial state so flow subscribers (WebSocket connections) get the correct replay value
        runBlocking { emitState() }
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
     * Unpark a channel.
     */
    suspend fun unpark(universe: Int, channel: Int) {
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
     * Unpark all channels.
     */
    suspend fun unparkAll() {
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

    private suspend fun emitState() {
        _parkStateFlow.emit(getAllParked())
    }
}
