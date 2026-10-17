package uk.me.cormack.lighting7.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.dmx.OutputHold
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.FixtureCommands
import uk.me.cormack.lighting7.fixture.FixtureTriggers
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.models.AddressFacet
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoShowOverride
import uk.me.cormack.lighting7.models.DaoShowOverrides
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.models.decodeFacet
import uk.me.cormack.lighting7.show.Show
import uk.me.cormack.lighting7.venue.ShowBehind
import uk.me.cormack.lighting7.venue.VenueBehind
import uk.me.cormack.lighting7.venue.VenueException
import uk.me.cormack.lighting7.venue.VenuePins

private val logger = LoggerFactory.getLogger("VenueHoldService")

/**
 * The desk's **hold** (venue plan P5, D7; session 6): whether the current show — a show on a venue
 * whose pinned version is behind the installed one *on output* ([ShowBehind]) — is kept off the rig,
 * and whether the operator has chosen to **run against** its older pin for now.
 *
 * - **Judged by the loader**, before it builds a controller (`DbFixtureLoader`, through the show's
 *   [uk.me.cormack.lighting7.show.RigHold]): every load of the current project — opening it, a patch
 *   edit, an Update, a sync pull that moves the pin — judges afresh. Held means every controller sends
 *   zero on every channel but a parked one; the show still composes and draws. A publish or a venue pull
 *   that installs a newer version re-judges the live show too ([refreshCurrent]), but never blacks out
 *   one that is running: it carries on *run against* its pin, guarded, and opens held next time.
 * - **Run against** lifts the hold for **this pin pair on this desk run**: it is runtime only, survives
 *   every reload of the same project at the same pinned and installed versions (a patch edit, a pull
 *   that leaves the pin), and is dropped by opening another project, by the pin moving (an Update) and
 *   by a newer version being installed — each a new question the operator has not answered. A restart
 *   comes up held. While it runs, the **installed** version's trigger, arm and command channels stay
 *   reserved idle ([OutputHold.Reserve]).
 * - **Arming, firing and fixture commands are refused** — 409 `VENUE_HOLD` — while the show is behind
 *   on output, held or running against: held, nothing would reach the rig; running against, the old
 *   patch's cannon may sit where the hall now has a dimmer, and the hall's own cannon is reserved idle,
 *   so a fire would be announced and drawn and never happen. A rehearsal — drawn, never sent — is not
 *   refused. Programmer Blind is untouched: it is the programmer's gate, this is the show's.
 *
 * Published as `venue.hold` ([status], a `StateFlow`, so the subscription is the connect snapshot);
 * outbound only — *Run against* is REST (`routes/venueUpdate.kt`).
 */
class VenueHoldService(private val state: State) {

    /** The current show's hold, as `venue.hold` carries it. */
    data class Status(
        val projectId: Int? = null,
        val held: Boolean = false,
        val pinned: Int? = null,
        val installed: Int? = null,
        val runAgainst: Boolean = false,
        val behind: VenueBehind = VenueBehind.NONE,
        val venueName: String? = null,
    )

    /** A *Run against* answer: for this project at exactly this pin and installed version. */
    private data class RunAgainst(val projectId: Int, val pinned: Int?, val installed: Int?)

    private val lock = Any()
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile
    private var runAgainst: RunAgainst? = null

    /**
     * Judge [projectId]'s hold for [show] and apply it to the show's [OutputHold] — the loader's call,
     * before any controller exists. Blocking.
     */
    fun judge(projectId: Int, show: Show) {
        val judgement = ShowBehind.judge(state, projectId)
        val output = show.rigHold.output
        val key = RunAgainst(projectId, judgement.pinned, judgement.installed)
        val reserve = synchronized(lock) {
            // Another project, a moved pin or a newer installed version: the answer was to another question.
            if (runAgainst != null && runAgainst != key) runAgainst = null
            judgement.behind == VenueBehind.OUTPUT && runAgainst == key
        }
        // Read outside the lock (the database, and fixture types instantiated), applied inside it.
        val reserved = if (reserve) runCatching { reservedChannels(judgement) }
            .onFailure { logger.warn("Could not read v{}'s trigger and command channels for project {}: {}", judgement.installed, projectId, it.message) }
            .getOrNull() else emptyMap()
        val (previous, next) = synchronized(lock) {
            // An answer withdrawn while the channels were read is withdrawn; and reserving that failed
            // holds rather than running against an unguarded rig.
            val running = reserve && runAgainst == key && reserved != null
            val held = judgement.behind == VenueBehind.OUTPUT && !running
            output.setReserved(if (running) reserved.orEmpty() else emptyMap())
            output.setHeld(held)
            val next = Status(
                projectId = projectId,
                held = held,
                pinned = judgement.pinned,
                installed = judgement.installed,
                runAgainst = running,
                behind = judgement.behind,
                venueName = judgement.venueName,
            )
            val previous = _status.value
            _status.value = next
            previous to next
        }
        if (next != previous && (next.held || next.runAgainst)) {
            logger.warn(
                "Project {} is on {} v{} with v{} installed, behind on output: {}",
                projectId, judgement.venueName, judgement.pinned, judgement.installed,
                if (next.held) "output held" else "running against v${judgement.pinned}, v${judgement.installed}'s trigger and command channels reserved",
            )
        }
        // An arm is a promise the cannons can fire; a show behind on output cannot keep it.
        if (judgement.behind == VenueBehind.OUTPUT && state.effectsService.armed.value.armed) {
            state.effectsService.disarm("the show is behind its venue on output")
        }
    }

    /**
     * Re-judge the current show — a publish or a venue pull may have installed a newer version. A show
     * that is live and **not** held when that puts it behind on output is not blacked out mid-run (venue
     * plan D2: a venue change reaches a show only through Update): it carries on as if run against its
     * pin — output live, the new version's trigger and command channels reserved, arm refused — and
     * opens held the next time it is opened. A show already held stays held. Decided with Chris,
     * 2026-10-11, over blacking it out at once (an unattended venue sync could do that to a performance).
     */
    fun refreshCurrent() {
        val show = state.showOrNull ?: return
        val projectId = runCatching { state.projectManager.currentProject.id.value }.getOrNull() ?: return
        runCatching {
            val before = _status.value
            val judgement = ShowBehind.judge(state, projectId)
            if (judgement.behind == VenueBehind.OUTPUT && !(before.projectId == projectId && before.held)) {
                synchronized(lock) { runAgainst = RunAgainst(projectId, judgement.pinned, judgement.installed) }
                logger.warn(
                    "{} v{} was installed while project {} ran on v{}: it stays live, guarded, until it is reopened or updated",
                    judgement.venueName, judgement.installed, projectId, judgement.pinned,
                )
            }
            judge(projectId, show)
        }.onFailure { logger.warn("Could not re-judge project {}'s hold: {}", projectId, it.message) }
    }

    /**
     * *Run against* the current show's older pin ([on]), or put the hold back. Refused with 409
     * [CODE_NOT_BEHIND] when the show is not behind on output — there is nothing to run against.
     */
    fun setRunAgainst(projectId: Int, on: Boolean): Status {
        val judgement = ShowBehind.judge(state, projectId)
        synchronized(lock) {
            if (on) {
                if (judgement.behind != VenueBehind.OUTPUT) {
                    throw VenueException.conflict(
                        CODE_NOT_BEHIND,
                        if (judgement.pinned == null) "This show is not on a venue: there is no older patch to run against"
                        else "This show's v${judgement.pinned} sends what v${judgement.installed ?: judgement.pinned} would: there is no hold to lift",
                    )
                }
                runAgainst = RunAgainst(projectId, judgement.pinned, judgement.installed)
            } else {
                runAgainst = null
            }
        }
        logger.info("Project {}: {}", projectId, if (on) "running against v${judgement.pinned}" else "hold restored")
        judge(projectId, state.show)
        return _status.value
    }

    /**
     * Run [projectId] against [pinned] with [installed] from its next load: an attach that puts the
     * running show behind on output (venue-attach plan §4.4), as [refreshCurrent] does for a publish —
     * recorded before the reload, whose loader [judge]s it, since a standalone show had no hold to lift.
     */
    fun runAgainstOnLoad(projectId: Int, pinned: Int, installed: Int) {
        synchronized(lock) { runAgainst = RunAgainst(projectId, pinned, installed) }
    }

    /**
     * Why an arm, a fire or a fixture command is refused now (409 [CODE_VENUE_HOLD]), or null. The
     * current show's: the arm and the cannons are only ever the current project's.
     */
    fun effectsRefusal(): String? {
        val s = _status.value
        if (s.behind != VenueBehind.OUTPUT) return null
        val current = runCatching { state.projectManager.currentProject.id.value }.getOrNull()
        if (current != s.projectId) return null
        val venue = s.venueName ?: "its venue"
        return if (s.runAgainst) {
            "Running against $venue v${s.pinned}: v${s.installed}'s trigger and command channels stay guarded, so " +
                "nothing can be armed, fired or run until this show is updated"
        } else {
            "Output is held: this show uses $venue v${s.pinned} and the hall is v${s.installed}. " +
                "Update the show, or run against v${s.pinned}, first — nothing can be armed, fired or run while it is behind"
        }
    }

    /** [effectsRefusal] as the exception a route answers with. */
    fun refuseEffectsIfHeld() {
        effectsRefusal()?.let { throw VenueException.conflict(CODE_VENUE_HOLD, it) }
    }

    /**
     * The installed version's one-shot trigger, arm and fixture-command channels — and, for a unit the
     * show re-addresses, the same at the show's address — as the controllers guard them while the show
     * runs against an older pin. Every installed patch counts, hidden ones too: a hide takes a unit out
     * of the show, not off the bar.
     */
    private fun reservedChannels(judgement: ShowBehind.Judgement): Map<Int, Map<Int, OutputHold.Reserve>> {
        val venueUuid = judgement.venueUuid ?: return emptyMap()
        val installed = judgement.installed ?: return emptyMap()
        val sites = transaction(state.database) {
            val snapshot = VenuePins.snapshotOf(venueUuid, installed) ?: return@transaction emptyList()
            val universes = (DaoUniverseConfig.find { DaoUniverseConfigs.project eq snapshot.id } +
                DaoUniverseConfig.find { DaoUniverseConfigs.project eq judgement.projectId })
                .associateBy { it.uuid.toString() }
            val addresses = DaoShowOverride.find {
                (DaoShowOverrides.project eq judgement.projectId) and (DaoShowOverrides.facet eq PatchFacet.ADDRESS)
            }.mapNotNull { row -> (decodeFacet(PatchFacet.ADDRESS, row.value) as AddressFacet?)?.let { row.recordUuid to it } }.toMap()
            DaoFixturePatch.find { DaoFixturePatches.project eq snapshot.id }.flatMap { patch ->
                val own = Site(patch.fixtureTypeKey, patch.key, patch.displayName, Universe(patch.universeConfig.subnet, patch.universeConfig.universe), patch.startChannel)
                val moved = addresses[patch.uuid]?.let { a ->
                    universes[a.universeConfigUuid]?.let { u -> own.copy(universe = Universe(u.subnet, u.universe), startChannel = a.startChannel) }
                }
                listOfNotNull(own, moved)
            }
        }
        val out = HashMap<Int, HashMap<Int, OutputHold.Reserve>>()
        fun put(universe: Int, channel: Int, reserve: OutputHold.Reserve) {
            val byChannel = out.getOrPut(universe) { HashMap() }
            // A fixed idle beats a band: the channel is a trigger or a dedicated command for someone.
            if (byChannel[channel] !is OutputHold.Reserve.Fixed) byChannel[channel] = reserve
        }
        for (site in sites) {
            val fixture = runCatching {
                FixtureTypeRegistry.instantiateByTypeKey(site.typeKey, site.universe, site.key, site.name, site.startChannel)
            }.getOrNull() ?: continue
            for (t in FixtureTriggers.of(fixture)) {
                put(t.universe, t.channelNo, OutputHold.Reserve.Fixed(FixtureTriggers.IDLE_LEVEL))
                put(t.universe, t.armChannelNo, OutputHold.Reserve.Fixed(FixtureTriggers.IDLE_LEVEL))
            }
            for ((channel, commands) in FixtureCommands.of(fixture).groupBy { it.universe to it.channelNo }) {
                val first = commands.first()
                put(
                    channel.first, channel.second,
                    if (first.dedicated) OutputHold.Reserve.Fixed(first.idleLevel)
                    else OutputHold.Reserve.Band(commands.map { it.bandMin.toUInt()..it.bandMax.toUInt() }, first.idleLevel),
                )
            }
        }
        return out
    }

    private data class Site(val typeKey: String, val key: String, val name: String, val universe: Universe, val startChannel: Int)

    companion object {
        const val CODE_VENUE_HOLD = "VENUE_HOLD"
        const val CODE_NOT_BEHIND = "VENUE_NOT_BEHIND"
    }
}
