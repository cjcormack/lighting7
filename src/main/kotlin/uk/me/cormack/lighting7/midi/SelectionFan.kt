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
import uk.me.cormack.lighting7.routes.LocalPosition
import uk.me.cormack.lighting7.routes.SpreadBaseDto
import uk.me.cormack.lighting7.routes.SpreadFanDto
import uk.me.cormack.lighting7.routes.SpreadOutcome
import uk.me.cormack.lighting7.routes.SpreadRequest
import uk.me.cormack.lighting7.routes.localPosition
import uk.me.cormack.lighting7.routes.spreadIntoProgrammer
import uk.me.cormack.lighting7.show.Show
import uk.me.cormack.lighting7.state.BuskRigOrder
import uk.me.cormack.lighting7.state.State
import java.lang.ref.WeakReference
import kotlin.math.abs

/** The axis a fan encoder moves. */
@Serializable
enum class FanAxis { PAN, TILT }

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
 * panel's Fan ([spreadIntoProgrammer], owner `SURFACE`).
 *
 * It holds the gesture's **anchor** — where each head started, as the desk answered it, and the rig
 * order read once for the gesture rather than once per detent — and the amount on each axis, so a
 * turn sets the fan from where the heads were when the gesture began and never fans a fan.
 *
 * A turn is read as a **move**: the change from the value the control was last shown, two centre
 * steps a 7-bit step. The anchor goes, and the ring returns to the centre, when the selection
 * changes, the show is rebuilt, or something else has written a fanned head's position since the
 * fan last did — judged at the next turn against Local as output arbitrates it, and that turn writes
 * nothing: the next one starts from where the heads are. A new gesture **picks up at the centre**:
 * until an input lands within [PICKUP_STEPS] of 64 it is ignored, so a detent sent before the
 * device took the centred ring, or a touched motor fader still standing where it was, cannot jump
 * the heads by the distance between the two.
 */
class SelectionFan(private val state: State) : FanRing {
    private val logger = LoggerFactory.getLogger(SelectionFan::class.java)

    data class Amounts(val pan: Int = 0, val tilt: Int = 0) {
        fun of(axis: FanAxis) = when (axis) {
            FanAxis.PAN -> pan
            FanAxis.TILT -> tilt
        }

        fun with(axis: FanAxis, amount: Int) = when (axis) {
            FanAxis.PAN -> copy(pan = amount)
            FanAxis.TILT -> copy(tilt = amount)
        }
    }

    /** What the rings were last told to show, stamped so an equal value written again still reaches the publisher. */
    data class Shown(val amounts: Amounts, val stamp: Long)

    private class Anchor(
        /** Weak, so a fan last turned on a show that has since been switched away keeps none of it reachable. */
        val show: WeakReference<Show>,
        val targets: List<CueTargetDto>,
        val bases: List<SpreadBaseDto>,
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
    override fun amountFor(axis: FanAxis): Int = if (anchorCurrent()) amounts.of(axis) else 0

    /** One turn of an encoder on [axis], at [value7Bit]. An empty selection drops it. */
    @Synchronized
    fun turn(axis: FanAxis, value7Bit: UByte) {
        val targets = state.deskSelection.state.value.targets
        if (targets.isEmpty()) {
            logger.debug("Surface fan dropped: nothing selected")
            return
        }
        val held = anchor?.takeIf { anchorCurrent() }
        if (held != null && !untouched(held)) {
            // Something else moved a fanned head: the ring back to the centre, and nothing written.
            anchor = null
            amounts = Amounts()
            _shown.value = Shown(amounts, ++stamp)
            return
        }
        val current = if (held != null) amounts else Amounts()
        val shown = amountTo7Bit(current.of(axis))
        if (held == null && abs(value7Bit.toInt() - shown) > PICKUP_STEPS) {
            logger.debug("Surface fan: {} at {} waits to pick up at the centre", axis, value7Bit)
            return
        }
        val raw = current.with(axis, clampAmount(current.of(axis) + (value7Bit.toInt() - shown) * 2))
        if (held != null && raw == current) return
        val rigOrder = held?.rigOrder ?: lazy { state.buskRigOrder() }
        val request = SpreadRequest(
            targets = targets,
            property = "position",
            mode = "FAN",
            fan = SpreadFanDto(pan = raw.pan, tilt = raw.tilt),
            base = held?.bases,
        )
        when (val outcome = spreadIntoProgrammer(state, request, null, ProgrammerOwner.SURFACE) { rigOrder.value }) {
            is SpreadOutcome.Invalid -> logger.debug("Surface fan refused: {}", outcome.message)
            is SpreadOutcome.Done -> {
                val answer = outcome.response
                anchor = Anchor(
                    show = WeakReference(state.show),
                    targets = targets,
                    bases = held?.bases ?: answer.base,
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

    companion object {
        /** An amount on the 7-bit scale a ring shows: 0 at 64, two centre steps a step. */
        fun amountTo7Bit(amount: Int): Int = ((amount - POSITION_CENTRE_MIN) / 2).coerceIn(0, 127)

        /** How far from the centre a new gesture's first input may land and still be taken: a fast detent or two. */
        const val PICKUP_STEPS = 4

        private fun clampAmount(amount: Int) = amount.coerceIn(POSITION_CENTRE_MIN, POSITION_CENTRE_MAX)
    }
}
