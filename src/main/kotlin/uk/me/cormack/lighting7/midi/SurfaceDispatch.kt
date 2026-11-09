package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.history.HistorySource
import java.util.concurrent.ConcurrentHashMap

/**
 * One [Binding] into its function: the dispatch a MIDI surface's router and the OSC collector share
 * (OSC plan D2), so a button and an OSC message that name one binding cannot do different things.
 * Every input answers an [ActionOutcome].
 *
 * This is the one place a [How] becomes an [Op]: Toggle, On and Off press as themselves; While held
 * presses on and, on release, undoes exactly what the press wrote ([Acted.release]), else sends off;
 * Toggle-hold-replaces toggles and, still held after [selectHoldMs], replaces. Holds are keyed by the
 * control part ([HoldKey]), so two devices of one type each hold their own.
 *
 * Callers run each input inside [guarded]: the actions read `state.show`, which throws while a
 * project switch is in flight, and a throw out of an input collector would end the collector.
 */
class SurfaceDispatch(
    private val actions: SurfaceActions,
    private val bankState: ActiveBankState,
    private val encoderBankState: EncoderBankState,
    val holds: SurfaceHolds = SurfaceHolds(),
    /** Where a Toggle-hold-replaces press waits out its hold; null arms no hold. */
    private val scope: () -> CoroutineScope? = { null },
    private val selectHoldMs: Long = SELECT_HOLD_MS,
) {
    /** One control part on one attached device. */
    data class HoldKey(val displayKey: String, val controlId: String, val part: ControlPart)

    /**
     * Where an input came from: the device profile, the history source an undo key acts as, and the
     * control part a hold is kept on (none for OSC), whose device a layer or encoder page press
     * switches ([displayKey]).
     */
    data class Origin(
        val deviceTypeKey: String,
        val source: HistorySource,
        val hold: HoldKey? = null,
    ) {
        /** The device the input came from; a sender with no device (OSC) is its device type. */
        val displayKey: String get() = hold?.displayKey ?: deviceTypeKey
    }

    /** One input from a control part, or a value an OSC message carries. */
    sealed interface Input {
        data class Move(val value7Bit: UByte, val encoder: Boolean = false) : Input
        data class Set(val value: Double) : Input
        data object Press : Input
        data object Release : Input
    }

    private val selectHolds = ConcurrentHashMap<HoldKey, Job>()

    fun dispatch(binding: Binding, input: Input, origin: Origin): ActionOutcome {
        val ctx = SurfaceContext(actions, bankState, encoderBankState, origin)
        return when (input) {
            is Input.Move -> binding.does.act(binding.on, Op.Move(input.value7Bit, input.encoder), ctx).outcome
            is Input.Set -> binding.does.act(binding.on, Op.Set(input.value), ctx).outcome
            Input.Press -> press(binding, ctx)
            Input.Release -> {
                origin.hold?.let(::releasePart)
                ActionOutcome.DONE
            }
        }
    }

    private fun press(binding: Binding, ctx: SurfaceContext): ActionOutcome {
        val key = ctx.origin.hold
        return when (binding.effectiveHow) {
            How.TOGGLE -> act(binding, Op.Toggle, ctx)
            How.ON, null -> act(binding, Op.On, ctx)
            How.OFF -> act(binding, Op.Off, ctx)
            How.REPLACE -> act(binding, Op.Replace, ctx)
            How.WHILE_HELD -> {
                if (key != null && holds.isHeld(key)) return ActionOutcome.Done("held")
                val acted = binding.does.act(binding.on, Op.On, ctx)
                if (key != null) {
                    val release: () -> Unit = acted.release ?: { binding.does.act(binding.on, Op.Off, ctx) }
                    holds.hold(key, release)
                }
                acted.outcome
            }
            How.TOGGLE_HOLD_REPLACES -> {
                val outcome = act(binding, Op.Toggle, ctx)
                if (key != null) armReplace(key, binding, ctx)
                outcome
            }
        }
    }

    /**
     * A control part let go: cancel its pending replace and undo its hold. Needs no binding — a
     * release always finds its own, even if the binding changed or went dead while it was down.
     */
    fun releasePart(key: HoldKey) {
        selectHolds.remove(key)?.cancel()
        holds.release(key)
    }

    private fun act(binding: Binding, op: Op, ctx: SurfaceContext): ActionOutcome = binding.does.act(binding.on, op, ctx).outcome

    private fun armReplace(key: HoldKey, binding: Binding, ctx: SurfaceContext) {
        val scope = scope() ?: return
        val job = scope.launch {
            delay(selectHoldMs)
            selectHolds.remove(key)
            guarded("Surface hold on ${key.displayKey}/${key.controlId}") { act(binding, Op.Replace, ctx) }
        }
        selectHolds.put(key, job)?.cancel()
    }

    /** Drop every pending replace and let go of every hold on [displayKey]: the device went away. */
    fun releaseDevice(displayKey: String) {
        selectHolds.keys.filter { it.displayKey == displayKey }.forEach { selectHolds.remove(it)?.cancel() }
        holds.releaseDevice(displayKey)
    }

    /** Drop every pending replace. */
    fun cancelPending() {
        selectHolds.values.forEach { it.cancel() }
        selectHolds.clear()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SurfaceDispatch::class.java)

        /**
         * How long a Toggle-hold-replaces button must be held before it *also* replaces the
         * selection. The toggle still happens on press, so the LED is immediate; keeping the button
         * down then narrows the selection to that one target, which is how every fader wing surveyed
         * in `docs/plans/completed/midi-surface-plan.md` §1 behaves.
         */
        const val SELECT_HOLD_MS = 500L

        /**
         * Run one input, turning any throw but a cancellation into [ActionOutcome.Failed] so the
         * collector it runs in carries on (`FU-MIDI-SHOW-GUARD`).
         */
        inline fun guarded(what: String, block: () -> ActionOutcome): ActionOutcome = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(what, e)
        }

        @PublishedApi
        internal fun failed(what: String, e: Exception): ActionOutcome {
            logger.warn("{} failed: {}", what, e.toString())
            return ActionOutcome.Failed(e.message ?: e::class.simpleName ?: "error")
        }
    }
}

/**
 * What every While held press holds now, keyed by control part, with the release that undoes it.
 * The feedback publisher lights a held flash's LED from [isHeld] and [changes], so each device lights
 * its own (D16).
 */
class SurfaceHolds {
    private val held = ConcurrentHashMap<SurfaceDispatch.HoldKey, () -> Unit>()

    /** A control part's hold starting or ending. */
    data class HoldChange(val key: SurfaceDispatch.HoldKey, val held: Boolean)

    private val _changes = MutableSharedFlow<HoldChange>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes: SharedFlow<HoldChange> = _changes.asSharedFlow()

    fun isHeld(key: SurfaceDispatch.HoldKey): Boolean = held.containsKey(key)

    internal fun hold(key: SurfaceDispatch.HoldKey, release: () -> Unit) {
        held[key] = release
        _changes.tryEmit(HoldChange(key, true))
    }

    /** Let go of [key]'s hold, undoing what its press wrote. */
    fun release(key: SurfaceDispatch.HoldKey) {
        val gone = held.remove(key) ?: return
        try {
            gone()
        } finally {
            _changes.tryEmit(HoldChange(key, false))
        }
    }

    fun releaseDevice(displayKey: String) {
        held.keys.filter { it.displayKey == displayKey }.forEach { key -> runCatching { release(key) } }
    }

    /** Forget every hold without undoing it: the project the presses wrote into has gone. */
    fun clearAll() {
        val keys = held.keys.toList()
        held.clear()
        keys.forEach { _changes.tryEmit(HoldChange(it, false)) }
    }
}
