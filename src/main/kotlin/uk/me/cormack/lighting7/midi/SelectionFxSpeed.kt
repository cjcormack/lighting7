package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.FxPhase
import uk.me.cormack.lighting7.fx.FxSpeed
import uk.me.cormack.lighting7.fx.TargetCoverage
import uk.me.cormack.lighting7.fx.TimingSource
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.routes.FxUpdateOutcome
import uk.me.cormack.lighting7.routes.UpdateFxRequest
import uk.me.cormack.lighting7.routes.applyFxUpdate
import uk.me.cormack.lighting7.show.FixturesChangeListener
import uk.me.cormack.lighting7.show.Show
import uk.me.cormack.lighting7.state.BuskRigOrder
import uk.me.cormack.lighting7.state.State
import java.lang.ref.WeakReference
import kotlin.math.abs

/** What an *FX speed* control's ring shows: the feedback publisher's half of [SelectionFxSpeed]. */
interface FxSpeedRing {
    /** Stamped when a ring should be re-fed though no FX moved — a turn refused as stale. */
    val shown: StateFlow<Long>

    /** The lead FX's Speed, or null when no Local FX paints the selection. */
    fun leadSpeed(): Double?
}

/**
 * The surface's **FX speed** (fx-speed plan D13): a control bound to [BindingTarget.FxSpeed] moves the
 * Speed of every Local FX ([FxInstance.isLocalFx]) that paints a head of the desk selection, each
 * whole — [applyFxUpdate] with no targets, so nothing splits and an FX partly inside the selection
 * changes on every head it paints. A programmer layer's, a cue's and a Look's FX are not Local and are
 * left alone, and so is an FX that runs at its own pace (`ownPace`), which has no Speed.
 *
 * An **encoder** moves each a quarter of an octave a detent, each on its own ([FxSpeed.stepSpeed],
 * sticking at its own powers of two), read as the change from where its ring shows the **lead FX** —
 * the Local FX painting the first selected head in rig order, the lowest Local rank where two do.
 * A **fader** sets every one to its position, ×¼ to ×4 ([FxSpeed.faderSpeed]); soft takeover picks it
 * up at the lead's Speed, which the publisher feeds it. Each writes a cycle of whole ticks (D1), and
 * the engine keeps each FX's elapsed cycles across the change (D6).
 */
class SelectionFxSpeed(private val state: State) : FxSpeedRing {
    private val logger = LoggerFactory.getLogger(SelectionFxSpeed::class.java)

    private var stamp = 0L
    private val _shown = MutableStateFlow(0L)
    override val shown: StateFlow<Long> = _shown.asStateFlow()

    /**
     * The rig order read once per selection on a show, and again after a rig or patch change, so a
     * ring re-fed on every FX change costs no database read.
     */
    private class RigOrderOnce(val show: WeakReference<Show>, val targets: List<CueTargetDto>, val order: BuskRigOrder)

    @Volatile
    private var rigOrder: RigOrderOnce? = null

    /** The show whose fixtures [rigListener] is registered on. */
    private var listening: WeakReference<Show>? = null

    private val rigListener = object : FixturesChangeListener {
        override fun buskRigChanged() { rigOrder = null }
        override fun patchListChanged() { rigOrder = null }
        override fun fixturesChanged() { rigOrder = null }
    }

    @Synchronized
    override fun leadSpeed(): Double? {
        val targets = state.deskSelection.state.value.targets
        if (targets.isEmpty()) return null
        val painting = painting(targets)
        return lead(targets, painting)?.let { speedOf(it) }
    }

    /** One move of a control at [value7Bit]; [encoder] says whether it is an encoder's turn or a fader's. */
    @Synchronized
    fun turn(value7Bit: UByte, encoder: Boolean) {
        val targets = state.deskSelection.state.value.targets
        if (targets.isEmpty()) {
            logger.debug("Surface FX speed dropped: nothing selected")
            return
        }
        val painting = painting(targets)
        if (painting.isEmpty()) {
            logger.debug("Surface FX speed dropped: no Local FX paints the selection")
            return
        }
        val speeds: Map<FxInstance, Double> = if (encoder) {
            val lead = lead(targets, painting) ?: return
            val delta = value7Bit.toInt() - FxSpeed.ringPosition(speedOf(lead))
            if (delta == 0) return
            if (abs(delta) > ENCODER_JUMP) {
                // The device holds a position the desk did not show it: show it again, move nothing.
                _shown.value = ++stamp
                return
            }
            painting.keys.associateWith { FxSpeed.stepSpeed(speedOf(it), delta) }
        } else {
            val speed = FxSpeed.faderSpeed(value7Bit.toInt())
            painting.keys.associateWith { speed }
        }
        for ((fx, speed) in speeds) {
            val wallClock = isWallClock(fx)
            val cycle = FxSpeed.cycleOf(speed, wallClock)
            if (sameCycle(fx.timing.cycle, cycle, wallClock)) continue
            when (val outcome = applyFxUpdate(state, fx.id, UpdateFxRequest(cycle = cycle))) {
                is FxUpdateOutcome.Refused -> logger.debug("Surface FX speed refused on {}: {}", fx.id, outcome.message)
                is FxUpdateOutcome.Conflict -> logger.debug("Surface FX speed refused on {}: {}", fx.id, outcome.message)
                else -> {}
            }
        }
        // A lead that did not land where the device counted — held at ×32 or ×1/512, rounded to whole
        // ticks, or refused — leaves the device off its ring; re-show it, or the next detent counts double.
        if (encoder && leadSpeed()?.let(FxSpeed::ringPosition) != value7Bit.toInt()) _shown.value = ++stamp
    }

    /** Every Local FX with a Speed that paints a selected head, with the keys it paints. */
    private fun painting(targets: List<CueTargetDto>): Map<FxInstance, Set<String>> {
        val show = state.show
        val engine = show.fxEngine
        val coverage = coverage(show)
        val selected = coverage.expand(targets).toSet()
        val selectedParents = selected.mapNotNullTo(HashSet()) { coverage.parentOf(it)?.key }
        val out = LinkedHashMap<FxInstance, Set<String>>()
        for (fx in engine.getActiveFx()) {
            if (!fx.isLocalFx || ownPace(show, fx)) continue
            val keys = engine.paintedHeads(fx).mapTo(LinkedHashSet()) { it.key }
            val paints = keys.any { key -> key in selectedParents || coverage.covers(selected, fixture(key)) }
            if (paints) out[fx] = keys
        }
        return out
    }

    /** The Local FX painting the first selected head in rig order, the lowest Local rank where two do. */
    private fun lead(targets: List<CueTargetDto>, painting: Map<FxInstance, Set<String>>): FxInstance? {
        if (painting.isEmpty()) return null
        val show = state.show
        val coverage = coverage(show)
        val heads = coverage.expand(targets).distinct()
        val ordered = rigOrderFor(show, targets)?.sort(heads) ?: heads
        val byRank = painting.entries.sortedWith(compareBy({ it.key.priority }, { it.key.id }))
        for (head in ordered) {
            val parent = coverage.parentOf(head)?.key
            byRank.firstOrNull { (_, keys) ->
                head.key in keys || (parent != null && parent in keys) ||
                    keys.any { coverage.parentOf(fixture(it))?.key == head.key }
            }?.let { return it.key }
        }
        return byRank.first().key
    }

    private fun rigOrderFor(show: Show, targets: List<CueTargetDto>): BuskRigOrder? {
        val held = rigOrder
        if (held != null && held.show.get() === show && held.targets == targets) return held.order
        if (listening?.get() !== show) {
            listening?.get()?.fixtures?.unregisterListener(rigListener)
            show.fixtures.registerListener(rigListener)
            listening = WeakReference(show)
        }
        // A failed read (the show mid-load) is not kept: the next asks again.
        val order = state.buskRigOrder() ?: return null
        rigOrder = RigOrderOnce(WeakReference(show), targets, order)
        return order
    }

    private fun coverage(show: Show) = TargetCoverage { show.fixtures }

    private fun ownPace(show: Show, fx: FxInstance): Boolean =
        show.fxRegistry.getRegistration(fx.fxTypeId)?.ownPace == true

    private fun isWallClock(fx: FxInstance) = fx.registrationTimingSource == TimingSource.WALL_CLOCK

    private fun speedOf(fx: FxInstance) = FxSpeed.runningSpeed(fx.timing.cycle, isWallClock(fx))

    private fun sameCycle(current: Double, next: Double, wallClock: Boolean): Boolean =
        if (wallClock) abs(current - next) < 1e-9 else FxPhase.beatCycleTicks(current) == FxPhase.beatCycleTicks(next)

    private fun fixture(key: String) = CueTargetDto(TargetRef.Fixture.TYPE, key)

    companion object {
        /**
         * The furthest a turn may land from where its ring was shown and still be read as detents: a
         * fast turn's few. Past it the device holds a stale position (a ring not yet fed), so the turn
         * moves nothing and the ring is shown again.
         */
        const val ENCODER_JUMP = 8
    }
}
