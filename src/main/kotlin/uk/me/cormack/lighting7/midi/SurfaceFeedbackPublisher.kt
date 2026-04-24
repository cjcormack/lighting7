package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.dmx.packChannelKey
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fx.Layer3Resolver
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.plugins.CueEditSessionRegistry
import uk.me.cormack.lighting7.plugins.CueEditSessionState
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.show.FixturesChangeListener
import java.util.concurrent.atomic.AtomicReference

/**
 * Contract the [SurfaceInputRouter] consults on every inbound event with feedback relevance.
 * Production wires this to [SurfaceFeedbackPublisher]; tests inject a fake or pass `null`.
 */
interface SurfaceFeedbackHooks {
    /**
     * Notify that a touch-sensitive fader has been touched or released. While a fader is
     * touched the publisher must not drive its motor — otherwise the motor fights the finger.
     */
    fun onTouch(displayKey: String, controlId: String, down: Boolean)

    /**
     * Decide whether an inbound fader value should be accepted. Returns true to apply, false
     * to suppress (pickup not yet). Implementations update their internal state regardless
     * of the return value — [SoftTakeoverStateMachine.acceptInboundFader] bumps the last
     * known physical position on every call.
     */
    fun acceptInboundFader(
        displayKey: String,
        deviceTypeKey: String,
        controlId: String,
        value7Bit: UByte,
    ): Boolean

    /**
     * Notify that a button has just been released. Publisher re-asserts the button's LED to
     * its logical state, bypassing delta suppression, to work around surfaces (notably the
     * X-Touch Compact in Momentary mode) that locally drive the LED off on release even when
     * we've already told them to keep it on. No-op default — only the real publisher cares.
     */
    fun onButtonRelease(displayKey: String, controlId: String) {}
}

/**
 * Observes the composition model (channel changes, flash state, scaler state) and drives
 * MIDI feedback back to attached control surfaces: motor position for motorised faders,
 * LED ring position for encoders, button LEDs for flash / blackout / grand-master bindings.
 *
 * Also hosts the supporting state — [TouchStateTracker] and [SoftTakeoverStateMachine] —
 * and implements [SurfaceFeedbackHooks] so the router can consult them on every inbound event.
 *
 * ## Data model
 *
 * Indexes rebuild on any of: binding add / update / remove, device attach / detach,
 * active-bank change, fixture registration change, project change. The effective takeover
 * policy is baked into [ContinuousEntry] at rebuild time so the hot [onChannelsChanged] path
 * never walks the binding cache or attached-devices list.
 *
 * ## Lifecycle
 *
 * [start] launches all subscription jobs. [stop] cancels them and detaches from the current
 * fixture registry. Project switches re-attach internally via [onProjectChanged] — callers
 * don't need to restart the publisher.
 */
class SurfaceFeedbackPublisher(
    private val deviceMatcher: DeviceMatcher,
    private val controllerLookup: (String) -> MidiController?,
    private val bindingService: ControlSurfaceBindingService,
    private val bankState: ActiveBankState,
    private val flashTracker: FlashStateTracker,
    private val projectIdProvider: () -> Int,
    private val fixturesProvider: () -> Fixtures,
    private val globalScalerStateProvider: () -> GlobalScalerState,
    private val types: () -> List<ControlSurfaceRegistry.DeviceTypeInfo> = { ControlSurfaceRegistry.allTypes },
    val touchState: TouchStateTracker = TouchStateTracker(),
    val takeover: SoftTakeoverStateMachine = SoftTakeoverStateMachine(),
    /**
     * Phase 6: when non-null, feedback for bound continuous targets reflects the cue-edit
     * session's current Layer 3 assignment for the target (if any) instead of the composed
     * live DMX value. Lookup is cached internally so the hot [onChannelsChanged] path stays
     * allocation-free when no session is open.
     */
    private val cueEditSessionProvider: ((Int) -> CueEditSessionState?)? = null,
    /** When provided, [start] subscribes to keep the session-assignments cache in sync. */
    private val cueEditEvents: SharedFlow<CueEditSessionRegistry.Event>? = null,
) : SurfaceFeedbackHooks {

    companion object {
        private val logger = LoggerFactory.getLogger(SurfaceFeedbackPublisher::class.java)
    }

    /**
     * Continuous-binding entry in the channel reverse index. [policy] is the effective
     * takeover policy (per-binding override > device-class default) computed once at
     * [rebuildIndex] time — saves a binding-cache lookup per DMX tick on the hot path.
     *
     * [primaryChannel] is the channel whose DMX value drives the 7-bit feedback position.
     * For a slider binding it's the slider's channel; for a colour binding it's the red
     * axis (keeping the mapping symmetric with the write path which fans the same 7-bit
     * value to all three).
     */
    private data class ContinuousEntry(
        val displayKey: String,
        val deviceTypeKey: String,
        val control: ControlDescriptor,
        val binding: ControlSurfaceBindingService.ResolvedBinding,
        val primaryChannel: PropertyChannelResolver.PropertyChannel,
        val policy: BindingTakeoverPolicy,
    )

    /** Discrete-binding entry for LED feedback (Flash / Blackout / GrandMasterToggle). */
    private data class LedEntry(
        val displayKey: String,
        val control: ButtonDescriptor,
        val binding: ControlSurfaceBindingService.ResolvedBinding,
    )

    /** Snapshot of every derived index produced by a single [rebuildIndex] pass. */
    private data class Index(
        val byChannel: Map<Long, List<ContinuousEntry>>,
        val continuousByDisplay: Map<String, List<ContinuousEntry>>,
        /**
         * Secondary index of fixture / group continuous entries keyed by assignment identity —
         * lets [resyncEntriesMatching] skip the per-entry walk on the cue-edit hot path.
         * Populated only for bindings whose target produces an [AssignmentKey]
         * (FixtureProperty / GroupProperty); Flash / cueStack / blackout targets are absent.
         */
        val continuousByAssignmentKey: Map<AssignmentKey, List<ContinuousEntry>>,
        val ledsByDisplay: Map<String, List<LedEntry>>,
        val flashByBindingId: Map<Int, LedEntry>,
        val blackoutLeds: List<LedEntry>,
        val grandMasterLeds: List<LedEntry>,
    ) {
        companion object {
            val EMPTY = Index(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyList())
        }
    }

    private val index = AtomicReference(Index.EMPTY)

    @Volatile
    private var currentFixtures: Fixtures? = null

    /**
     * Per-target cached cue assignments for the active cue-edit session. Keyed by
     * `(targetType, targetKey, propertyName)` — matches [CuePropertyAssignmentDto] row
     * identity. Empty when no session is active. Rebuilt on session start / mode change /
     * discard; patched incrementally on single-assignment events.
     */
    internal data class AssignmentKey(val targetType: String, val targetKey: String, val propertyName: String)

    private val sessionAssignments = AtomicReference<Map<AssignmentKey, String>>(emptyMap())

    private val fixtureListener = object : FixturesChangeListener {
        override fun channelsChanged(universe: Universe, changes: Map<Int, UByte>) =
            onChannelsChanged(universe, changes)
        override fun controllersChanged() {}
        override fun fixturesChanged() { rebuildIndex() }
        override fun presetListChanged() {}
        override fun cueListChanged() {}
        override fun cueStackListChanged() {}
        override fun cueSlotListChanged() {}
        override fun patchListChanged() {}
        override fun showEntriesChanged() {}
        override fun showChanged(projectId: Int, activeEntryId: Int?, activatedStackId: Int?, activatedStackName: String?) {}
    }

    private val jobs = mutableListOf<Job>()
    private var scalerJob: Job? = null
    private var publisherScope: CoroutineScope? = null
    private var running = false

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        publisherScope = scope
        attachToFixtures()

        jobs += scope.launch(CoroutineName("FeedbackPublisher-matcher")) {
            deviceMatcher.events.collect { onSurfaceEvent(it) }
        }
        jobs += scope.launch(CoroutineName("FeedbackPublisher-bindings")) {
            bindingService.changes.collect { rebuildIndex() }
        }
        jobs += scope.launch(CoroutineName("FeedbackPublisher-banks")) {
            bankState.changes.collect { onBankChanged(it) }
        }
        jobs += scope.launch(CoroutineName("FeedbackPublisher-flash")) {
            flashTracker.changes.collect { onFlashChanged(it) }
        }
        subscribeScaler(scope)
        cueEditEvents?.let { events ->
            jobs += scope.launch(CoroutineName("FeedbackPublisher-cueEdit")) {
                events.collect { onCueEditEvent(it) }
            }
        }
    }

    /**
     * (Re)subscribe to the current show's [GlobalScalerState] flows. The facade is
     * re-created on project switch (the underlying holder is preserved elsewhere), so
     * the subscription needs to follow the active facade — otherwise the publisher would
     * still observe the previous project's holder after a switch.
     */
    private fun subscribeScaler(scope: CoroutineScope) {
        scalerJob?.cancel()
        val scaler = try {
            globalScalerStateProvider()
        } catch (_: Exception) {
            null
        } ?: return
        scalerJob = combine(scaler.blackoutEnabled, scaler.grandMasterEnabled) { b, g -> b to g }
            .onEach { (blackout, grandMaster) -> onScalerChanged(blackout, grandMaster) }
            .launchIn(scope)
    }

    fun stop() {
        running = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        scalerJob?.cancel()
        scalerJob = null
        publisherScope = null
        sessionAssignments.set(emptyMap())
        detachFromFixtures()
    }

    fun onProjectChanged() {
        detachFromFixtures()
        touchState.clearAll()
        takeover.clearAll()
        // Any cached cue-edit state belongs to the previous project's cue; drop it so the
        // registry's next event on the new project rebuilds from scratch.
        sessionAssignments.set(emptyMap())
        attachToFixtures()
        rebuildIndex()
        // Re-subscribe to the new show's scaler facade — the previous subscription
        // observes the stale facade (its holder is preserved, but the facade itself is
        // re-created on project switch).
        publisherScope?.let { subscribeScaler(it) }
        // Push a full resync for every currently-attached device so the new show's logical
        // values land on the hardware.
        for (displayKey in deviceMatcher.attached.value.keys) {
            sendFullResync(displayKey)
        }
    }

    private fun attachToFixtures() {
        try {
            val f = fixturesProvider()
            f.registerListener(fixtureListener)
            currentFixtures = f
        } catch (e: Exception) {
            logger.debug("Feedback publisher: no show yet ({})", e.message)
        }
    }

    private fun detachFromFixtures() {
        currentFixtures?.unregisterListener(fixtureListener)
        currentFixtures = null
    }

    // --- Hooks consumed by SurfaceInputRouter ---

    override fun onTouch(displayKey: String, controlId: String, down: Boolean) {
        touchState.setTouched(displayKey, controlId, down)
        if (!down) {
            // Motor catch-up: whatever the logical value is now, drive to it.
            resyncControl(displayKey, controlId)
        }
    }

    override fun onButtonRelease(displayKey: String, controlId: String) {
        val entry = index.get().ledsByDisplay[displayKey]?.firstOrNull {
            it.control.controlId == controlId
        } ?: run {
            logger.debug("onButtonRelease: no LED entry for {}/{}", displayKey, controlId)
            return
        }
        val controller = controllerLookup(displayKey) ?: return
        val on = when (val target = entry.binding.target) {
            is BindingTarget.Flash -> flashTracker.isActive(entry.binding.id)
            is BindingTarget.Blackout -> globalScalerStateProvider().blackoutEnabled.value
            is BindingTarget.GrandMasterToggle -> globalScalerStateProvider().grandMasterEnabled.value
            else -> {
                logger.debug("onButtonRelease: unexpected target {} for led entry", target::class.simpleName)
                return
            }
        }
        logger.debug("onButtonRelease: reasserting LED {}/{} note={} on={}", displayKey, controlId, entry.control.note, on)
        controller.invalidateFeedbackCache(
            MidiControlKey(entry.control.channel, MidiControlKey.Type.NOTE, entry.control.note)
        )
        sendLed(entry, on)
    }

    override fun acceptInboundFader(
        displayKey: String,
        deviceTypeKey: String,
        controlId: String,
        value7Bit: UByte,
    ): Boolean = takeover.acceptInboundFader(
        displayKey, controlId, value7Bit, effectivePolicyFor(deviceTypeKey, controlId),
    )

    /**
     * Resolve the effective takeover policy for a control the hooks path sees — which may
     * come from a control not in the current bank (e.g. a cross-bank fader still being
     * wiggled by the operator). Pre-baked policy on [ContinuousEntry] is the primary path;
     * this is the fallback for unknown / out-of-index controls.
     */
    internal fun effectivePolicyFor(deviceTypeKey: String, controlId: String): BindingTakeoverPolicy {
        val attached = deviceMatcher.attached.value.values.firstOrNull { it.typeKey == deviceTypeKey }
        val control = attached?.instance?.controls?.firstOrNull { it.controlId == controlId }
        val classDefault = if (control is FaderDescriptor && !control.hasMotor) {
            BindingTakeoverPolicy.PICKUP
        } else {
            BindingTakeoverPolicy.IMMEDIATE
        }
        val projectId = try {
            projectIdProvider()
        } catch (_: Exception) {
            return classDefault
        }
        val bank = bankState.bankFor(deviceTypeKey)
        val binding = bindingService.resolve(projectId, deviceTypeKey, controlId, bank)
        return binding?.takeoverPolicy ?: classDefault
    }

    // --- Event handlers ---

    private fun onSurfaceEvent(event: DeviceMatcher.SurfaceEvent) {
        when (event) {
            is DeviceMatcher.SurfaceEvent.DeviceAttached -> {
                rebuildIndex()
                sendFullResync(event.handle.displayKey)
            }
            is DeviceMatcher.SurfaceEvent.DeviceDetached -> {
                touchState.clearDevice(event.handle.displayKey)
                takeover.clearDevice(event.handle.displayKey)
                rebuildIndex()
            }
            is DeviceMatcher.SurfaceEvent.UnmatchedDeviceConnected -> Unit
        }
    }

    private fun onBankChanged(change: ActiveBankState.BankChange) {
        rebuildIndex()
        for ((displayKey, attached) in deviceMatcher.attached.value) {
            if (attached.typeKey != change.deviceTypeKey) continue
            sendFullResync(displayKey)
        }
    }

    private fun onFlashChanged(change: FlashStateTracker.FlashChange) {
        val entry = index.get().flashByBindingId[change.bindingId] ?: return
        sendLed(entry, change.pressed)
    }

    private fun onScalerChanged(blackoutEnabled: Boolean, grandMasterEnabled: Boolean) {
        val idx = index.get()
        for (entry in idx.blackoutLeds) sendLed(entry, blackoutEnabled)
        // Grand Master LED: "ON" = grand master engaged (normal output). Some consoles invert
        // this; we follow the `enabled` flag so a lit button means "lights are live".
        for (entry in idx.grandMasterLeds) sendLed(entry, grandMasterEnabled)
    }

    private fun onChannelsChanged(universe: Universe, changes: Map<Int, UByte>) {
        val byChannel = index.get().byChannel
        if (byChannel.isEmpty()) return
        for (channel in changes.keys) {
            val entries = byChannel[packChannelKey(universe.universe, channel)] ?: continue
            for (entry in entries) {
                val value = computeValue7Bit(entry)
                sendContinuousFeedback(entry, value)
                takeover.setLogical(entry.displayKey, entry.control.controlId, value, entry.policy)
            }
        }
    }

    // --- Cue-edit session handling (Phase 6) ---

    /**
     * React to a [CueEditSessionRegistry] event. Lifecycle events (Started / Ended / mode
     * change / discard) rebuild the cached assignments map and resync every attached device
     * — bound feedback may switch between cue-value and live-value sources. Incremental
     * events (AssignmentChanged / AssignmentCleared) patch the cache and resync only the
     * entries whose target matches.
     */
    private fun onCueEditEvent(event: CueEditSessionRegistry.Event) {
        when (event) {
            is CueEditSessionRegistry.Event.Started -> {
                sessionAssignments.set(buildAssignmentMap(event.session.snapshot))
                resyncAllDevices()
            }
            is CueEditSessionRegistry.Event.ModeChanged -> {
                // Snapshot doesn't change across mode flips; feedback source might (stage vs.
                // blind) so resync.
                resyncAllDevices()
            }
            is CueEditSessionRegistry.Event.Ended -> {
                sessionAssignments.set(emptyMap())
                resyncAllDevices()
            }
            is CueEditSessionRegistry.Event.AssignmentChanged -> {
                val key = AssignmentKey(event.targetType, event.targetKey, event.propertyName)
                val current = sessionAssignments.get()
                sessionAssignments.set(current + (key to event.value))
                resyncEntriesMatching(key)
            }
            is CueEditSessionRegistry.Event.AssignmentCleared -> {
                val key = AssignmentKey(event.targetType, event.targetKey, event.propertyName)
                val current = sessionAssignments.get()
                if (key in current) {
                    sessionAssignments.set(current - key)
                }
                resyncEntriesMatching(key)
            }
            is CueEditSessionRegistry.Event.AssignmentsReloaded -> {
                sessionAssignments.set(buildAssignmentMap(event.assignments))
                resyncAllDevices()
            }
        }
    }

    private fun buildAssignmentMap(rows: List<CuePropertyAssignmentDto>): Map<AssignmentKey, String> =
        rows.associate { AssignmentKey(it.targetType, it.targetKey, it.propertyName) to it.value }

    private fun resyncAllDevices() {
        for (displayKey in deviceMatcher.attached.value.keys) {
            sendFullResync(displayKey)
        }
    }

    private fun resyncEntriesMatching(key: AssignmentKey) {
        val entries = index.get().continuousByAssignmentKey[key] ?: return
        for (entry in entries) {
            sendContinuousFeedback(entry, computeValue7Bit(entry))
        }
    }

    /**
     * Convert a cue's assignment value string to the 7-bit feedback position for [entry]'s
     * primary channel. Returns null if the string doesn't parse for the property type (the
     * caller falls back to the DMX-derived value). Uses the channel's declared category /
     * `min..max` to respect slider sub-ranges and colour fan-out.
     */
    private fun value7BitFromAssignment(entry: ContinuousEntry, valueStr: String): UByte? {
        val pc = entry.primaryChannel
        val propertyName = when (val t = entry.binding.target) {
            is BindingTarget.FixtureProperty -> t.propertyName
            is BindingTarget.GroupProperty -> t.propertyName
            else -> return null
        }
        val parsed = Layer3Resolver.parseAssignmentValue(pc.category, propertyName, valueStr) ?: return null
        return when (parsed) {
            is Layer3Resolver.PropertyValue.Slider ->
                PropertyChannelResolver.scaleWithinRangeTo7Bit(parsed.value, pc.min, pc.max)
            is Layer3Resolver.PropertyValue.Setting ->
                PropertyChannelResolver.scaleWithinRangeTo7Bit(parsed.channelValue, pc.min, pc.max)
            is Layer3Resolver.PropertyValue.Colour -> {
                // Primary channel for a colour binding is the red axis (0..255 range). Pick
                // the red component to drive the motor — a uniform grey set by
                // [serializeToAssignmentValue] round-trips to the same 7-bit position.
                PropertyChannelResolver.scaleDmxTo7Bit(parsed.value.color.red.toUByte())
            }
            is Layer3Resolver.PropertyValue.Position -> {
                // Primary channel for a position binding is the pan axis.
                PropertyChannelResolver.scaleDmxTo7Bit(parsed.pan)
            }
        }
    }

    // --- Rebuild & resync ---

    internal fun rebuildIndexForTest() = rebuildIndex()

    internal fun simulateChannelsChangedForTest(universe: Universe, changes: Map<Int, UByte>) =
        onChannelsChanged(universe, changes)

    private fun rebuildIndex() {
        val projectId = try {
            projectIdProvider()
        } catch (_: Exception) {
            index.set(Index.EMPTY)
            return
        }
        val fixtures = currentFixtures
        val attached = deviceMatcher.attached.value
        val profilesByKey = types().associateBy { it.typeKey }
        val byChannel = HashMap<Long, MutableList<ContinuousEntry>>()
        val continuousByDisplay = HashMap<String, MutableList<ContinuousEntry>>()
        val continuousByAssignmentKey = HashMap<AssignmentKey, MutableList<ContinuousEntry>>()
        val ledsByDisplay = HashMap<String, MutableList<LedEntry>>()
        val flashByBindingId = HashMap<Int, LedEntry>()
        val blackoutLeds = mutableListOf<LedEntry>()
        val grandMasterLeds = mutableListOf<LedEntry>()

        for ((displayKey, a) in attached) {
            val profile = profilesByKey[a.typeKey] ?: continue
            val bank = bankState.bankFor(a.typeKey)
            for (control in profile.controls) {
                val binding = bindingService.resolve(projectId, a.typeKey, control.controlId, bank) ?: continue
                if (control is FaderDescriptor || control is EncoderDescriptor) {
                    val primary = fixtures?.let { findPrimaryChannel(it, binding.target) }
                    if (primary != null) {
                        val classDefault = if (control is FaderDescriptor && !control.hasMotor) {
                            BindingTakeoverPolicy.PICKUP
                        } else {
                            BindingTakeoverPolicy.IMMEDIATE
                        }
                        val entry = ContinuousEntry(
                            displayKey = displayKey,
                            deviceTypeKey = a.typeKey,
                            control = control,
                            binding = binding,
                            primaryChannel = primary,
                            policy = binding.takeoverPolicy ?: classDefault,
                        )
                        byChannel.getOrPut(packChannelKey(primary.universe.universe, primary.channel)) { mutableListOf() }
                            .add(entry)
                        continuousByDisplay.getOrPut(displayKey) { mutableListOf() }.add(entry)
                        assignmentKeyFor(entry)?.let { key ->
                            continuousByAssignmentKey.getOrPut(key) { mutableListOf() }.add(entry)
                        }
                    }
                }
                if (control is ButtonDescriptor && control.ledFeedback != LedFeedback.NONE) {
                    val target = binding.target
                    if (target is BindingTarget.Flash || target is BindingTarget.Blackout ||
                        target is BindingTarget.GrandMasterToggle) {
                        val entry = LedEntry(displayKey, control, binding)
                        ledsByDisplay.getOrPut(displayKey) { mutableListOf() }.add(entry)
                        when (target) {
                            is BindingTarget.Flash -> flashByBindingId[binding.id] = entry
                            is BindingTarget.Blackout -> blackoutLeds += entry
                            is BindingTarget.GrandMasterToggle -> grandMasterLeds += entry
                            else -> Unit
                        }
                    }
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        index.set(
            Index(
                byChannel = byChannel as Map<Long, List<ContinuousEntry>>,
                continuousByDisplay = continuousByDisplay as Map<String, List<ContinuousEntry>>,
                continuousByAssignmentKey = continuousByAssignmentKey as Map<AssignmentKey, List<ContinuousEntry>>,
                ledsByDisplay = ledsByDisplay as Map<String, List<LedEntry>>,
                flashByBindingId = flashByBindingId,
                blackoutLeds = blackoutLeds,
                grandMasterLeds = grandMasterLeds,
            )
        )
    }

    private fun findPrimaryChannel(
        fixtures: Fixtures,
        target: BindingTarget,
    ): PropertyChannelResolver.PropertyChannel? = when (target) {
        is BindingTarget.FixtureProperty -> {
            val fixture = try {
                fixtures.untypedFixture(target.fixtureKey)
            } catch (_: Exception) { null }
            fixture?.let { PropertyChannelResolver.describeFixtureProperty(it, target.propertyName).firstOrNull() }
        }
        is BindingTarget.GroupProperty -> {
            val group = try {
                fixtures.untypedGroup(target.groupName)
            } catch (_: Exception) { null }
            group?.fixtures?.firstOrNull()?.let { first ->
                if (first is Fixture) PropertyChannelResolver.describeFixtureProperty(first, target.propertyName).firstOrNull() else null
            }
        }
        else -> null
    }

    /** Resync one specific control — motor catch-up on touch-off. */
    private fun resyncControl(displayKey: String, controlId: String) {
        val entries = index.get().continuousByDisplay[displayKey] ?: return
        for (entry in entries) {
            if (entry.control.controlId != controlId) continue
            sendContinuousFeedback(entry, computeValue7Bit(entry))
        }
    }

    /** Full resync: drive motors / rings / LEDs for every bound control on a device. */
    private fun sendFullResync(displayKey: String) {
        if (currentFixtures == null) return
        val idx = index.get()
        for (entry in idx.continuousByDisplay[displayKey].orEmpty()) {
            val value = computeValue7Bit(entry)
            sendContinuousFeedback(entry, value)
            if (entry.policy == BindingTakeoverPolicy.PICKUP) {
                takeover.forcePickup(entry.displayKey, entry.control.controlId, value)
            } else {
                takeover.setLogical(entry.displayKey, entry.control.controlId, value, entry.policy)
            }
        }
        val leds = idx.ledsByDisplay[displayKey].orEmpty()
        if (leds.isEmpty()) return
        val scaler = globalScalerStateProvider()
        val blackoutOn = scaler.blackoutEnabled.value
        val grandMasterOn = scaler.grandMasterEnabled.value
        for (entry in leds) {
            val on = when (entry.binding.target) {
                is BindingTarget.Flash -> flashTracker.isActive(entry.binding.id)
                is BindingTarget.Blackout -> blackoutOn
                is BindingTarget.GrandMasterToggle -> grandMasterOn
                else -> false
            }
            sendLed(entry, on)
        }
    }

    private fun computeValue7Bit(entry: ContinuousEntry): UByte {
        // Phase 6: during an active cue-edit session, prefer the cue's own Layer 3 assignment
        // for the bound target. Falls through to the DMX-derived value when the cue hasn't
        // asserted this property (or the provider reports no session), so an un-edited fader
        // still shows the live composed value.
        val assignments = sessionAssignments.get()
        if (assignments.isNotEmpty() && cueEditSessionProvider != null) {
            val key = assignmentKeyFor(entry)
            if (key != null) {
                val value = assignments[key]
                if (value != null) {
                    value7BitFromAssignment(entry, value)?.let { return it }
                }
            }
        }
        val fixtures = currentFixtures ?: return 0u
        val controller: DmxController = try {
            fixtures.controller(entry.primaryChannel.universe)
        } catch (_: Exception) {
            return 0u
        }
        val dmx = controller.getValue(entry.primaryChannel.channel)
        val pc = entry.primaryChannel
        return PropertyChannelResolver.scaleWithinRangeTo7Bit(dmx, pc.min, pc.max)
    }

    private fun assignmentKeyFor(entry: ContinuousEntry): AssignmentKey? = when (val t = entry.binding.target) {
        is BindingTarget.FixtureProperty -> AssignmentKey("fixture", t.fixtureKey, t.propertyName)
        is BindingTarget.GroupProperty -> AssignmentKey("group", t.groupName, t.propertyName)
        else -> null
    }

    private fun sendContinuousFeedback(entry: ContinuousEntry, value7Bit: UByte) {
        val controller = controllerLookup(entry.displayKey) ?: return
        val (channel, cc) = when (val control = entry.control) {
            is FaderDescriptor -> {
                if (!control.hasMotor) return  // Non-motor: no feedback, just takeover tracking.
                if (touchState.isTouched(entry.displayKey, control.controlId)) return
                control.channel to (control.motorCc ?: control.cc)
            }
            is EncoderDescriptor -> control.channel to (control.ringCc ?: return)
            else -> return
        }
        if (logger.isDebugEnabled) {
            val pc = entry.primaryChannel
            val dmx = runCatching { currentFixtures?.controller(pc.universe)?.getValue(pc.channel) }.getOrNull()
            logger.debug(
                "surface-out: control={} dmxCh={} dmx={} min={} max={} -> value7Bit={} cc={}",
                entry.control.controlId, pc.channel, dmx?.toInt(), pc.min.toInt(), pc.max.toInt(),
                value7Bit.toInt(), cc,
            )
        }
        controller.sendFeedback(MidiFeedbackMessage.ControlChangeFeedback(channel, cc, value7Bit))
    }

    private fun sendLed(entry: LedEntry, on: Boolean) {
        val controller = controllerLookup(entry.displayKey) ?: return
        val msg = if (on) {
            MidiFeedbackMessage.NoteOnFeedback(entry.control.channel, entry.control.note, 127u)
        } else {
            MidiFeedbackMessage.NoteOffFeedback(entry.control.channel, entry.control.note, 0u)
        }
        controller.sendFeedback(msg)
    }
}
