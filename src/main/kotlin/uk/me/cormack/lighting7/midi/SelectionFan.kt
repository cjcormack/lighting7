package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.POSITION_CENTRE_MAX
import uk.me.cormack.lighting7.fx.POSITION_CENTRE_MIN
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.routes.AimNearDto
import uk.me.cormack.lighting7.routes.AimPlacement
import uk.me.cormack.lighting7.routes.aimPlacements
import uk.me.cormack.lighting7.routes.LocalPosition
import uk.me.cormack.lighting7.routes.FanBaseDto
import uk.me.cormack.lighting7.routes.FanAmountDto
import uk.me.cormack.lighting7.routes.FanOutcome
import uk.me.cormack.lighting7.routes.FanRequest
import uk.me.cormack.lighting7.routes.localPosition
import uk.me.cormack.lighting7.routes.fanIntoProgrammer
import uk.me.cormack.lighting7.show.Show
import uk.me.cormack.lighting7.state.BuskRigOrder
import uk.me.cormack.lighting7.state.State
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * The axis a fan encoder moves: [PAN] and [TILT] in centre steps, or the landing points across the
 * stage ([ACROSS], SR↔SL) and up it ([DEPTH], DS↔US) in metres — a stage fan.
 */
@Serializable
enum class FanAxis {
    PAN, TILT, ACROSS, DEPTH;

    val stage: Boolean get() = this == ACROSS || this == DEPTH
}

/** What a fan encoder's ring shows: the feedback publisher's half of [SelectionFan]. */
interface FanRing {
    /** Re-feed the rings on every emission. */
    val shown: StateFlow<SelectionFan.Shown>

    /** The amount a control on [axis] shows. */
    fun amountFor(axis: FanAxis): Int
}

/**
 * The surface's **fan** (venue-visit plan S12): an encoder bound to [BindingTarget.SelectionFan]
 * fans the desk selection's positions about their own, through the same desk resolve as the Spread
 * panel's Fan ([fanIntoProgrammer], owner `SURFACE`).
 *
 * It holds the gesture's **anchor** — where each head started, as the desk answered it, and the rig
 * order read once for the gesture rather than once per detent — and the amount on each axis, so a
 * turn sets the fan from where the heads were when the gesture began and never fans a fan.
 *
 * A turn is read as a **move**: the change from the value the control was last shown, two centre
 * steps a 7-bit step on pan and tilt, a tenth of a metre on a stage axis. A gesture is in pan and
 * tilt or on the stage, and a turn on the other kind starts a new one from where the heads are; a
 * stage gesture also holds the heads' placements and their latest degrees.
 *
 * The anchor goes, and the ring returns to the centre, when the selection changes, the show is
 * rebuilt, or something else has written a fanned head's position since the fan last did — judged
 * at the next turn against Local as output arbitrates it, and that turn writes nothing: the next one
 * starts from where the heads are. A new gesture **picks up at the centre**: until an input lands
 * within [PICKUP_STEPS] of 64 it is ignored, so a detent sent before the device took the centred
 * ring, or a touched motor fader still standing where it was, cannot jump the heads by the distance
 * between the two.
 */
class SelectionFan(private val state: State) : FanRing {
    private val logger = LoggerFactory.getLogger(SelectionFan::class.java)

    /** Pan and tilt in centre steps; across and depth in decimetres. */
    data class Amounts(val pan: Int = 0, val tilt: Int = 0, val across: Int = 0, val depth: Int = 0) {
        fun of(axis: FanAxis) = when (axis) {
            FanAxis.PAN -> pan
            FanAxis.TILT -> tilt
            FanAxis.ACROSS -> across
            FanAxis.DEPTH -> depth
        }

        fun with(axis: FanAxis, amount: Int) = when (axis) {
            FanAxis.PAN -> copy(pan = amount)
            FanAxis.TILT -> copy(tilt = amount)
            FanAxis.ACROSS -> copy(across = amount)
            FanAxis.DEPTH -> copy(depth = amount)
        }
    }

    /** What the rings were last told to show, stamped so an equal value written again still reaches the publisher. */
    data class Shown(val amounts: Amounts, val stamp: Long)

    private class Anchor(
        /** Weak, so a fan last turned on a show that has since been switched away keeps none of it reachable. */
        val show: WeakReference<Show>,
        val targets: List<CueTargetDto>,
        /** Whether the gesture is a stage fan. */
        val stage: Boolean,
        val bases: List<FanBaseDto>,
        /** A stage fan's latest degrees per head, so each stays on the side it is on. */
        val near: List<AimNearDto>,
        /** A stage fan's placements, read once for the gesture as the rig order is. */
        val placements: PlacementsOnce,
        val rigOrder: Lazy<BuskRigOrder?>,
        /** What the fan last wrote, by head key. */
        val written: Map<String, LocalPosition>,
    )

    private var anchor: Anchor? = null
    private var amounts = Amounts()
    private var stamp = 0L
    private val _shown = MutableStateFlow(Shown(Amounts(), 0))

    /** The feedback publisher's signal to re-feed the rings; [amountFor] is what each shows. */
    override val shown: StateFlow<Shown> = _shown.asStateFlow()

    /** The amount a control on [axis] shows: 0 once the selection or the show has moved off the anchor's. */
    @Synchronized
    override fun amountFor(axis: FanAxis): Int = if (anchorCurrent() && anchor?.stage == axis.stage) amounts.of(axis) else 0

    /** One turn of an encoder on [axis], at [value7Bit]. An empty selection drops it. */
    @Synchronized
    fun turn(axis: FanAxis, value7Bit: UByte) {
        val targets = state.deskSelection.state.value.targets
        if (targets.isEmpty()) {
            logger.debug("Surface fan dropped: nothing selected")
            return
        }
        val held = anchor?.takeIf { anchorCurrent() && it.stage == axis.stage }
        if (held != null && !untouched(held)) {
            // Something else moved a fanned head: the ring back to the centre, and nothing written.
            anchor = null
            amounts = Amounts()
            _shown.value = Shown(amounts, ++stamp)
            return
        }
        val current = if (held != null) amounts else Amounts()
        val shown = amountTo7Bit(axis, current.of(axis))
        if (held == null && abs(value7Bit.toInt() - shown) > PICKUP_STEPS) {
            logger.debug("Surface fan: {} at {} waits to pick up at the centre", axis, value7Bit)
            return
        }
        val raw = current.with(axis, clampAmount(axis, current.of(axis) + (value7Bit.toInt() - shown) * stepOf(axis)))
        if (held != null && raw == current) return
        val rigOrder = held?.rigOrder ?: lazy { state.buskRigOrder() }
        val placements = held?.placements ?: PlacementsOnce { aimPlacements(state, state.show.project, it) }
        val fan = if (axis.stage) {
            FanAmountDto(x = raw.across / DECIMETRES_PER_METRE, y = raw.depth / DECIMETRES_PER_METRE)
        } else {
            FanAmountDto(pan = raw.pan, tilt = raw.tilt)
        }
        val request = FanRequest(
            targets = targets,
            property = "position",
            mode = "ABOUT",
            fan = fan,
            base = held?.bases,
            near = held?.near,
        )
        val outcome = fanIntoProgrammer(
            state, request, null, ProgrammerOwner.SURFACE,
            rigOrder = { rigOrder.value },
            placementsOf = placements::of,
        )
        when (outcome) {
            is FanOutcome.Invalid -> logger.debug("Surface fan refused: {}", outcome.message)
            is FanOutcome.Done -> {
                val answer = outcome.response
                anchor = Anchor(
                    show = WeakReference(state.show),
                    targets = targets,
                    stage = axis.stage,
                    bases = held?.bases ?: answer.base,
                    near = (held?.near.orEmpty() + answer.near).associateBy { it.target.key }.values.toList(),
                    placements = placements,
                    rigOrder = rigOrder,
                    written = answer.written.mapNotNull { write ->
                        val position = CueAssignmentResolver.parseAssignmentValue(PropertyCategory.PAN, "position", write.value)
                            as? CueAssignmentResolver.PropertyValue.Position
                        position?.let { write.target.key to LocalPosition(it.pan, it.tilt) }
                    }.toMap(),
                )
                amounts = raw
                _shown.value = Shown(raw, ++stamp)
            }
        }
    }

    private fun anchorCurrent(): Boolean {
        val held = anchor ?: return false
        return held.show.get() === state.show && held.targets == state.deskSelection.state.value.targets
    }

    /** Nothing else has written a head the fan wrote since: Local still holds, axis by axis, what the fan put there. */
    private fun untouched(held: Anchor): Boolean {
        val store = state.show.programmerStore
        val fixtures = state.show.fixtures
        return held.written.all { (key, written) ->
            val head = runCatching { fixtures.untypedGroupableFixture(key) }.getOrNull() ?: return@all false
            localPosition(store, head) == written
        }
    }

    /** Placements read once for the heads first asked about, and again only for a different set. */
    private class PlacementsOnce(private val read: (Collection<String>) -> Map<String, AimPlacement>) {
        private var keys: Set<String>? = null
        private var held: Map<String, AimPlacement> = emptyMap()

        fun of(asked: Collection<String>): Map<String, AimPlacement> {
            val set = asked.toSet()
            if (set != keys) {
                held = read(set)
                keys = set
            }
            return held
        }
    }

    companion object {
        /** An amount on [axis] on the 7-bit scale a ring shows: 0 at 64, a step [stepOf] the amount. */
        fun amountTo7Bit(axis: FanAxis, amount: Int): Int = ((amount - minOf(axis)) / stepOf(axis)).coerceIn(0, 127)

        /** How far from the centre a new gesture's first input may land and still be taken: a fast detent or two. */
        const val PICKUP_STEPS = 4

        private const val DECIMETRES_PER_METRE = 10.0

        /** A stage axis's reach either way, decimetres: the ring's 64 steps each side of its centre. */
        private const val STAGE_AMOUNT_MAX = 63

        private fun stepOf(axis: FanAxis) = if (axis.stage) 1 else 2

        private fun minOf(axis: FanAxis) = if (axis.stage) -(STAGE_AMOUNT_MAX + 1) else POSITION_CENTRE_MIN

        private fun clampAmount(axis: FanAxis, amount: Int) =
            if (axis.stage) amount.coerceIn(-(STAGE_AMOUNT_MAX + 1), STAGE_AMOUNT_MAX) else amount.coerceIn(POSITION_CENTRE_MIN, POSITION_CENTRE_MAX)
    }
}
