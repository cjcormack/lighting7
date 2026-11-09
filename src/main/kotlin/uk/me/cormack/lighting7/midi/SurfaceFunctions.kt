package uk.me.cormack.lighting7.midi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.AppliedExtent
import uk.me.cormack.lighting7.fx.MasterClock
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.fx.SpeedMasterBank
import uk.me.cormack.lighting7.routes.MAX_PROGRAMMER_FADE_SECONDS
import uk.me.cormack.lighting7.state.SubselectMode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong
import kotlin.reflect.KClass

private val logger = LoggerFactory.getLogger("SurfaceFunctions")

private val BUTTON = setOf(ControlKind.BUTTON)
private val CONTINUOUS = setOf(ControlKind.CONTINUOUS)
private val NONE = setOf(SubjectKind.NONE)
private val HEADS = setOf(SubjectKind.SELECTION, SubjectKind.GROUP, SubjectKind.FIXTURE)
private val NAMED_HEADS = setOf(SubjectKind.GROUP, SubjectKind.FIXTURE)
private val SELECTION = setOf(SubjectKind.SELECTION)
private val MASTER_OR_FIRST = setOf(SubjectKind.MASTER, SubjectKind.NONE)
private val ON_OFF = setOf(How.TOGGLE, How.ON, How.OFF)

/** A function that does not take [op], or was handed a subject its spec does not admit. */
private fun Does.wrongKind(op: Op): Acted =
    Acted(ActionOutcome.Refused(ActionOutcome.WRONG_KIND, "$functionId does not take ${op::class.simpleName}"))

private fun Does.wrongSubject(on: Subject): Acted =
    Acted(ActionOutcome.Refused(ActionOutcome.WRONG_SUBJECT, "$functionId does not act on ${on.subjectKind.name.lowercase()}"))

private fun done(block: () -> Unit): Acted {
    block()
    return Acted(ActionOutcome.DONE)
}

private fun answered(outcome: ActionOutcome) = Acted(outcome)

private val functionIds = ConcurrentHashMap<KClass<*>, String>()

/**
 * A surface function (surface-setup plan P3): its [spec] and its [act] on one type, so the service's
 * refusals, dispatch and health read one definition. Serialised by its `@SerialName`, the function's
 * id, beside its own parameters.
 */
@Serializable
sealed interface Does {
    val spec: Spec

    /** Do [op] on [on]. Dispatch has already turned the input and the binding's [How] into [op]. */
    fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted

    /**
     * The LED a button bound to this function shows (P3): a light, or explicitly none. Abstract, so
     * no function can be left out of feedback silently.
     */
    fun led(binding: Binding, site: FeedbackSite): Led

    /** What a fader or ring bound to this function shows; null for a function no continuous input reaches. */
    fun read(binding: Binding, site: FeedbackSite): Readout? = null

    /** Why this function cannot act on [on] with these parameters, beyond the spec's subject kinds; null when it can. */
    fun refusal(on: Subject): String? = null

    /** The function's id, its `@SerialName`. */
    val functionId: String
        get() = functionIds.getOrPut(this::class) {
            this::class.annotations.filterIsInstance<SerialName>().firstOrNull()?.value
                ?: error("${this::class.simpleName} has no @SerialName")
        }

    /** A continuous write to [attribute] of the heads the subject reaches; [axis] on a colour (null is hue). */
    @Serializable
    @SerialName("level")
    data class Level(val attribute: String, val axis: ColourAxis? = null) : Does {
        override val spec get() = Spec(WritesTo.LOCAL, HEADS, CONTINUOUS)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None
        override fun read(binding: Binding, site: FeedbackSite): Readout {
            val heads = site.levelHeads(binding.on, attribute, axis)
            val signals = if (binding.on == Subject.Selection) setOf(Signal.PROGRAMMER, Signal.SELECTION) else setOf(Signal.PROGRAMMER)
            return Readout(signals) { heads.read() }
        }

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted = when (op) {
            is Op.Move -> done { ctx.actions.writeLevel(on, attribute, op.value7Bit, axis) }
            else -> wrongKind(op)
        }
    }

    /**
     * A [Level] on whatever attribute, and colour axis, the encoder page of the device it is on names
     * — the page a strip encoder follows, on any continuous control and subject.
     */
    @Serializable
    @SerialName("pageLevel")
    data object PageLevel : Does {
        override val spec get() = Spec(WritesTo.LOCAL, HEADS, CONTINUOUS)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        // A page change rebuilds the device's index, so the page read here is the one in force.
        override fun read(binding: Binding, site: FeedbackSite): Readout =
            Level(site.encoderPage.attribute, site.encoderPage.colourAxis).read(binding, site)

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted {
            val page = ctx.encoderBankState.selectionFor(ctx.origin.displayKey)
            return Level(page.attribute, page.colourAxis).act(on, op, ctx)
        }
    }

    /**
     * While held, each head's [attribute] at [percent] of its own range, as a fader scales (D10), in
     * the momentary owner; the release takes off exactly the heads the press wrote, whatever the
     * selection is by then.
     */
    @Serializable
    @SerialName("flash")
    data class Flash(val attribute: String, val percent: Int = 100) : Does {
        init {
            require(percent in 0..100) { "A flash is 0–100 %" }
        }

        override val spec get() = Spec(WritesTo.LOCAL, HEADS, BUTTON, setOf(How.WHILE_HELD), How.WHILE_HELD)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.Light(setOf(Signal.HOLDS)) { held(site.hold) }

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted = when (op) {
            Op.On -> Acted(ActionOutcome.DONE, ctx.actions.flashPress(on, attribute, percent))
            Op.Off -> Acted(ActionOutcome.DONE)
            else -> wrongKind(op)
        }
    }

    /** Put a group or fixture into the desk selection, or replace the selection with it. */
    @Serializable
    @SerialName("select")
    data object Select : Does {
        override val spec get() = Spec(
            WritesTo.DESK, NAMED_HEADS, BUTTON,
            setOf(How.TOGGLE_HOLD_REPLACES, How.TOGGLE, How.REPLACE), How.TOGGLE_HOLD_REPLACES,
        )
        override fun led(binding: Binding, site: FeedbackSite): Led {
            val target = binding.on.asTarget() ?: return Led.Light(emptySet()) { false }
            return Led.Light(setOf(Signal.SELECTION)) { covers(target) }
        }

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted {
            val target = on.asTarget() ?: return wrongSubject(on)
            return when (op) {
                Op.Toggle, Op.On -> done { ctx.actions.selectTarget(target, replace = false) }
                Op.Replace -> done { ctx.actions.selectTarget(target, replace = true) }
                else -> wrongKind(op)
            }
        }
    }

    /** Locate the subject's targets, or release them when every one is located. */
    @Serializable
    @SerialName("locate")
    data object Locate : Does {
        override val spec get() = Spec(WritesTo.LOCAL, HEADS, BUTTON, setOf(How.TOGGLE), How.TOGGLE)
        override fun led(binding: Binding, site: FeedbackSite): Led {
            val named = binding.on.asTarget()?.let { TargetRef.ofOrNull(it.type, it.key) }
            if (binding.on != Subject.Selection) {
                return if (named == null) Led.Light(emptySet()) { false } else Led.Light(setOf(Signal.LOCATE)) { named in located() }
            }
            return Led.Light(setOf(Signal.SELECTION, Signal.LOCATE)) {
                selection.isNotEmpty() && located().let { located ->
                    selection.all { TargetRef.ofOrNull(it.type, it.key)?.let { ref -> ref in located } ?: false }
                }
            }
        }

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted = when (op) {
            Op.Toggle, Op.On -> done { ctx.actions.locate(on) }
            else -> wrongKind(op)
        }
    }

    /** Fan the subject's positions on [axis], with an anchor per subject — see [SelectionFan]. */
    @Serializable
    @SerialName("fan")
    data class Fan(val axis: FanAxis) : Does {
        override val spec get() = Spec(WritesTo.LOCAL, SELECTION + SubjectKind.GROUP, CONTINUOUS)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None
        override fun read(binding: Binding, site: FeedbackSite): Readout {
            val on = binding.on
            val signals = if (on == Subject.Selection) setOf(Signal.FAN, Signal.SELECTION) else setOf(Signal.FAN)
            return Readout(signals) {
                fanAmount(on, axis)?.let { Reading.Value(SelectionFan.amountTo7Bit(axis, it).toUByte()) } ?: Reading.Nothing
            }
        }

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted = when (op) {
            is Op.Move -> done { ctx.actions.writeFan(on, axis, op.value7Bit) }
            else -> wrongKind(op)
        }

        override fun refusal(on: Subject): String? =
            if (on is Subject.Fixture) "A fan spreads over several heads, so it takes the selection, not one fixture" else null
    }

    /** The Speed of the Local FX painting the subject's heads — see [SelectionFxSpeed]. */
    @Serializable
    @SerialName("fxSpeed")
    data object FxSpeed : Does {
        override val spec get() = Spec(WritesTo.LOCAL, HEADS, CONTINUOUS)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None
        override fun read(binding: Binding, site: FeedbackSite): Readout {
            val encoder = site.control is EncoderDescriptor
            val on = binding.on
            val signals = if (on == Subject.Selection) setOf(Signal.FX_LIST, Signal.SELECTION, Signal.FX_SPEED_SHOWN)
                else setOf(Signal.FX_LIST, Signal.FX_SPEED_SHOWN)
            return Readout(signals) {
                val lead = fxLeadSpeed(on) ?: return@Readout Reading.Nothing
                if (encoder) Reading.Value(uk.me.cormack.lighting7.fx.FxSpeed.ringPosition(lead).toUByte())
                // A fader's own write lands within a stick and whole-tick rounding of where it was moved.
                else Reading.Value(uk.me.cormack.lighting7.fx.FxSpeed.faderPosition(lead).toUByte(), tolerance = FADER_ECHO_STEPS)
            }
        }

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted = when (op) {
            is Op.Move -> done { ctx.actions.writeFxSpeed(on, op.value7Bit, op.encoder) }
            else -> wrongKind(op)
        }
    }

    /** Clear the whole programmer — its layers, Locate, scenery and tempos too — as its Clear does, over [fade] seconds. */
    @Serializable
    @SerialName("clear")
    data class Clear(val fade: Double = 0.0) : Does {
        init {
            requireFade(fade)
        }

        override val spec get() = Spec(WritesTo.LOCAL, NONE, BUTTON, defaultHow = How.ON)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted =
            if (op == Op.On || op == Op.Toggle) done { ctx.actions.clearProgrammer(fadeMs(fade)) } else wrongKind(op)
    }

    /**
     * The programmer's Blind over [fade] seconds: toggled, or on while held, which lets it go on
     * release only when the press put it on. Lit while Blind.
     */
    @Serializable
    @SerialName("blind")
    data class Blind(val fade: Double = 0.0) : Does {
        init {
            requireFade(fade)
        }

        override val spec get() = Spec(WritesTo.LOCAL, NONE, BUTTON, setOf(How.TOGGLE, How.WHILE_HELD), How.TOGGLE)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.Light(setOf(Signal.PROGRAMMER)) { blind() }

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted {
            val fadeMs = fadeMs(fade)
            return when (op) {
                Op.Toggle -> answered(ctx.actions.setBlind(!ctx.actions.isBlind(), fadeMs))
                Op.On -> {
                    val wasBlind = ctx.actions.isBlind()
                    val outcome = ctx.actions.setBlind(true, fadeMs)
                    // A release undoes what the press did, so a press on an already-blind desk leaves it blind.
                    Acted(outcome, release = { if (!wasBlind) ctx.actions.setBlind(false, fadeMs) })
                }
                Op.Off -> answered(ctx.actions.setBlind(false, fadeMs))
                else -> wrongKind(op)
            }
        }
    }

    /** Release everything the programmer holds on the subject's targets, each as its Release does, over [fade] seconds. */
    @Serializable
    @SerialName("release")
    data class Release(val fade: Double = 0.0) : Does {
        init {
            requireFade(fade)
        }

        override val spec get() = Spec(WritesTo.LOCAL, HEADS, BUTTON, defaultHow = How.ON)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted =
            if (op == Op.On || op == Op.Toggle) answered(ctx.actions.release(on, fadeMs(fade))) else wrongKind(op)
    }

    /**
     * Record the programmer as a new cue at the end of a stack: every touched value, all
     * attributes, with its FX, held scenery and held tempos, as the Record sheet's defaults do. On
     * the serial lane with Undo.
     */
    @Serializable
    @SerialName("record")
    data object Record : Does {
        override val spec get() = stackPress
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted {
            val stack = on.recordUuid(RecordKind.STACK) ?: return wrongSubject(on)
            return if (op == Op.On || op == Op.Toggle) answered(ctx.actions.record(stack)) else wrongKind(op)
        }
    }

    /**
     * Write the programmer back into what Include loaded, as its Update does (Mode A). Refused when
     * nothing is included. On the serial lane with Undo.
     */
    @Serializable
    @SerialName("update")
    data object Update : Does {
        override val spec get() = Spec(WritesTo.PLAYBACK, NONE, BUTTON, defaultHow = How.ON)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted =
            if (op == Op.On || op == Op.Toggle) answered(ctx.actions.update()) else wrongKind(op)
    }

    /** Press [palette] onto the selection. */
    @Serializable
    @SerialName("palette")
    data class Palette(@Serializable(with = UuidStringSerializer::class) val palette: UUID) : Does {
        override val spec get() = Spec(WritesTo.LOCAL, SELECTION, BUTTON, setOf(How.TOGGLE), How.TOGGLE)
        override fun led(binding: Binding, site: FeedbackSite): Led =
            Led.Light(setOf(Signal.LAYERS, Signal.SELECTION)) { coversSelection(palette) }

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted = when (op) {
            Op.Toggle, Op.On -> done { ctx.actions.pressPalette(palette) }
            else -> wrongKind(op)
        }
    }

    /** A Look, whole, on its own fixtures: toggled, or put on or taken off. */
    @Serializable
    @SerialName("look")
    data object Look : Does {
        override val spec get() = Spec(WritesTo.LOCAL, setOf(SubjectKind.LOOK), BUTTON, ON_OFF, How.TOGGLE)
        override fun led(binding: Binding, site: FeedbackSite): Led {
            val look = binding.on.recordUuid(RecordKind.LOOK) ?: return Led.Light(emptySet()) { false }
            return Led.Light(setOf(Signal.LAYERS)) { applied().any { it.source.uuid == look } }
        }

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted {
            val look = on.recordUuid(RecordKind.LOOK) ?: return wrongSubject(on)
            return when (op) {
                Op.Toggle -> answered(ctx.actions.applyLook(look))
                Op.On -> answered(ctx.actions.lookSet(look, true))
                Op.Off -> answered(ctx.actions.lookSet(look, false))
                else -> wrongKind(op)
            }
        }
    }

    /** A cue's loose hold: toggled, or taken or let go. */
    @Serializable
    @SerialName("cue")
    data object Cue : Does {
        override val spec get() = Spec(WritesTo.PLAYBACK, setOf(SubjectKind.CUE), BUTTON, ON_OFF, How.TOGGLE)
        override fun led(binding: Binding, site: FeedbackSite): Led {
            val cueId = binding.on.recordUuid(RecordKind.CUE)?.let(site::cueId) ?: return Led.Light(emptySet()) { false }
            return Led.Light(setOf(Signal.CUE_HOLDS)) { cueLooseHeld(cueId) }
        }

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted {
            val cue = on.recordUuid(RecordKind.CUE) ?: return wrongSubject(on)
            return when (op) {
                Op.Toggle -> answered(ctx.actions.fireCue(cue))
                Op.On -> answered(ctx.actions.looseCueSet(cue, true))
                Op.Off -> answered(ctx.actions.looseCueSet(cue, false))
                else -> wrongKind(op)
            }
        }
    }

    /** A busk pad's press, through its own bank's plan, on the desk selection. */
    @Serializable
    @SerialName("pad")
    data object Pad : Does {
        override val spec get() = Spec(WritesTo.LOCAL, setOf(SubjectKind.PAD), BUTTON, defaultHow = How.ON)
        override fun led(binding: Binding, site: FeedbackSite): Led =
            when (val ref = binding.on.recordUuid(RecordKind.PAD)?.let(site::pad)) {
                // A Look pad is on or off whatever is selected (absolute-looks plan P4); a palette pad is
                // the busk view's selection-scoped fold.
                is PadRef.Layer -> if (ref.isLook) {
                    Led.Light(setOf(Signal.LAYERS)) { applied().any { it.source.uuid == ref.sourceUuid } }
                } else {
                    Led.Light(setOf(Signal.LAYERS, Signal.SELECTION)) { coversSelection(ref.sourceUuid) }
                }
                is PadRef.Cue -> Led.Light(setOf(Signal.CUE_HOLDS)) { cueLooseHeld(ref.cueId) }
                is PadRef.Stack -> Led.Light(setOf(Signal.STACK_RUN)) { stackRunning(ref.stackId) }
                // The pad is gone; health says so and the LED stays dark.
                null -> Led.Light(emptySet()) { false }
            }

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted {
            val pad = on.recordUuid(RecordKind.PAD) ?: return wrongSubject(on)
            return if (op == Op.On || op == Op.Toggle) answered(ctx.actions.pressPad(pad)) else wrongKind(op)
        }
    }

    /** Put a busk pad's record into the desk's hand. */
    @Serializable
    @SerialName("pickUp")
    data object PickUp : Does {
        override val spec get() = Spec(WritesTo.DESK, setOf(SubjectKind.PAD), BUTTON, defaultHow = How.ON)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted {
            val pad = on.recordUuid(RecordKind.PAD) ?: return wrongSubject(on)
            return if (op == Op.On || op == Op.Toggle) done { ctx.actions.pickUpPad(pad) } else wrongKind(op)
        }
    }

    /** Place what the hand holds as a pad on a busk bank, then let go. */
    @Serializable
    @SerialName("place")
    data object Place : Does {
        override val spec get() = Spec(WritesTo.DESK, setOf(SubjectKind.BANK), BUTTON, defaultHow = How.ON)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted {
            val bank = on.recordUuid(RecordKind.BANK) ?: return wrongSubject(on)
            return if (op == Op.On || op == Op.Toggle) done { ctx.actions.handPlaceInBank(bank) } else wrongKind(op)
        }
    }

    /** Let go of what the hand holds. */
    @Serializable
    @SerialName("drop")
    data object Drop : Does {
        override val spec get() = Spec(WritesTo.DESK, NONE, BUTTON, defaultHow = How.ON)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted =
            if (op == Op.On || op == Op.Toggle) done { ctx.actions.handDrop() } else wrongKind(op)
    }

    /** GO on a stack. */
    @Serializable
    @SerialName("go")
    data object Go : Does {
        override val spec get() = stackPress
        override fun led(binding: Binding, site: FeedbackSite): Led {
            val stackId = binding.on.recordUuid(RecordKind.STACK)?.let(site::stackId) ?: return Led.Light(emptySet()) { false }
            return Led.Light(setOf(Signal.STACK_RUN)) { stackRunning(stackId) }
        }

        override fun act(on: Subject, op: Op, ctx: SurfaceContext) = stack(on, op) { ctx.actions.cueStackGo(it) }
    }

    /** GO BACK on a stack. */
    @Serializable
    @SerialName("back")
    data object Back : Does {
        override val spec get() = stackPress
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext) = stack(on, op) { ctx.actions.cueStackBack(it) }
    }

    /** Cancel a stack's running follow. */
    @Serializable
    @SerialName("pause")
    data object Pause : Does {
        override val spec get() = stackPress
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext) = stack(on, op) { ctx.actions.cueStackPause(it) }
    }

    /** Stop a stack over its release time, which drops the desk's arm as every stop does. */
    @Serializable
    @SerialName("stop")
    data object Stop : Does {
        override val spec get() = stackPress
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext) = stack(on, op) { ctx.actions.cueStackStop(it) }
    }

    /** GO TO one entry, starting its stack if stopped; nothing if that entry is already live. */
    @Serializable
    @SerialName("goTo")
    data object GoTo : Does {
        override val spec get() = entryPress
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext) = entry(on, op) { ctx.actions.cueStackGoTo(it) }
    }

    /** Arm one entry as its stack's next GO. */
    @Serializable
    @SerialName("standby")
    data object Standby : Does {
        override val spec get() = entryPress
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext) = entry(on, op) { ctx.actions.cueStackStandby(it) }
    }

    /** A stack's FX amount: 0..127 across 0–100 %, or an amount taken whole, 0–1. */
    @Serializable
    @SerialName("fxAmount")
    data object FxAmount : Does {
        override val spec get() = Spec(WritesTo.PLAYBACK, setOf(SubjectKind.STACK), CONTINUOUS)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None
        override fun read(binding: Binding, site: FeedbackSite): Readout {
            val stackId = binding.on.recordUuid(RecordKind.STACK)?.let(site::stackId)
            return Readout(setOf(Signal.STACK_FX)) {
                stackId?.let(::stackFxAmount)?.let { Reading.Value(Scale7Bit.UNIT.to7(it)) } ?: Reading.Nothing
            }
        }

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted {
            val stack = on.recordUuid(RecordKind.STACK) ?: return wrongSubject(on)
            return when (op) {
                is Op.Move -> done { ctx.actions.writeStackFxAmount(stack, op.value7Bit) }
                is Op.Set -> answered(ctx.actions.setStackFxAmount(stack, op.value))
                else -> wrongKind(op)
            }
        }
    }

    /** Fire one tube of a one-shot fixture: needs the desk's arm, like every fire. Fixture only (D2). */
    @Serializable
    @SerialName("fire")
    data class Fire(val trigger: String) : Does {
        override val spec get() = Spec(WritesTo.PLAYBACK, setOf(SubjectKind.FIXTURE), BUTTON, defaultHow = How.ON)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted {
            val fixture = (on as? Subject.Fixture)?.key ?: return wrongSubject(on)
            return if (op == Op.On || op == Op.Toggle) done { ctx.actions.fireTrigger(fixture, trigger) } else wrongKind(op)
        }
    }

    /** The desk's blackout: toggled, or set on or off. */
    @Serializable
    @SerialName("blackout")
    data object Blackout : Does {
        override val spec get() = Spec(WritesTo.MASTER, NONE, BUTTON, ON_OFF, How.TOGGLE)
        override fun led(binding: Binding, site: FeedbackSite): Led {
            // Lit while blackout is in the state the button sets: on for a toggle or On, off for Off.
            val off = binding.how == How.OFF
            return Led.Light(setOf(Signal.SCALER)) { blackout() != off }
        }

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted = when (op) {
            Op.Toggle -> answered(ActionOutcome.Done(if (ctx.actions.toggleBlackout()) "on" else "off"))
            Op.On -> answered(ctx.actions.setBlackout(true))
            Op.Off -> answered(ctx.actions.setBlackout(false))
            else -> wrongKind(op)
        }
    }

    /**
     * The grand master: a fader's level, 0..127 across 0–100 %; a button that takes it to 0 and back;
     * or, with a [level], a button that sets it there. A level taken whole (OSC) is a percentage.
     */
    @Serializable
    @SerialName("grandMaster")
    data class GrandMaster(val level: Double? = null) : Does {
        init {
            require(level == null || (level.isFinite() && level in 0.0..100.0)) { "A grand master level is 0–100 %" }
        }

        override val spec get() =
            if (level == null) Spec(WritesTo.MASTER, NONE, CONTINUOUS + BUTTON, setOf(How.TOGGLE), How.TOGGLE)
            else Spec(WritesTo.MASTER, NONE, BUTTON, defaultHow = How.ON)

        override fun led(binding: Binding, site: FeedbackSite): Led =
            // Lit while the grand master is engaged: a lit button means the lights are live.
            if (level == null) Led.Light(setOf(Signal.SCALER)) { grandMasterEnabled() } else Led.None

        override fun read(binding: Binding, site: FeedbackSite): Readout? =
            if (level != null) null
            else Readout(setOf(Signal.GRAND_MASTER)) { grandMasterLevel()?.let { Reading.Value(Scale7Bit.PERCENT.to7(it)) } ?: Reading.Nothing }

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted = when (op) {
            is Op.Move -> done { ctx.actions.writeGrandMaster(op.value7Bit) }
            is Op.Set -> answered(ctx.actions.setGrandMasterLevel(op.value))
            Op.Toggle -> done { ctx.actions.toggleGrandMaster() }
            Op.On -> level?.let { answered(ctx.actions.setGrandMasterLevel(it)) } ?: done { ctx.actions.toggleGrandMaster() }
            else -> wrongKind(op)
        }
    }

    /**
     * A speed master's tempo (master 1 with no subject): a fader or encoder across [minBpm]..[maxBpm],
     * or, with a [bpm], a button that sets that tempo. A tempo taken whole (OSC) is BPM.
     */
    @Serializable
    @SerialName("tempo")
    data class Tempo(
        val minBpm: Double = DEFAULT_MIN_BPM,
        val maxBpm: Double = DEFAULT_MAX_BPM,
        val bpm: Double? = null,
    ) : Does {
        init {
            require(minBpm < maxBpm) { "A tempo window's minimum must be below its maximum" }
            require(minBpm >= MasterClock.MIN_BPM && maxBpm <= MasterClock.MAX_BPM) {
                "A tempo window sits within ${MasterClock.MIN_BPM}..${MasterClock.MAX_BPM}"
            }
            require(bpm == null || (bpm.isFinite() && bpm >= MasterClock.MIN_BPM && bpm <= MasterClock.MAX_BPM)) {
                "A tempo is ${MasterClock.MIN_BPM}..${MasterClock.MAX_BPM} BPM"
            }
        }

        override val spec get() =
            if (bpm == null) Spec(WritesTo.TEMPO, MASTER_OR_FIRST, CONTINUOUS)
            else Spec(WritesTo.TEMPO, MASTER_OR_FIRST, BUTTON, defaultHow = How.ON)

        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None
        override fun read(binding: Binding, site: FeedbackSite): Readout? {
            if (bpm != null) return null
            val master = master(binding.on)?.value
            val window = Scale7Bit(minBpm, maxBpm)
            return Readout(setOf(Signal.TEMPO)) { tempo(master)?.let { Reading.Value(window.to7(it)) } ?: Reading.Nothing }
        }

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted {
            val master = master(on) ?: return wrongSubject(on)
            return when (op) {
                is Op.Move -> done { ctx.actions.writeSpeedMasterBpm(master.value, minBpm, maxBpm, op.value7Bit) }
                is Op.Set -> answered(ctx.actions.setSpeedMasterBpm(master.value, op.value))
                Op.On, Op.Toggle -> bpm?.let { answered(ctx.actions.setSpeedMasterBpm(master.value, it)) } ?: wrongKind(op)
                else -> wrongKind(op)
            }
        }

        companion object {
            const val DEFAULT_MIN_BPM = 60.0
            const val DEFAULT_MAX_BPM = 180.0
        }
    }

    /** Tap a speed master's tempo (master 1 with no subject). */
    @Serializable
    @SerialName("tap")
    data object Tap : Does {
        override val spec get() = Spec(WritesTo.TEMPO, MASTER_OR_FIRST, BUTTON, defaultHow = How.ON)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted {
            val master = master(on) ?: return wrongSubject(on)
            return if (op == Op.On || op == Op.Toggle) answered(ctx.actions.tapSpeedMaster(master.value)) else wrongKind(op)
        }
    }

    /** Halve or double a speed master's tempo (master 1 with no subject). */
    @Serializable
    @SerialName("tempoScale")
    data class TempoScale(val factor: Double) : Does {
        init {
            require(factor in SpeedMasterBank.SCALE_FACTORS) { "A tempo scale is 0.5 or 2" }
        }

        override val spec get() = Spec(WritesTo.TEMPO, MASTER_OR_FIRST, BUTTON, defaultHow = How.ON)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted {
            val master = master(on) ?: return wrongSubject(on)
            return if (op == Op.On || op == Op.Toggle) done { ctx.actions.scaleSpeedMaster(master.value, factor) } else wrongKind(op)
        }
    }

    /** Switch the layer of the device the button is on. */
    @Serializable
    @SerialName("layer")
    data class Layer(val layer: String) : Does {
        override val spec get() = Spec(WritesTo.DESK, NONE, BUTTON, defaultHow = How.ON)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted =
            if (op == Op.On || op == Op.Toggle) done { ctx.bankState.setBank(ctx.origin.displayKey, layer) } else wrongKind(op)
    }

    /** Point the strip encoders of the device the button is on at [attribute], on [axis] for a colour. */
    @Serializable
    @SerialName("encoderPage")
    data class EncoderPage(val attribute: String, val axis: ColourAxis? = null) : Does {
        override val spec get() = Spec(WritesTo.DESK, NONE, BUTTON, defaultHow = How.ON)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.Light(setOf(Signal.ENCODER_PAGE)) {
            // Through the effective axis: (rgbColour, null) is the same page as (rgbColour, HUE).
            encoderPage(site.displayKey).let { it.attribute == attribute && it.colourAxis.effective == axis.effective }
        }

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted =
            if (op == Op.On || op == Op.Toggle) {
                done { ctx.encoderBankState.set(ctx.origin.displayKey, EncoderBankSelection(attribute, axis)) }
            } else {
                wrongKind(op)
            }
    }

    /** The undo key, the redo key, or the programmer's own undo, as the surface's source (undo plan D1, D2). */
    @Serializable
    @SerialName("undo")
    data class Undo(val kind: UndoKind = UndoKind.UNDO) : Does {
        override val spec get() = Spec(WritesTo.DESK, NONE, BUTTON, defaultHow = How.ON)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted {
            if (op != Op.On && op != Op.Toggle) return wrongKind(op)
            return done {
                when (kind) {
                    UndoKind.UNDO -> ctx.actions.historyMove(ctx.origin.source, undo = true)
                    UndoKind.REDO -> ctx.actions.historyMove(ctx.origin.source, undo = false)
                    UndoKind.PROGRAMMER -> ctx.actions.historyMove(ctx.origin.source, undo = true, programmerOnly = true)
                }
            }
        }
    }

    /** The busk page the desk shows: a page by its uuid, or one [step] along, wrapping. */
    @Serializable
    @SerialName("buskPage")
    data class BuskPage(val step: Int? = null) : Does {
        override val spec get() = Spec(WritesTo.DESK, setOf(SubjectKind.NONE, SubjectKind.PAGE), BUTTON, defaultHow = How.ON)
        override fun led(binding: Binding, site: FeedbackSite): Led {
            val page = binding.on.recordUuid(RecordKind.PAGE) ?: return Led.None
            val pageId = site.pageId(page)
            return Led.Light(setOf(Signal.BUSK_PAGE)) { pageId != null && buskPage() == pageId }
        }

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted {
            if (op != Op.On && op != Op.Toggle) return wrongKind(op)
            on.recordUuid(RecordKind.PAGE)?.let { return answered(ctx.actions.buskPageSet(it)) }
            if (on !is Subject.None) return wrongSubject(on)
            return done { ctx.actions.buskPageStep(step ?: 1) }
        }

        override fun refusal(on: Subject): String? = when {
            on is Subject.Record && step != null -> "A busk page binding shows one page or steps along, not both"
            on is Subject.None && step == null -> "A busk page binding with no page steps along, so it needs a step"
            else -> null
        }
    }

    /** Set the busk focus of every connected window named [window]. */
    @Serializable
    @SerialName("windowFocus")
    data class WindowFocus(val window: String, val focus: String) : Does {
        init {
            require(focus in FOCUSES) { "A busk focus is one of $FOCUSES" }
        }

        override val spec get() = Spec(WritesTo.DESK, NONE, BUTTON, defaultHow = How.ON)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted =
            if (op == Op.On || op == Op.Toggle) done { ctx.actions.buskFocusSet(window, focus) } else wrongKind(op)

        companion object {
            /** The three focus values the busk view has (busk-further plan §3.4). */
            val FOCUSES: Set<String> = setOf("split", "pads", "rig")
        }
    }

    /** Fold or open the busk side sheet of every connected window named [window]. */
    @Serializable
    @SerialName("windowSheet")
    data class WindowSheet(val window: String) : Does {
        override val spec get() = Spec(WritesTo.DESK, NONE, BUTTON, defaultHow = How.ON)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted =
            if (op == Op.On || op == Op.Toggle) done { ctx.actions.buskSheetToggle(window) } else wrongKind(op)
    }

    /** Empty the desk selection, or rewrite its targets by a sub-selection move. */
    @Serializable
    @SerialName("selection")
    data class SelectionStep(val step: SelectionMove) : Does {
        override val spec get() = Spec(WritesTo.DESK, NONE, BUTTON, defaultHow = How.ON)
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted {
            if (op != Op.On && op != Op.Toggle) return wrongKind(op)
            return done {
                when (val mode = step.subselect) {
                    null -> ctx.actions.clearSelection()
                    else -> ctx.actions.subselect(mode)
                }
            }
        }
    }

    /**
     * One group or fixture on a whole channel strip, at a strip id rather than a control id. Never
     * dispatched: the service derives it to what each of the strip's controls behaves as
     * ([deriveStripBinding]). Jobs replace it in session 5.
     */
    @Serializable
    @SerialName("strip")
    data object Strip : Does {
        override val spec get() = Spec(WritesTo.LOCAL, NAMED_HEADS, emptySet())
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted {
            logger.warn("A strip binding reached dispatch; the service should have derived it")
            return wrongKind(op)
        }
    }

    /**
     * A stored payload this build cannot read, kept verbatim ([rawPayload]) so a round trip through
     * an older desk loses nothing. Never written by a client; health marks it dead.
     */
    @Serializable
    @SerialName("unknown")
    data class Unknown(val storedType: String, val rawPayload: String) : Does {
        override val spec get() = Spec(WritesTo.DESK, emptySet(), emptySet())
        override val functionId: String get() = storedType
        override fun led(binding: Binding, site: FeedbackSite): Led = Led.None

        override fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted = wrongKind(op)
    }
}

/** What a function names on the heads it reaches, for the refusals and the health that judge the name. */
sealed interface Named {
    data class Attribute(val attribute: String, val axis: ColourAxis? = null) : Named
    data class Trigger(val trigger: String) : Named
}

/** Exhaustive (P3), so a new function has to say whether it names an attribute or a trigger. */
val Does.named: Named?
    get() = when (this) {
        is Does.Level -> Named.Attribute(attribute, axis)
        // Its attribute is the device's encoder page, which no binding names.
        Does.PageLevel -> null
        is Does.Flash -> Named.Attribute(attribute)
        is Does.EncoderPage -> Named.Attribute(attribute, axis)
        is Does.Fire -> Named.Trigger(trigger)
        Does.Select, Does.Locate, is Does.Fan, Does.FxSpeed, is Does.Palette, Does.Look, Does.Cue, Does.Pad,
        Does.PickUp, Does.Place, Does.Drop, Does.Go, Does.Back, Does.Pause, Does.Stop, Does.GoTo,
        Does.Standby, Does.FxAmount, Does.Blackout, is Does.GrandMaster, is Does.Tempo, Does.Tap,
        is Does.TempoScale, is Does.Layer, is Does.Undo, is Does.BuskPage, is Does.WindowFocus,
        is Does.WindowSheet, is Does.SelectionStep, is Does.Clear, is Does.Blind, is Does.Release, Does.Record,
        Does.Update, Does.Strip, is Does.Unknown,
            -> null
    }

/** Which key an [Does.Undo] binding is. */
@Serializable
enum class UndoKind { UNDO, REDO, PROGRAMMER }

/** What a [Does.SelectionStep] does: empty the selection, or one of the sub-selection's moves. */
@Serializable
enum class SelectionMove(val subselect: SubselectMode?) {
    CLEAR(null),
    NEXT(SubselectMode.NEXT),
    PREV(SubselectMode.PREV),
    ALL(SubselectMode.ALL),
    ODD(SubselectMode.ODD),
    EVEN(SubselectMode.EVEN),
    FIRST_HALF(SubselectMode.FIRST_HALF),
    SECOND_HALF(SubselectMode.SECOND_HALF),
    INVERT(SubselectMode.INVERT),
    MASTERS(SubselectMode.MASTERS),
    ;

    companion object {
        fun of(mode: SubselectMode): SelectionMove = valueOf(mode.name)
    }
}

/** A record LED's palette fold: lit while [palette]'s layer covers every selected target whole — the busk pad's ring. */
private fun FeedbackPass.coversSelection(palette: UUID): Boolean {
    if (selection.isEmpty()) return false
    val entry = applied().firstOrNull { it.source.uuid == palette } ?: return false
    return selection.all { target ->
        entry.targets.any { it.target.type == target.type && it.target.key == target.key && it.extent == AppliedExtent.ALL }
    }
}

/**
 * How far an FX speed fader's readback may land from where it was moved and still be its own write:
 * 0.15 of an octave, past a stick's reach from a step and whole-tick rounding at ×4, on a travel of four
 * octaves over 127 steps.
 */
private const val FADER_ECHO_STEPS = 5

private fun requireFade(fade: Double) =
    require(fade.isFinite() && fade in 0.0..MAX_PROGRAMMER_FADE_SECONDS) { "A fade is 0–${MAX_PROGRAMMER_FADE_SECONDS.toInt()} s" }

private fun fadeMs(fade: Double): Long = (fade * 1000).roundToLong()

private val stackPress = Spec(WritesTo.PLAYBACK, setOf(SubjectKind.STACK), BUTTON, defaultHow = How.ON)
private val entryPress = Spec(WritesTo.PLAYBACK, setOf(SubjectKind.ENTRY), BUTTON, defaultHow = How.ON)

private fun Does.stack(on: Subject, op: Op, act: (UUID) -> ActionOutcome): Acted {
    val stack = on.recordUuid(RecordKind.STACK) ?: return wrongSubject(on)
    return if (op == Op.On || op == Op.Toggle) answered(act(stack)) else wrongKind(op)
}

private fun Does.entry(on: Subject, op: Op, act: (UUID) -> ActionOutcome): Acted {
    val entry = on.recordUuid(RecordKind.ENTRY) ?: return wrongSubject(on)
    return if (op == Op.On || op == Op.Toggle) answered(act(entry)) else wrongKind(op)
}

/** A tempo function's master: the record's uuid, or null-in-a-box for master 1; null for any other subject. */
private fun master(on: Subject): Box<UUID?>? = when (on) {
    Subject.None -> Box(null)
    is Subject.Record -> if (on.kind == RecordKind.MASTER) Box(on.uuid) else null
    else -> null
}

private data class Box<T>(val value: T)
