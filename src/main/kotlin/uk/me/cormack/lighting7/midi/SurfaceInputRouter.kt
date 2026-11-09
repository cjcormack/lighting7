package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.history.HistorySource
import uk.me.cormack.lighting7.history.WriteOrigin
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.models.describeAssignmentHealth
import uk.me.cormack.lighting7.perf.MidiLatencyStage
import uk.me.cormack.lighting7.perf.MidiLatencyTracker
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes inbound events from attached control surfaces to the app's composition / cue /
 * transport layers.
 *
 * Lifecycle:
 *   - [start] subscribes to [DeviceMatcher.events] and launches a per-device collector for
 *     each currently-attached surface. New attachments spin up a fresh collector; detachments
 *     cancel theirs.
 *   - [stop] cancels all running collectors.
 *
 * Hot path per inbound event:
 *   0. Offer it to [SurfaceIdentify], which keeps a touch a window is waiting for from dispatch.
 *   1. Look the event up in the device profile's [ControlInputIndex] to find the control part it
 *      addresses. Layer-button presses short-circuit straight to [ActiveBankState.setBank] — they
 *      are intentionally not user-bindable.
 *   2. For fader / encoder continuous input: consult [SurfaceFeedbackHooks]'s jump rule
 *      ([JumpPolicy]). A move it refuses is dropped — an undriven fader waiting to pick up, or a
 *      driven control shown its value again — with the physical position recorded.
 *   3. Resolve the binding via [ControlSurfaceBindingService.resolve] using
 *      `(deviceTypeKey, controlId, part, activeBank)`. Exact-bank wins over bank-agnostic.
 *   4. Dispatch the [Binding] through [SurfaceDispatch], which the OSC collector shares, inside its
 *      per-event guard. Holds — a flash's, a select's pending replace — are keyed by control part.
 *   5. Touch events pass through to the feedback hooks so motor writes are suppressed
 *      while a fader is held.
 */
class SurfaceInputRouter(
    private val deviceMatcher: DeviceMatcher,
    private val controllerLookup: (String) -> MidiController?,
    private val bindingService: ControlSurfaceBindingService,
    private val bankState: ActiveBankState,
    private val encoderBankState: EncoderBankState,
    holds: SurfaceHolds,
    private val projectIdProvider: () -> Int,
    private val actions: SurfaceActions,
    private val types: () -> List<ControlSurfaceRegistry.DeviceTypeInfo> = { ControlSurfaceRegistry.allTypes },
    /** Consulted for touch suppression + soft takeover. Null disables both. */
    private val feedbackHooks: SurfaceFeedbackHooks? = null,
    /** Records per-stage wall-clock duration of `dispatchContinuous` / `dispatchButton*`. */
    private val latencyTracker: MidiLatencyTracker = MidiLatencyTracker(),
    /** How long a select button must be held before it replaces the selection. See [SurfaceDispatch.SELECT_HOLD_MS]. */
    selectHoldMs: Long = SurfaceDispatch.SELECT_HOLD_MS,
    /** Gives the project a record cache before an input writes to it, so the write is journaled (undo plan §3.2). */
    private val ensureHistory: (Int) -> Unit = {},
    /** Identify mode, which takes a touch before dispatch sees it. */
    private val identify: SurfaceIdentify? = null,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(SurfaceInputRouter::class.java)

        /**
         * Suppress repeat dead-dispatch warnings for the same `(bindingId, health)`
         * signature inside a 30s window. Fader drags fire dozens of events per second, so
         * without a throttle a single dead binding floods the log while the operator tries
         * to figure out why nothing's happening. Mirrors the dead-assignment throttle in
         * `fx/CueHolds.kt`.
         */
        private const val DEAD_WARN_THROTTLE_MS = 30_000L

        /** Capped to prevent unbounded growth in a long-running process with many churned bindings. */
        private const val DEAD_WARN_STATE_MAX = 1024
    }

    private var matcherJob: Job? = null
    private var scope: CoroutineScope? = null

    private val dispatch = SurfaceDispatch(actions, bankState, encoderBankState, holds, { scope }, selectHoldMs)

    private val inputJobs = ConcurrentHashMap<String /* displayKey */, Job>()

    /**
     * Keyed by binding, or by job slot; value is (lastWarnNanos, signature) for the 30s throttle.
     * Access-ordered LRU capped at [DEAD_WARN_STATE_MAX] — it's a log-rate gate, not a
     * correctness cache, so imprecise eviction is fine.
     */
    private val deadWarnState: MutableMap<String, Pair<Long, String>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Pair<Long, String>>(16, 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, String>>?): Boolean =
                size > DEAD_WARN_STATE_MAX
        },
    )

    fun start(parentScope: CoroutineScope) {
        if (matcherJob != null) return
        scope = parentScope
        matcherJob = parentScope.launch(CoroutineName("SurfaceInputRouter-matcher")) {
            deviceMatcher.events.collect { event -> handleMatcherEvent(event) }
        }
        // Pick up any devices already attached at start time.
        for ((displayKey, attached) in deviceMatcher.attached.value) {
            attachCollector(displayKey, attached.typeKey)
        }
    }

    fun stop() {
        matcherJob?.cancel()
        matcherJob = null
        inputJobs.values.forEach { it.cancel() }
        inputJobs.clear()
        dispatch.cancelPending()
    }

    private fun handleMatcherEvent(event: DeviceMatcher.SurfaceEvent) {
        when (event) {
            is DeviceMatcher.SurfaceEvent.DeviceAttached -> attachCollector(event.handle.displayKey, event.typeKey)
            is DeviceMatcher.SurfaceEvent.DeviceDetached -> {
                inputJobs.remove(event.handle.displayKey)?.cancel()
                // Unplugging mid-hold must not replace the selection half a second later, nor leave
                // a flash on.
                dispatch.releaseDevice(event.handle.displayKey)
            }
            is DeviceMatcher.SurfaceEvent.UnmatchedDeviceConnected -> Unit
        }
    }

    private fun attachCollector(displayKey: String, deviceTypeKey: String) {
        inputJobs.remove(displayKey)?.cancel()
        val controller = controllerLookup(displayKey) ?: return
        val s = scope ?: return
        val job = s.launch(CoroutineName("SurfaceRouter-$displayKey")) {
            controller.input.collect { event -> route(displayKey, deviceTypeKey, event) }
        }
        inputJobs[displayKey] = job
    }

    /**
     * Entry point for tests. Drives a single event through the routing pipeline without
     * requiring a real [MidiController] or [DeviceMatcher]. `displayKey` defaults to the
     * typeKey since most tests have one logical device.
     */
    internal fun offerInputForTest(
        deviceTypeKey: String,
        event: MidiInputEvent,
        displayKey: String = deviceTypeKey,
    ) {
        route(displayKey, deviceTypeKey, event)
    }

    private fun route(displayKey: String, deviceTypeKey: String, event: MidiInputEvent) {
        val profile = types().firstOrNull { it.typeKey == deviceTypeKey } ?: return
        val input = profile.inputs.match(event) ?: return
        if (identify?.offer(displayKey, deviceTypeKey, input) == true) return
        // Each input is one step of the programmer's history, the surface's own; a fader or encoder
        // moved on one control folds into one (undo plan §3.2, §3.6).
        val control = when (input) {
            is ControlInputIndex.Input.Move -> input.control.controlId
            is ControlInputIndex.Input.Press -> input.control.controlId
            is ControlInputIndex.Input.Release -> input.control.controlId
            else -> null
        }
        val origin = WriteOrigin(HistorySource.surface(displayKey)).apply {
            label = "$displayKey ${control ?: "input"}"
            if (input is ControlInputIndex.Input.Move) coalesceKey = "surface|$displayKey|$control"
        }
        runCatching { ensureHistory(projectIdProvider()) }
        // Per event (`FU-MIDI-SHOW-GUARD`): a throw — `state.show` mid-switch among them — must not
        // end the device's collector.
        SurfaceDispatch.guarded("Surface input on $displayKey") {
            WriteOrigin.with(origin) { routeMatched(deviceTypeKey, displayKey, input) }
            ActionOutcome.DONE
        }
    }

    private fun routeMatched(deviceTypeKey: String, displayKey: String, input: ControlInputIndex.Input) {
        when (input) {
            // Layer buttons short-circuit binding resolution — they're never user-bindable.
            is ControlInputIndex.Input.Layer -> bankState.setBank(displayKey, input.bankId)
            is ControlInputIndex.Input.Move -> {
                val controlId = input.control.controlId
                // The jump rule: an undriven fader waits to pick up, a driven control refuses a jump.
                val accepted = feedbackHooks?.acceptInboundFader(
                    displayKey, deviceTypeKey, controlId, input.value7Bit,
                ) ?: true
                if (!accepted) return
                val binding = resolveBinding(deviceTypeKey, displayKey, controlId, ControlPart.MAIN) ?: return
                if (isDeadBinding(binding)) return
                latencyTracker.measure(MidiLatencyStage.INGRESS_CONTINUOUS) {
                    val move = SurfaceDispatch.Input.Move(input.value7Bit, encoder = input.control is EncoderDescriptor)
                    val outcome = dispatch.dispatch(binding.binding, move, origin(deviceTypeKey, displayKey, binding, ControlPart.MAIN))
                    // Continuous input is control rate: what it did not do is a debug line, never a warning per move.
                    if (outcome !is ActionOutcome.Done) {
                        logger.debug("surface-in: binding {} → {}: {}", binding.id, binding.binding.functionId, outcome)
                    }
                }
            }
            is ControlInputIndex.Input.Press -> {
                val binding = resolveBinding(deviceTypeKey, displayKey, input.control.controlId, input.part) ?: return
                if (isDeadBinding(binding)) return
                latencyTracker.measure(MidiLatencyStage.INGRESS_BUTTON) {
                    report(binding, dispatch.dispatch(binding.binding, SurfaceDispatch.Input.Press, origin(deviceTypeKey, displayKey, binding, input.part)))
                }
            }
            is ControlInputIndex.Input.Release -> {
                // Unconditionally, and before anything that can return early: a release must always
                // cancel its own pending replace and let go of its own hold.
                val controlId = input.control.controlId
                latencyTracker.measure(MidiLatencyStage.INGRESS_BUTTON) {
                    dispatch.releasePart(SurfaceDispatch.HoldKey(displayKey, controlId, input.part))
                }
                feedbackHooks?.onButtonRelease(displayKey, controlId, input.part)
            }
            is ControlInputIndex.Input.Touch -> feedbackHooks?.onTouch(displayKey, input.control.controlId, input.down)
        }
    }

    private fun origin(
        deviceTypeKey: String,
        displayKey: String,
        binding: ControlSurfaceBindingService.ResolvedBinding,
        part: ControlPart,
    ) = SurfaceDispatch.Origin(
        deviceTypeKey,
        HistorySource.surface(displayKey),
        SurfaceDispatch.HoldKey(displayKey, binding.controlId, part),
    )

    /** A MIDI press has no reply channel, so a press that did nothing says why in the log. */
    private fun report(binding: ControlSurfaceBindingService.ResolvedBinding, outcome: ActionOutcome) {
        when (outcome) {
            is ActionOutcome.Done -> Unit
            is ActionOutcome.Refused ->
                if (outcome.code == ActionOutcome.WRONG_KIND) logger.debug("surface-in: binding {}: {}", binding.id, outcome.message)
                else logger.warn("surface-in: binding {} refused: {}", binding.id, outcome.message)
            is ActionOutcome.NotFound -> logger.warn("surface-in: binding {}: {}", binding.id, outcome.message)
            is ActionOutcome.Failed -> logger.warn("surface-in: binding {} failed: {}", binding.id, outcome.message)
        }
    }

    private fun resolveBinding(
        deviceTypeKey: String,
        displayKey: String,
        controlId: String,
        part: ControlPart,
    ): ControlSurfaceBindingService.ResolvedBinding? {
        val projectId = try {
            projectIdProvider()
        } catch (e: Exception) {
            logger.debug("Surface route: no active project ({})", e.message)
            return null
        }
        val bank = bankState.bankFor(displayKey)
        return bindingService.resolve(
            projectId, deviceTypeKey, controlId, bank, encoderBankState.selectionFor(displayKey), part,
        )
    }

    /**
     * Short-circuit dispatch for bindings whose target no longer resolves against the
     * current project. Logs a throttled warn so the operator can see *why* the control
     * isn't doing anything without flooding the log on a held fader.
     */
    private fun isDeadBinding(binding: ControlSurfaceBindingService.ResolvedBinding): Boolean {
        val health = binding.health
        if (health is AssignmentHealth.Ok) return false
        val signature = describeAssignmentHealth(health)
        val now = System.nanoTime()
        val throttleNs = DEAD_WARN_THROTTLE_MS * 1_000_000L
        val key = binding.job?.let { "job ${it.jobId} slot ${it.slot}" } ?: "binding ${binding.id}"
        val previous = deadWarnState[key]
        if (previous == null || previous.second != signature || (now - previous.first) >= throttleNs) {
            deadWarnState[key] = now to signature
            logger.warn(
                "surface-in: dropping dead binding id={} control={} ({})",
                binding.id, binding.controlId, signature,
            )
        }
        return true
    }
}
