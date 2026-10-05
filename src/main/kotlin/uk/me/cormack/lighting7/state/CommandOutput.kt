package uk.me.cormack.lighting7.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.ParkSource
import uk.me.cormack.lighting7.dmx.TransmitModifier
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.FixtureCommands
import uk.me.cormack.lighting7.fixture.ResolvedCommand
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.show.FixturesChangeListener
import java.time.Instant

private val logger = LoggerFactory.getLogger("CommandOutput")

/**
 * Every fixture command's channel on the patched rig, and the command holds in flight (fixture
 * optics plan session 7, D13). One per show.
 *
 * **Where it sits.** Above composition and under park, through the controllers' [ParkSource]
 * (`Show.outputSource`), beside the trigger output — they never own the same channel. A park is the
 * operator's hand on the channel and beats everything, so a command is refused while any channel it
 * would hold is parked ([run], [Outcome.Parked]) rather than sent and silently overridden; and a park
 * that would itself be a command — a level in a command's band, or anything but idle on a dedicated
 * channel — is refused live and passed over at the output ([parkRefusal], [admitsPark]).
 *
 * **What it holds.**
 * - A **dedicated** command channel (no property covers it: the Revolution's reset, the Robe's
 *   control channel, the Varytec's and the Shehds' reset) is the desk's outright: held at its idle
 *   level always, at the command's level for the hold. A raw channel write on it is dropped
 *   (`ChannelSocket`).
 * - A **shared** channel (the MAC 250's shutter, the Fusion's motor mode, the Orbit's program, the
 *   Slender's special function) belongs to composition except during a hold. Its band guard is a
 *   [TransmitModifier]: any value composition, an effect, a script or a raw write puts there that falls
 *   in a command's band is sent as the channel's idle level — the half of "no path can hold a reset"
 *   that a name check cannot reach, since `"208"` on a strobe is a strobe row by name.
 * - During a hold, the command's level on its channel and every precondition channel it declares
 *   (`alongside`) at theirs. When the hold ends the channels go back — a dedicated one straight to idle,
 *   with no fade (the Revolution's manual: "then set the channel to 0% without timing or fading"), a
 *   shared one to whatever composition is sending.
 *
 * **What ends a hold.** Its time ([Ended.COMPLETED]); the show closing — a project switch or shutdown
 * ([close]), the fixture being repatched away mid-hold, or a park laid on one of the channels it holds
 * — the park beats the hold, so the fixture stopped seeing the command — ([Ended.INTERRUPTED]; the hold
 * looks every [PARK_POLL_MS]). A blackout does not:
 * held values bypass the transmit modifiers, as a park does, so a reset or a lamp strike already under
 * way is never cut short by a fader. Blind refuses a command outright — it would reach the rig.
 *
 * One command per fixture at a time ([Outcome.Busy] — `COMMAND_BUSY`); different fixtures run
 * together. Nothing is persisted: a restart comes up with no hold.
 */
class CommandOutput(
    private val fixtures: Fixtures,
    /** The operator's parks: a command whose channel is parked is refused. */
    private val parks: ParkSource,
    /** Whether the programmer is blind: a command is refused then. */
    private val isBlind: () -> Boolean,
) : ParkSource, FixturesChangeListener, TransmitModifier {

    /** A dedicated command channel: whose it is, and the commands on it. */
    data class Owned(val fixtureKey: String, val fixtureName: String, val commands: List<ResolvedCommand>) {
        val idleLevel: UByte get() = commands.first().idleLevel
    }

    /** A shared command channel's bands, and what a value inside one is sent as. */
    private class Guard(val ranges: List<UIntRange>, val idle: UByte)

    /** One command being held. */
    data class Running(
        val fixtureKey: String,
        val fixtureName: String,
        val command: ResolvedCommand,
        val startedAt: Instant,
        val endsAt: Instant,
        /** Completes when the hold ends, with how. */
        val done: CompletableDeferred<Ended>,
    )

    enum class Ended { COMPLETED, INTERRUPTED }

    /** What a request to run one command came to. */
    sealed interface Outcome {
        data class Started(val running: Running) : Outcome
        /** The fixture is holding another command. */
        data class Busy(val running: Running) : Outcome
        data class Unknown(val message: String) : Outcome
        data object Blind : Outcome
        data class Parked(val message: String) : Outcome
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Fixture key → its hold in flight. Guarded by [lock]. */
    private val running = HashMap<String, Pair<Running, Job>>()

    @Volatile private var views: Map<Int, Map<Int, UByte>> = emptyMap()
    @Volatile private var owned: Map<Long, Owned> = emptyMap()
    @Volatile private var guards: Map<Long, Guard> = emptyMap()

    /**
     * [guards] laid out for the transmit loop: universe → a 513-slot array indexed by channel. The
     * guard is asked about every channel of every frame, so it must not box a key per call — a
     * universe number is a small `Int` the boxing cache already holds, and the channel is an index.
     */
    @Volatile private var guardsByUniverse: Map<Int, Array<Guard?>> = emptyMap()
    @Volatile private var closed = false

    private val controllers = mutableListOf<DmxController>()

    init {
        rebuild()
    }

    // ─── ParkSource ──────────────────────────────────────────────────────────────────────────

    override fun getParkedValue(universe: Int, channel: Int): UByte? = views[universe]?.get(channel)

    /** Held, never parked: a command channel is not the operator's park. */
    override fun isParked(universe: Int, channel: Int): Boolean = false

    override fun universeView(universe: Int): Map<Int, UByte>? = views[universe]

    // ─── The band guard ──────────────────────────────────────────────────────────────────────

    /** A value in a command's band on a shared channel, outside a hold, goes out as the channel's idle level. */
    override fun modify(universe: Universe, channel: Int, value: UByte): UByte {
        val byChannel = guardsByUniverse[universe.universe] ?: return value
        val guard = byChannel.getOrNull(channel) ?: return value
        for (range in guard.ranges) if (value in range) return guard.idle
        return value
    }

    /**
     * Register the band guard on every controller; [controllersChanged] keeps it registered across
     * patch rebuilds. The show registers this as a fixtures listener when it builds it.
     */
    fun attach() = reattach()

    override fun controllersChanged() = reattach()

    private fun reattach() {
        synchronized(controllers) {
            for (c in controllers) c.removeTransmitModifier(this)
            controllers.clear()
            if (closed) return
            for (c in fixtures.controllers) {
                c.addTransmitModifier(this)
                controllers += c
            }
        }
    }

    // ─── Running a command ───────────────────────────────────────────────────────────────────

    /** The commands of [fixtureKey] on the rig as loaded; empty for a fixture with none. */
    fun commandsOf(fixtureKey: String): List<ResolvedCommand> =
        runCatching { FixtureCommands.of(fixtures.untypedFixture(fixtureKey)) }.getOrDefault(emptyList())

    /** [fixtureKey]'s hold in flight, if any. */
    fun runningOn(fixtureKey: String): Running? = synchronized(lock) { running[fixtureKey]?.first }

    /**
     * Start [commandName] on [fixtureKey]: hold its level, and its preconditions, for its declared time.
     * Returns at once; [Running.done] completes when the hold ends.
     */
    fun run(fixtureKey: String, commandName: String): Outcome {
        val fixture = runCatching { fixtures.untypedFixture(fixtureKey) }.getOrNull()
            ?: return Outcome.Unknown("'$fixtureKey' is not a patched fixture in the current project")
        val commands = FixtureCommands.of(fixture)
        val command = commands.firstOrNull { it.name.equals(commandName, ignoreCase = true) }
            ?: return Outcome.Unknown(
                if (commands.isEmpty()) "'${fixture.fixtureName}' has no commands"
                else "'${fixture.fixtureName}' has no command '$commandName' — it has ${commands.joinToString { it.name }}",
            )
        if (isBlind()) return Outcome.Blind
        val channels = listOf(command.channelNo) + command.alongside.map { it.channelNo }
        channels.firstOrNull { parks.isParked(command.universe, it) }?.let { ch ->
            return Outcome.Parked(
                "Universe ${command.universe} channel $ch is parked, so '${command.spec.label}' on '${fixture.fixtureName}' " +
                    "would not reach the fixture — unpark it first",
            )
        }
        val started: Running
        synchronized(lock) {
            if (closed) return Outcome.Unknown("The show is closing")
            running[fixtureKey]?.let { return Outcome.Busy(it.first) }
            val now = nowUtc()
            started = Running(fixtureKey, fixture.fixtureName, command, now, now.plusMillis(command.spec.holdMs), CompletableDeferred())
            val job = scope.launch {
                // A park beats the hold, so one laid on a held channel mid-hold means the fixture
                // stopped seeing the command: watch for it rather than report a hold that never landed.
                val endAt = System.nanoTime() + command.spec.holdMs * 1_000_000
                while (true) {
                    val left = (endAt - System.nanoTime()) / 1_000_000
                    if (left <= 0) break
                    delay(minOf(left, PARK_POLL_MS))
                    channels.firstOrNull { parks.isParked(command.universe, it) }?.let { ch ->
                        logger.warn("Command '{}' on '{}' cut short: universe {} channel {} was parked mid-hold", command.name, fixtureKey, command.universe, ch)
                        finish(fixtureKey, started, Ended.INTERRUPTED)
                        return@launch
                    }
                }
                finish(fixtureKey, started, Ended.COMPLETED)
            }
            running[fixtureKey] = started to job
            rebuildLocked()
        }
        logger.info("Command '{}' on '{}': holding {} on {}/{} for {} ms", command.name, fixtureKey, command.level, command.universe, command.channelNo, command.spec.holdMs)
        return Outcome.Started(started)
    }

    private fun finish(fixtureKey: String, which: Running, how: Ended) {
        synchronized(lock) {
            val current = running[fixtureKey] ?: return
            if (current.first !== which) return
            running.remove(fixtureKey)
            rebuildLocked()
        }
        which.done.complete(how)
        logger.info("Command '{}' on '{}' {}", which.command.name, fixtureKey, if (how == Ended.COMPLETED) "released" else "interrupted")
    }

    /** End every hold now — the show is going (a project switch, shutdown). */
    fun close() {
        val ended: List<Running>
        synchronized(lock) {
            closed = true
            ended = running.values.map { it.first }
            running.values.forEach { it.second.cancel() }
            running.clear()
            rebuildLocked()
        }
        ended.forEach { it.done.complete(Ended.INTERRUPTED) }
        scope.cancel()
        fixtures.unregisterListener(this)
        reattach()
    }

    // ─── Parks and raw writes ────────────────────────────────────────────────────────────────

    /** The dedicated command channel at this address, if any — what a raw channel write is checked against. */
    fun ownerOf(universe: Int, channel: Int): Owned? = owned[key(universe, channel)]

    /** Whether a park of [value] here may reach the wire — [parkRefusal]'s output half. */
    fun admitsPark(universe: Int, channel: Int, value: UByte): Boolean = parkRefusal(universe, channel, value) == null

    /**
     * Why a park of [value] on this channel is refused, or null. A park beats the command output, so a
     * park that *is* a command would hold the fixture in it — a reset forever. On a dedicated channel
     * that is anything but idle (its bands are the manual's, and several say only "Reset"); on a shared
     * one, a level in a command's band.
     */
    fun parkRefusal(universe: Int, channel: Int, value: UByte): String? {
        val k = key(universe, channel)
        owned[k]?.let { owner ->
            if (value == owner.idleLevel) return null
            return "Universe $universe channel $channel is '${owner.fixtureName}'s command channel " +
                "(${owner.commands.joinToString { it.spec.label }}): a park at $value would hold a command. " +
                "Park it at ${owner.idleLevel} to lock it out, or run a command from the fixture's Commands menu."
        }
        val guard = guards[k] ?: return null
        if (guard.ranges.none { value in it }) return null
        return "A park at $value on universe $universe channel $channel would hold a fixture command " +
            "(its band ${guard.ranges.joinToString { "${it.first}–${it.last}" }}). Run it from the fixture's Commands menu instead."
    }

    // ─── Rebuild ─────────────────────────────────────────────────────────────────────────────

    override fun fixturesChanged() {
        val interrupted = mutableListOf<Running>()
        synchronized(lock) {
            // A hold whose fixture, or whose command, went in a repatch has nothing left to hold.
            val gone = running.filter { (key, held) ->
                val now = runCatching { FixtureCommands.of(fixtures.untypedFixture(key)) }.getOrDefault(emptyList())
                now.none { it.name == held.first.command.name && it.channelNo == held.first.command.channelNo && it.universe == held.first.command.universe }
            }
            gone.forEach { (key, held) ->
                held.second.cancel()
                running.remove(key)
                interrupted += held.first
            }
            rebuildLocked()
        }
        interrupted.forEach {
            it.done.complete(Ended.INTERRUPTED)
            logger.warn("Command '{}' on '{}' interrupted: the fixture was repatched", it.command.name, it.fixtureKey)
        }
    }

    private fun rebuild() = synchronized(lock) { rebuildLocked() }

    private fun rebuildLocked() {
        val nextViews = HashMap<Int, HashMap<Int, UByte>>()
        val nextOwned = HashMap<Long, Owned>()
        val nextGuards = HashMap<Long, Guard>()
        if (!closed) {
            for (fixture in fixtures.fixtures) {
                val commands = FixtureCommands.of(fixture)
                if (commands.isEmpty()) continue
                for ((channel, onChannel) in commands.groupBy { it.universe to it.channelNo }) {
                    val (universe, channelNo) = channel
                    val idle = onChannel.first().idleLevel
                    if (onChannel.first().dedicated) {
                        nextViews.getOrPut(universe) { HashMap() }[channelNo] = idle
                        nextOwned[key(universe, channelNo)] = Owned(fixture.key, fixture.fixtureName, onChannel)
                    } else {
                        nextGuards[key(universe, channelNo)] = Guard(onChannel.map { it.bandMin..it.bandMax }, idle)
                    }
                }
            }
            for ((held, _) in running.values) {
                val c = held.command
                val channels = nextViews.getOrPut(c.universe) { HashMap() }
                channels[c.channelNo] = c.level
                for (pre in c.alongside) channels[pre.channelNo] = pre.level
            }
        }
        owned = nextOwned
        guards = nextGuards
        guardsByUniverse = nextGuards.entries.groupBy({ (it.key shr 32).toInt() }, { it }).mapValues { (_, entries) ->
            arrayOfNulls<Guard>(513).also { array ->
                entries.forEach { e -> (e.key and 0xffffffffL).toInt().takeIf { it in 1..512 }?.let { array[it] = e.value } }
            }
        }
        views = nextViews
    }

    private fun key(universe: Int, channel: Int): Long = (universe.toLong() shl 32) or channel.toLong()

    private companion object {
        /** How often a hold looks for a park laid on one of its channels. */
        const val PARK_POLL_MS = 100L
    }
}
