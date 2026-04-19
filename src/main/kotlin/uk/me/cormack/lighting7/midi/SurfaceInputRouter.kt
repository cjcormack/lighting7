package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes inbound events from attached control surfaces to the app's composition / cue /
 * transport layers.
 *
 * Lifecycle mirrors [MidiLearnSessionManager]:
 *   - [start] subscribes to [DeviceMatcher.events] and launches a per-device collector for
 *     each currently-attached surface. New attachments spin up a fresh collector; detachments
 *     cancel theirs.
 *   - [stop] cancels all running collectors.
 *
 * Hot path per inbound event:
 *   1. Look up the event against the device's [ControlSurfaceRegistry] profile to resolve
 *      a stable `controlId`. Bank-button presses short-circuit straight to
 *      [ActiveBankState.setBank] — they are intentionally not user-bindable.
 *   2. For fader / encoder continuous input: consult [SurfaceFeedbackHooks] soft takeover.
 *      If pickup hasn't occurred, drop the event silently but record the physical position
 *      so subsequent events can trigger pickup.
 *   3. Resolve the binding via [ControlSurfaceBindingService.resolve] using
 *      `(deviceTypeKey, controlId, activeBank)`. Exact-bank wins over bank-agnostic.
 *   4. Dispatch by [BindingTarget] variant into [SurfaceActions]. Flash press / release
 *      is tracked per-binding by [FlashStateTracker] so overlapping holds don't corrupt
 *      each other's release state.
 *   5. Touch events pass through to the feedback hooks so motor writes are suppressed
 *      while a fader is held.
 */
class SurfaceInputRouter(
    private val deviceMatcher: DeviceMatcher,
    private val controllerLookup: (String) -> MidiController?,
    private val bindingService: ControlSurfaceBindingService,
    private val bankState: ActiveBankState,
    private val flashTracker: FlashStateTracker,
    private val projectIdProvider: () -> Int,
    private val actions: SurfaceActions,
    private val types: () -> List<ControlSurfaceRegistry.DeviceTypeInfo> = { ControlSurfaceRegistry.allTypes },
    /** Consulted for touch suppression + soft takeover. Null disables both. */
    private val feedbackHooks: SurfaceFeedbackHooks? = null,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(SurfaceInputRouter::class.java)
    }

    private val inputJobs = ConcurrentHashMap<String /* displayKey */, Job>()
    private var matcherJob: Job? = null
    private var scope: CoroutineScope? = null

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
    }

    private fun handleMatcherEvent(event: DeviceMatcher.SurfaceEvent) {
        when (event) {
            is DeviceMatcher.SurfaceEvent.DeviceAttached -> attachCollector(event.handle.displayKey, event.typeKey)
            is DeviceMatcher.SurfaceEvent.DeviceDetached -> {
                inputJobs.remove(event.handle.displayKey)?.cancel()
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
        val match = matchEvent(profile, event) ?: return
        when (match) {
            is ResolvedInput.BankButton -> {
                // Bank buttons short-circuit binding resolution — they're never user-bindable.
                if (match.pressed) bankState.setBank(deviceTypeKey, match.bankId)
            }
            is ResolvedInput.Continuous -> {
                // Soft-takeover check for faders / encoders. Non-motor controls may suppress
                // the event until pickup; motor controls always pass through.
                val accepted = feedbackHooks?.acceptInboundFader(
                    displayKey, deviceTypeKey, match.controlId, match.value7Bit,
                ) ?: true
                if (!accepted) return
                val binding = resolveBinding(deviceTypeKey, match.controlId) ?: return
                dispatchContinuous(binding, match.value7Bit)
            }
            is ResolvedInput.ButtonPress -> {
                val binding = resolveBinding(deviceTypeKey, match.controlId) ?: return
                dispatchButtonPress(deviceTypeKey, binding)
            }
            is ResolvedInput.ButtonRelease -> {
                val binding = resolveBinding(deviceTypeKey, match.controlId) ?: return
                dispatchButtonRelease(binding)
            }
            is ResolvedInput.Touch -> {
                feedbackHooks?.onTouch(displayKey, match.controlId, match.down)
            }
        }
    }

    private fun resolveBinding(deviceTypeKey: String, controlId: String): ControlSurfaceBindingService.ResolvedBinding? {
        val projectId = try {
            projectIdProvider()
        } catch (e: Exception) {
            logger.debug("Surface route: no active project ({})", e.message)
            return null
        }
        val bank = bankState.bankFor(deviceTypeKey)
        return bindingService.resolve(projectId, deviceTypeKey, controlId, bank)
    }

    private fun dispatchContinuous(
        binding: ControlSurfaceBindingService.ResolvedBinding,
        value7Bit: UByte,
    ) {
        when (val target = binding.target) {
            is BindingTarget.FixtureProperty -> actions.writeFixtureProperty(
                target.fixtureKey, target.propertyName, value7Bit,
            )
            is BindingTarget.GroupProperty -> actions.writeGroupProperty(
                target.groupName, target.propertyName, value7Bit,
            )
            else -> {
                logger.debug(
                    "Ignoring continuous input on binding {} → {} (discrete target)",
                    binding.id, target::class.simpleName,
                )
            }
        }
    }

    private fun dispatchButtonPress(
        deviceTypeKey: String,
        binding: ControlSurfaceBindingService.ResolvedBinding,
    ) {
        when (val target = binding.target) {
            is BindingTarget.CueStackGo -> actions.cueStackGo(target.stackId)
            is BindingTarget.CueStackBack -> actions.cueStackBack(target.stackId)
            is BindingTarget.CueStackPause -> actions.cueStackPause(target.stackId)
            is BindingTarget.FireCue -> actions.fireCue(target.cueId)
            is BindingTarget.Blackout -> actions.toggleBlackout()
            is BindingTarget.GrandMasterToggle -> actions.toggleGrandMaster()
            is BindingTarget.SetBank -> bankState.setBank(target.deviceTypeKey, target.bank)
            is BindingTarget.Flash -> {
                val newlyPressed = flashTracker.pressed(binding.id)
                if (!newlyPressed) return  // retrigger while held
                val max = target.max.toUByte()
                when (val inner = target.target) {
                    is BindingTarget.FixtureProperty -> actions.flashFixturePropertyPress(
                        inner.fixtureKey, inner.propertyName, max,
                    )
                    is BindingTarget.GroupProperty -> actions.flashGroupPropertyPress(
                        inner.groupName, inner.propertyName, max,
                    )
                    else -> {
                        // Forbidden by Flash's init block — defensive log.
                        logger.warn("Flash binding {} has non-property target {}", binding.id, inner::class.simpleName)
                    }
                }
            }
            is BindingTarget.FixtureProperty,
            is BindingTarget.GroupProperty,
                -> {
                // A button press on a continuous target = write full value (max "1" in 7-bit).
                // Behaves like a momentary full-on without release handling. v1 design choice:
                // these targets on buttons are equivalent to Flash with max=255 but without the
                // release step — operator should use Flash if they want that semantics.
                if (target is BindingTarget.FixtureProperty) {
                    actions.writeFixtureProperty(target.fixtureKey, target.propertyName, 127u)
                } else if (target is BindingTarget.GroupProperty) {
                    actions.writeGroupProperty(target.groupName, target.propertyName, 127u)
                }
            }
        }
    }

    private fun dispatchButtonRelease(binding: ControlSurfaceBindingService.ResolvedBinding) {
        val target = binding.target
        if (target !is BindingTarget.Flash) return
        if (!flashTracker.clearPress(binding.id)) return
        when (val inner = target.target) {
            is BindingTarget.FixtureProperty -> actions.flashFixturePropertyRelease(
                inner.fixtureKey, inner.propertyName,
            )
            is BindingTarget.GroupProperty -> actions.flashGroupPropertyRelease(
                inner.groupName, inner.propertyName,
            )
            else -> Unit
        }
    }

    /**
     * Match an inbound event against a device profile. Returns one of the
     * [ResolvedInput] variants or null if the event doesn't correspond to any declared
     * control on the device.
     */
    private fun matchEvent(
        profile: ControlSurfaceRegistry.DeviceTypeInfo,
        event: MidiInputEvent,
    ): ResolvedInput? {
        when (event) {
            is MidiInputEvent.ControlChange -> {
                for (control in profile.controls) {
                    val (cc, channel) = when (control) {
                        is FaderDescriptor -> control.cc to control.channel
                        is EncoderDescriptor -> control.cc to control.channel
                        else -> continue
                    }
                    if (cc == event.cc && channel == event.channel) {
                        return ResolvedInput.Continuous(control.controlId, event.value)
                    }
                }
            }
            // NoteOn(velocity=0) is the running-status-friendly form of NoteOff per MIDI spec.
            is MidiInputEvent.NoteOn -> return matchNoteEvent(profile, event.channel, event.note, pressed = event.velocity.toInt() > 0)
            is MidiInputEvent.NoteOff -> return matchNoteEvent(profile, event.channel, event.note, pressed = false)
            is MidiInputEvent.PitchBend -> Unit  // No descriptor maps PitchBend in v1.
            is MidiInputEvent.SysEx -> Unit
        }
        return null
    }

    private fun matchNoteEvent(
        profile: ControlSurfaceRegistry.DeviceTypeInfo,
        channel: Int,
        note: Int,
        pressed: Boolean,
    ): ResolvedInput? {
        for (control in profile.controls) {
            when (control) {
                is ButtonDescriptor ->
                    if (control.note == note && control.channel == channel)
                        return if (pressed) ResolvedInput.ButtonPress(control.controlId)
                        else ResolvedInput.ButtonRelease(control.controlId)
                is EncoderDescriptor ->
                    if (control.pushNote != null && control.pushNote == note && control.channel == channel)
                        return if (pressed) ResolvedInput.ButtonPress(control.controlId)
                        else ResolvedInput.ButtonRelease(control.controlId)
                is FaderDescriptor ->
                    if (control.touchNote != null && control.touchNote == note && control.channel == channel)
                        return ResolvedInput.Touch(control.controlId, down = pressed)
                is BankButtonDescriptor ->
                    // Bank buttons switch on press only; the release event is swallowed.
                    if (control.note == note && control.channel == channel)
                        return if (pressed) ResolvedInput.BankButton(control.bankId, pressed = true) else null
            }
        }
        return null
    }

    private sealed class ResolvedInput {
        data class Continuous(val controlId: String, val value7Bit: UByte) : ResolvedInput()
        data class ButtonPress(val controlId: String) : ResolvedInput()
        data class ButtonRelease(val controlId: String) : ResolvedInput()
        data class Touch(val controlId: String, val down: Boolean) : ResolvedInput()
        data class BankButton(val bankId: String, val pressed: Boolean) : ResolvedInput()
    }
}
