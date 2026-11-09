package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.fx.AppliedSource
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import java.util.UUID
import kotlin.math.roundToInt

/** A control's 0..127 across [min]..[max] and back: a tempo window, a percentage, an amount. */
data class Scale7Bit(val min: Double, val max: Double) {
    /** [value] as a control position, rounded and clamped; [min] for a window with no span. */
    fun to7(value: Double): UByte =
        if (max <= min) 0u else (((value - min) / (max - min)) * 127.0).roundToInt().coerceIn(0, 127).toUByte()

    /** A control position as a value in the window. */
    fun of7(value7Bit: UByte): Double = min + (max - min) * (value7Bit.toInt().coerceIn(0, 127) / 127.0)

    companion object {
        /** 0–100 %: the grand master. */
        val PERCENT = Scale7Bit(0.0, 100.0)

        /** 0–1: a stack's FX amount. */
        val UNIT = Scale7Bit(0.0, 1.0)
    }
}

/** What a continuous function reads back as, for one control. */
sealed interface Reading {
    /** One position; another within [tolerance] of it is the same setting (a hue's quantisation, a fader's echo). */
    data class Value(val value7Bit: UByte, val tolerance: Int = 0) : Reading

    /** Its heads disagree: no one position to show. */
    data object Mixed : Reading

    /** Nothing to show: no heads, no lead FX, a record that does not resolve. */
    data object Nothing : Reading
}

/**
 * Something a control's feedback follows. The publisher re-reads a control on each of its signals and
 * on nothing else; [PROGRAMMER] is the poll's, the rest are subscriptions.
 */
enum class Signal {
    /** The programmer store's revision, its Blind, or the cue layer moved. */
    PROGRAMMER,
    SELECTION,
    LOCATE,
    /** The programmer's layer stack or its palette references. */
    LAYERS,
    /** Blackout or the grand master's on/off. */
    SCALER,
    /** The grand master's level. */
    GRAND_MASTER,
    TEMPO,
    STACK_FX,
    STACK_RUN,
    CUE_HOLDS,
    BUSK_PAGE,
    /** Delivered by the device's full resync, which a page change runs. */
    ENCODER_PAGE,
    /** A While held press starting or ending, delivered to its own control part alone. */
    HOLDS,
    /** The running-FX list. */
    FX_LIST,
    FAN,
    /** An FX speed control to be shown again though no FX moved. */
    FX_SPEED_SHOWN,
}

/** What a button function's LED says (P3): a light, or explicitly none. */
sealed interface Led {
    /** Lit while [lit] answers true; re-read on each of [signals]. */
    class Light(val signals: Set<Signal>, val lit: FeedbackPass.() -> Boolean) : Led

    /** The function has no state to show. */
    data object None : Led
}

/** What a continuous function shows on a fader or ring: [read], re-read on each of [signals]. */
class Readout(val signals: Set<Signal>, val read: FeedbackPass.() -> Reading)

/**
 * One bound control part as a function's [Does.led] and [Does.read] see it when the publisher indexes
 * it: where it is, and the record lookups that cost a database read, made once per index rather than
 * once per LED.
 */
interface FeedbackSite {
    val displayKey: String
    val hold: SurfaceDispatch.HoldKey
    val control: ControlDescriptor

    /** The encoder page of the device, as it stands while this index does. */
    val encoderPage: EncoderBankSelection

    fun stackId(stack: UUID): Int?
    fun cueId(cue: UUID): Int?
    fun pad(pad: UUID): PadRef?
    fun pageId(page: UUID): Int?

    /** The heads a [Does.Level] on [on] reads, expanded once and kept until the selection moves. */
    fun levelHeads(on: Subject, attribute: String, axis: ColourAxis?): LevelHeads
}

/** A [Does.Level]'s heads, resolved when read. */
fun interface LevelHeads {
    fun read(): Reading
}

/**
 * What a light or a readout reads, during one pass of the publisher: the live state, fresh for the
 * pass, so ten record LEDs fold the programmer's applied state once.
 */
interface FeedbackPass {
    val selection: List<CueTargetDto>
    fun covers(target: CueTargetDto): Boolean
    fun located(): Set<TargetRef>
    fun applied(): List<AppliedSource>
    fun blackout(): Boolean
    fun grandMasterEnabled(): Boolean
    fun grandMasterLevel(): Double?
    fun tempo(master: UUID?): Double?
    fun stackRunning(stackId: Int): Boolean
    fun stackFxAmount(stackId: Int): Double?
    fun cueLooseHeld(cueId: Int): Boolean
    fun buskPage(): Int?
    fun held(key: SurfaceDispatch.HoldKey): Boolean
    fun encoderPage(displayKey: String): EncoderBankSelection
    fun fanAmount(on: Subject, axis: FanAxis): Int?
    fun fxLeadSpeed(on: Subject): Double?

    /** Whether the programmer is Blind. */
    fun blind(): Boolean
}
