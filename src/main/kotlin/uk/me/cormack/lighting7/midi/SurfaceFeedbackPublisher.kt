package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.dmx.packChannelKey
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fx.SpeedMasterBank
import uk.me.cormack.lighting7.fx.speedMasterUuidOrNull
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.perf.MidiLatencyStage
import uk.me.cormack.lighting7.perf.MidiLatencyTracker
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.show.FixturesChangeListener
import uk.me.cormack.lighting7.show.LocateManager
import uk.me.cormack.lighting7.state.DeskSelection
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

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
 * Observes the composition model (channel changes, flash state, scaler state, the desk
 * selection, locate state) and drives MIDI feedback back to attached control surfaces: motor
 * position for motorised faders, LED ring position for encoders, button LEDs for flash /
 * blackout / grand-master / select / locate bindings.
 *
 * Also hosts the supporting state — [TouchStateTracker], [SoftTakeoverStateMachine] and the
 * [ControlStateTracker] behind the `surfaceControls.*` stream — and implements
 * [SurfaceFeedbackHooks] so the router can consult them on every inbound event.
 *
 * ## Data model
 *
 * Indexes rebuild on any of: binding add / update / remove, device attach / detach,
 * active-bank change, fixture registration change, project change, desk-selection change. The
 * effective takeover policy is baked into [ContinuousEntry] at rebuild time so the hot
 * [onChannelsChanged] path never walks the binding cache or attached-devices list.
 *
 * A continuous entry may stand on **several channels** — a fixed group binding on every member,
 * a selection binding on every selected head. Its feedback value is the one 7-bit value those
 * channels agree on, and **null when they disagree**: nothing goes to the motor, the ring is
 * driven to its off state, takeover is disarmed, and the stream reports `value: null`. A turn
 * or move then writes every head and the next tick reads uniform
 * (`docs/plans/midi-surface-plan.md` D10).
 *
 * ## The control-state stream
 *
 * Every send site writes [controlStates] *before* any early return, so the tracker records what
 * a non-motor fader would have been told, and what a touched motor was spared. The frontend
 * never recomputes a control's state from DMX.
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
    /**
     * Speed-master bank for tempo-bound encoders. Like [globalScalerStateProvider] this is
     * a provider rather than a value because a project switch swaps the whole show, and the
     * subscription has to follow the live bank.
     */
    private val speedMasterBankProvider: (() -> SpeedMasterBank)? = null,
    /**
     * The desk selection a `SelectionProperty` control writes and a `SelectTarget` LED shows.
     * A value, not a provider: it is State-scoped and survives a project switch (cleared, not
     * rebuilt). Null disables the selection-relative arms — a harness without a desk.
     */
    private val deskSelection: DeskSelection? = null,
    /** Locate state for `LocateSelection` LEDs; a provider because the manager is per show. */
    private val locateManagerProvider: (() -> LocateManager)? = null,
    private val types: () -> List<ControlSurfaceRegistry.DeviceTypeInfo> = { ControlSurfaceRegistry.allTypes },
    val touchState: TouchStateTracker = TouchStateTracker(),
    val takeover: SoftTakeoverStateMachine = SoftTakeoverStateMachine(),
    /** The `surfaceControls.*` stream's store — see [ControlStateTracker]. */
    val controlStates: ControlStateTracker = ControlStateTracker(),
    /** Records per-stage wall-clock duration of motor / LED egress writes. */
    private val latencyTracker: MidiLatencyTracker = MidiLatencyTracker(),
) : SurfaceFeedbackHooks {

    companion object {
        private val logger = LoggerFactory.getLogger(SurfaceFeedbackPublisher::class.java)

        /**
         * The ring CC value that darkens an encoder ring, per style. `0` for every style the
         * X-Touch Compact can be put in is the working assumption until the rig confirms it
         * (`docs/plans/midi-surface-plan.md` §9 check 3): if the desk lights the first dot on
         * `0`, this is the one constant to change. The tracker's [RingState.OFF] is the truth
         * the view reads regardless of which byte went to the hardware.
         */
        internal fun ringOffValue(style: EncoderRingStyle): UByte? = when (style) {
            EncoderRingStyle.NONE -> null
            EncoderRingStyle.SINGLE_DOT, EncoderRingStyle.FAN, EncoderRingStyle.PAN -> 0u
        }
    }

    /**
     * Continuous-binding entry in the channel reverse index. [policy] is the effective
     * takeover policy (per-binding override > device-class default) computed once at
     * [rebuildIndex] time — saves a binding-cache lookup per DMX tick on the hot path.
     *
     * [channels] are the channels whose DMX values drive the 7-bit feedback position — one for
     * a fixture slider (the red axis for a colour, keeping the mapping symmetric with the write
     * path which fans the same 7-bit value to all three), one per member for a group, one per
     * selected head for a selection binding. Empty for a selection binding with nothing
     * selected: the entry exists so the control reads "no selection" rather than "unbound".
     */
    private data class ContinuousEntry(
        val displayKey: String,
        val deviceTypeKey: String,
        val control: ControlDescriptor,
        val binding: ControlSurfaceBindingService.ResolvedBinding,
        val channels: List<PropertyChannelResolver.PropertyChannel>,
        val policy: BindingTakeoverPolicy,
    )

    /** Discrete-binding entry for LED feedback (Flash / Blackout / GrandMasterToggle / Select / Locate). */
    private data class LedEntry(
        val displayKey: String,
        val control: ButtonDescriptor,
        val binding: ControlSurfaceBindingService.ResolvedBinding,
    )

    /**
     * Continuous entry for a tempo-bound control. Deliberately *not* a [ContinuousEntry]:
     * that type is built around a DMX [PropertyChannelResolver.PropertyChannel], and a speed
     * master has no channel behind it — its value lives in the bank.
     *
     * This exists mainly so soft takeover works. PICKUP only engages once something calls
     * [SoftTakeoverStateMachine.setLogical] for the control, and nothing does that for a
     * target the index doesn't know about — so without this entry a tempo encoder would be
     * permanently ENGAGED and would jump the show's tempo the instant it was touched.
     */
    private data class SpeedMasterEntry(
        val displayKey: String,
        val control: ControlDescriptor,
        val masterUuid: UUID?,
        val minBpm: Double,
        val maxBpm: Double,
        val policy: BindingTakeoverPolicy,
    )

    /** Snapshot of every derived index produced by a single [rebuildIndex] pass. */
    private data class Index(
        val byChannel: Map<Long, List<ContinuousEntry>>,
        val continuousByDisplay: Map<String, List<ContinuousEntry>>,
        val ledsByDisplay: Map<String, List<LedEntry>>,
        val flashByBindingId: Map<Int, LedEntry>,
        val blackoutLeds: List<LedEntry>,
        val grandMasterLeds: List<LedEntry>,
        /** `SelectTarget` buttons: lit while the target's heads are all in the selection. */
        val selectLeds: List<LedEntry>,
        /** `LocateSelection` buttons: lit while every selected target is located. */
        val locateLeds: List<LedEntry>,
        /**
         * Tempo-bound continuous controls. A flat list rather than a map: there is at most
         * one per physical encoder on an attached surface, so a scan per tempo change is
         * cheaper than maintaining an index — and tempo changes are operator-rate, not
         * DMX-tick-rate.
         */
        val speedMasterEntries: List<SpeedMasterEntry>,
    ) {
        companion object {
            val EMPTY = Index(
                emptyMap(), emptyMap(), emptyMap(), emptyMap(),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            )
        }
    }

    private val index = AtomicReference(Index.EMPTY)

    @Volatile
    private var currentFixtures: Fixtures? = null

    private val fixtureListener = object : FixturesChangeListener {
        override fun channelsChanged(universe: Universe, changes: Map<Int, UByte>) =
            onChannelsChanged(universe, changes)
        override fun fixturesChanged() { rebuildAndResync() }
        // A rename is cosmetic, but a create/delete changes which master uuids resolve — and
        // a tempo-bound encoder pointing at a deleted master must stop being fed.
        override fun speedMasterListChanged() { rebuildAndResync() }
    }

    private val jobs = mutableListOf<Job>()
    private var scalerJob: Job? = null
    private var speedMasterJob: Job? = null
    private var locateJob: Job? = null
    private var publisherScope: CoroutineScope? = null
    private var running = false

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        publisherScope = scope
        attachToFixtures()
        controlStates.start(scope)

        jobs += scope.launch(CoroutineName("FeedbackPublisher-matcher")) {
            deviceMatcher.events.collect { onSurfaceEvent(it) }
        }
        jobs += scope.launch(CoroutineName("FeedbackPublisher-bindings")) {
            bindingService.changes.collect { rebuildAndResync() }
        }
        jobs += scope.launch(CoroutineName("FeedbackPublisher-banks")) {
            bankState.changes.collect { onBankChanged(it) }
        }
        jobs += scope.launch(CoroutineName("FeedbackPublisher-flash")) {
            flashTracker.changes.collect { onFlashChanged(it) }
        }
        deskSelection?.let { selection ->
            // A StateFlow replays its current value on subscribe: the first collect is a
            // rebuild the start-up path has already done, and is harmless.
            jobs += scope.launch(CoroutineName("FeedbackPublisher-selection")) {
                selection.targets.collect { rebuildAndResync() }
            }
        }
        subscribeScaler(scope)
        subscribeSpeedMasters(scope)
        subscribeLocate(scope)
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

    /**
     * (Re)subscribe to the current show's speed-master bank, for the same project-switch
     * reason as [subscribeScaler]. Only the changed master's controls are re-fed: a tap
     * arrives at operator rate but there is no reason to rewrite every tempo encoder.
     */
    private fun subscribeSpeedMasters(scope: CoroutineScope) {
        speedMasterJob?.cancel()
        val bank = try {
            speedMasterBankProvider?.invoke()
        } catch (_: Exception) {
            null
        } ?: return
        speedMasterJob = bank.changes
            .onEach { change ->
                resyncSpeedMasterEntries(
                    index.get().speedMasterEntries,
                    changedUuid = change.uuid,
                    onlyChanged = true,
                )
            }
            .launchIn(scope)
    }

    /** (Re)subscribe to the current show's locate state, per show like [subscribeScaler]. */
    private fun subscribeLocate(scope: CoroutineScope) {
        locateJob?.cancel()
        val manager = try {
            locateManagerProvider?.invoke()
        } catch (_: Exception) {
            null
        } ?: return
        locateJob = manager.activeTargets
            .onEach { located -> resyncLocateLeds(located) }
            .launchIn(scope)
    }

    fun stop() {
        running = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        scalerJob?.cancel()
        scalerJob = null
        speedMasterJob?.cancel()
        speedMasterJob = null
        locateJob?.cancel()
        locateJob = null
        controlStates.stop()
        publisherScope = null
        detachFromFixtures()
    }

    fun onProjectChanged() {
        detachFromFixtures()
        touchState.clearAll()
        takeover.clearAll()
        attachToFixtures()
        rebuildIndex()
        // Re-subscribe to the new show's scaler facade — the previous subscription
        // observes the stale facade (its holder is preserved, but the facade itself is
        // re-created on project switch).
        publisherScope?.let {
            subscribeScaler(it)
            subscribeSpeedMasters(it)
            subscribeLocate(it)
        }
        // Push a full resync for every currently-attached device so the new show's logical
        // values land on the hardware.
        for (displayKey in deviceMatcher.attached.value.keys) {
            sendFullResync(displayKey, rearmPickup = true)
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
        controlStates.setTouched(displayKey, controlId, down)
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
        val on = ledOn(entry) ?: run {
            logger.debug("onButtonRelease: unexpected target {} for led entry", entry.binding.target::class.simpleName)
            return
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
    ): Boolean {
        // The physical position is a fact about the hardware whether or not the move is
        // accepted — it is what the pickup indicator is drawn beside.
        controlStates.setPhysical(displayKey, controlId, value7Bit.toInt())
        return takeover.acceptInboundFader(
            displayKey, controlId, value7Bit, effectivePolicyFor(deviceTypeKey, controlId),
        )
    }

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
                sendFullResync(event.handle.displayKey, rearmPickup = true)
            }
            is DeviceMatcher.SurfaceEvent.DeviceDetached -> {
                touchState.clearDevice(event.handle.displayKey)
                takeover.clearDevice(event.handle.displayKey)
                controlStates.remove(event.handle.displayKey)
                rebuildIndex()
            }
            is DeviceMatcher.SurfaceEvent.UnmatchedDeviceConnected -> Unit
        }
    }

    private fun onBankChanged(change: ActiveBankState.BankChange) {
        rebuildIndex()
        for ((displayKey, attached) in deviceMatcher.attached.value) {
            if (attached.typeKey != change.deviceTypeKey) continue
            sendFullResync(displayKey, rearmPickup = true)
        }
    }

    /**
     * A binding, fixture, selection or master-list change: the index is stale and every
     * attached device is re-fed from it, **without** re-arming pickup — the operator's fader
     * has not moved, so a non-motor fader only re-enters pickup if its value diverged
     * ([SoftTakeoverStateMachine.setLogical]'s own rule). Attach, bank and project changes use
     * [sendFullResync] with `rearmPickup = true` because there the physical position is stale.
     */
    private fun rebuildAndResync() {
        rebuildIndex()
        for (displayKey in deviceMatcher.attached.value.keys) {
            sendFullResync(displayKey, rearmPickup = false)
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

    private fun resyncLocateLeds(located: Set<TargetRef>) {
        val idx = index.get()
        if (idx.locateLeds.isEmpty()) return
        val on = selectionLocated(located)
        for (entry in idx.locateLeds) sendLed(entry, on)
    }

    private fun onChannelsChanged(universe: Universe, changes: Map<Int, UByte>) {
        val byChannel = index.get().byChannel
        if (byChannel.isEmpty()) return
        // A multi-channel entry is indexed under each of its channels; when a tick moves several
        // of them at once, feed it once — the value is the same computation either way, and the
        // tracker must not see N writes for one move.
        // By identity: the entry is a data class over a binding, a descriptor and a channel
        // list, and structural hashing of all that per channel per tick is what this path exists
        // to avoid. One index build yields one instance per entry, so identity is exact.
        var seen: MutableSet<ContinuousEntry>? = null
        for (channel in changes.keys) {
            val entries = byChannel[packChannelKey(universe.universe, channel)] ?: continue
            for (entry in entries) {
                if (entry.channels.size > 1) {
                    val s = seen ?: Collections.newSetFromMap(IdentityHashMap<ContinuousEntry, Boolean>()).also { seen = it }
                    if (!s.add(entry)) continue
                }
                applyContinuous(entry, computeValue7Bit(entry), rearmPickup = false)
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
        val ledsByDisplay = HashMap<String, MutableList<LedEntry>>()
        val flashByBindingId = HashMap<Int, LedEntry>()
        val blackoutLeds = mutableListOf<LedEntry>()
        val grandMasterLeds = mutableListOf<LedEntry>()
        val selectLeds = mutableListOf<LedEntry>()
        val locateLeds = mutableListOf<LedEntry>()
        val speedMasterEntries = mutableListOf<SpeedMasterEntry>()
        // Expanded once per rebuild, not once per bound control.
        val selectedHeads = deskSelection?.coverage().orEmpty()

        for ((displayKey, a) in attached) {
            val profile = profilesByKey[a.typeKey] ?: continue
            val bank = bankState.bankFor(a.typeKey)
            for (control in profile.controls) {
                val binding = bindingService.resolve(projectId, a.typeKey, control.controlId, bank) ?: continue
                if (control is FaderDescriptor || control is EncoderDescriptor) {
                    val classDefault = if (control is FaderDescriptor && !control.hasMotor) {
                        BindingTakeoverPolicy.PICKUP
                    } else {
                        BindingTakeoverPolicy.IMMEDIATE
                    }
                    (binding.target as? BindingTarget.SpeedMasterBpm)?.let { tempo ->
                        speedMasterEntries += SpeedMasterEntry(
                            displayKey = displayKey,
                            control = control,
                            masterUuid = tempo.masterUuid?.let { speedMasterUuidOrNull(it) },
                            minBpm = tempo.minBpm,
                            maxBpm = tempo.maxBpm,
                            policy = binding.takeoverPolicy ?: classDefault,
                        )
                    }
                    val channels = fixtures?.let { findChannels(it, binding.target, selectedHeads) }
                    if (channels != null) {
                        val entry = ContinuousEntry(
                            displayKey = displayKey,
                            deviceTypeKey = a.typeKey,
                            control = control,
                            binding = binding,
                            channels = channels,
                            policy = binding.takeoverPolicy ?: classDefault,
                        )
                        for (pc in channels) {
                            byChannel.getOrPut(packChannelKey(pc.universe.universe, pc.channel)) { mutableListOf() }
                                .add(entry)
                        }
                        continuousByDisplay.getOrPut(displayKey) { mutableListOf() }.add(entry)
                    }
                }
                if (control is ButtonDescriptor && control.ledFeedback != LedFeedback.NONE) {
                    val target = binding.target
                    val entry = LedEntry(displayKey, control, binding)
                    val listed = when (target) {
                        is BindingTarget.Flash -> { flashByBindingId[binding.id] = entry; true }
                        is BindingTarget.Blackout -> { blackoutLeds += entry; true }
                        is BindingTarget.GrandMasterToggle -> { grandMasterLeds += entry; true }
                        is BindingTarget.SelectTarget -> { selectLeds += entry; true }
                        is BindingTarget.LocateSelection -> { locateLeds += entry; true }
                        else -> false
                    }
                    if (listed) ledsByDisplay.getOrPut(displayKey) { mutableListOf() }.add(entry)
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        index.set(
            Index(
                byChannel = byChannel as Map<Long, List<ContinuousEntry>>,
                continuousByDisplay = continuousByDisplay as Map<String, List<ContinuousEntry>>,
                ledsByDisplay = ledsByDisplay as Map<String, List<LedEntry>>,
                flashByBindingId = flashByBindingId,
                blackoutLeds = blackoutLeds,
                grandMasterLeds = grandMasterLeds,
                selectLeds = selectLeds,
                locateLeds = locateLeds,
                speedMasterEntries = speedMasterEntries,
            )
        )
        // Seed takeover state so a PICKUP encoder knows where the tempo already sits before
        // the operator's first touch, rather than only after the first tempo change.
        resyncSpeedMasterEntries(speedMasterEntries)
    }

    /**
     * The channels a continuous target's feedback reads — null when the target is not a DMX
     * property or does not resolve, so no entry is built. A selection target always resolves
     * (to nothing when nothing is selected), because "no selection" is a state the control
     * shows rather than the absence of a binding.
     */
    private fun findChannels(
        fixtures: Fixtures,
        target: BindingTarget,
        selectedHeads: List<CueTargetDto>,
    ): List<PropertyChannelResolver.PropertyChannel>? = when (target) {
        is BindingTarget.FixtureProperty -> {
            val fixture = try {
                fixtures.untypedFixture(target.fixtureKey)
            } catch (_: Exception) { null }
            fixture?.let { PropertyChannelResolver.describeFixtureProperty(it, target.propertyName).firstOrNull() }
                ?.let { listOf(it) }
        }
        is BindingTarget.GroupProperty -> {
            val group = try {
                fixtures.untypedGroup(target.groupName)
            } catch (_: Exception) { null }
            group?.fixtures?.filterIsInstance<Fixture>()
                ?.mapNotNull { PropertyChannelResolver.describeFixtureProperty(it, target.propertyName).firstOrNull() }
                ?.takeIf { it.isNotEmpty() }
        }
        is BindingTarget.SelectionProperty -> selectedHeads.mapNotNull { head ->
            if (head.type != TargetRef.Fixture.TYPE) return@mapNotNull null
            val fixture = try {
                fixtures.untypedFixture(head.key)
            } catch (_: Exception) { null }
            fixture?.let { PropertyChannelResolver.describeFixtureProperty(it, target.propertyName).firstOrNull() }
        }
        else -> null
    }

    /** Resync one specific control — motor catch-up on touch-off. */
    private fun resyncControl(displayKey: String, controlId: String) {
        val entries = index.get().continuousByDisplay[displayKey] ?: return
        for (entry in entries) {
            if (entry.control.controlId != controlId) continue
            applyContinuous(entry, computeValue7Bit(entry), rearmPickup = false)
        }
    }

    /**
     * Full resync: drive motors / rings / LEDs for every bound control on a device, and publish
     * the device's whole [ControlStateTracker] snapshot afterwards. With [rearmPickup] every
     * PICKUP fader is forced back into pickup (the physical position is stale: attach, bank
     * change, project change); without it takeover follows [SoftTakeoverStateMachine.setLogical]'s
     * divergence rule.
     */
    private fun sendFullResync(displayKey: String, rearmPickup: Boolean) {
        val profile = deviceMatcher.attached.value[displayKey]?.typeKey
            ?.let { typeKey -> types().firstOrNull { it.typeKey == typeKey } }
        // Seed every profile control at "unbound" first, so a control that lost its binding
        // reads as such in the snapshot rather than keeping its last value.
        if (profile != null) controlStates.reset(displayKey, profile.controls.map { it.controlId })
        val idx = index.get()
        // DMX-backed entries need the show; tempo entries and LEDs do not, so a surface that
        // attaches before the show is up still gets its bank, scaler and select state.
        if (currentFixtures != null) {
            for (entry in idx.continuousByDisplay[displayKey].orEmpty()) {
                applyContinuous(entry, computeValue7Bit(entry), rearmPickup)
            }
        }
        // Tempo-bound encoders are not ContinuousEntries (they have no channel), so the reset
        // above wiped their rows: re-feed them here or the stream reports a lit ring as unbound.
        resyncSpeedMasterEntries(idx.speedMasterEntries.filter { it.displayKey == displayKey })
        for (entry in idx.ledsByDisplay[displayKey].orEmpty()) {
            sendLed(entry, ledOn(entry) ?: false)
        }
        controlStates.publishSnapshot(displayKey)
    }

    /**
     * The LED state a discrete binding should show right now, read from *current state* rather
     * than edge events — so a Flash held at the moment a device attaches lights up immediately.
     * Null for a target that carries no LED semantics.
     */
    private fun ledOn(entry: LedEntry): Boolean? = when (val target = entry.binding.target) {
        is BindingTarget.Flash -> flashTracker.isActive(entry.binding.id)
        // The scaler facade is per show; before the show is up there is nothing to read.
        is BindingTarget.Blackout -> runCatching { globalScalerStateProvider().blackoutEnabled.value }.getOrDefault(false)
        is BindingTarget.GrandMasterToggle -> runCatching { globalScalerStateProvider().grandMasterEnabled.value }.getOrDefault(false)
        is BindingTarget.SelectTarget -> deskSelection?.covers(target.target) ?: false
        is BindingTarget.LocateSelection -> selectionLocated(
            runCatching { locateManagerProvider?.invoke()?.activeTargets?.value }.getOrNull().orEmpty(),
        )
        else -> null
    }

    /** True when the selection is non-empty and every target in it is located. */
    private fun selectionLocated(located: Set<TargetRef>): Boolean {
        val targets = deskSelection?.targets?.value.orEmpty()
        if (targets.isEmpty()) return false
        return targets.all { TargetRef.ofOrNull(it.type, it.key)?.let { ref -> ref in located } ?: false }
    }

    /**
     * Feedback position for a bound continuous control: always the live composed DMX value on
     * the binding's channels, scaled through each channel's own `min..max`. Until sweep item D1
     * there was a second source — an open `cueEdit` session's own Layer 4 assignment took
     * precedence, so a fader showed the cue being edited rather than the stage. With that
     * family retired a cue is read-only from a surface, and the stage is the only thing
     * feedback can mean.
     *
     * Null when the channels disagree (a divergent group, a mixed selection) or when there are
     * none (nothing selected): the control has no one value to show.
     */
    private fun computeValue7Bit(entry: ContinuousEntry): UByte? {
        val fixtures = currentFixtures ?: return null
        if (entry.channels.isEmpty()) return null
        var common: UByte? = null
        for (pc in entry.channels) {
            val controller: DmxController = try {
                fixtures.controller(pc.universe)
            } catch (_: Exception) {
                return null
            }
            val value = PropertyChannelResolver.scaleWithinRangeTo7Bit(controller.getValue(pc.channel), pc.min, pc.max)
            if (common == null) common = value
            else if (common != value) return null
        }
        return common
    }

    /**
     * One continuous entry, one value, everywhere it has to land: the hardware, the takeover
     * machine and the control-state tracker. Null is the mixed / no-selection state — nothing
     * to the motor, the ring darkened, takeover disarmed so the next move writes through.
     */
    private fun applyContinuous(entry: ContinuousEntry, value7Bit: UByte?, rearmPickup: Boolean) {
        val displayKey = entry.displayKey
        val controlId = entry.control.controlId
        if (value7Bit == null) {
            if (logger.isDebugEnabled) {
                logger.debug(
                    "surface-out: control={} channels={} -> mixed / none, ring off",
                    controlId, entry.channels.size,
                )
            }
            sendRingOff(displayKey, entry.control)
            takeover.disarm(displayKey, controlId)
            return
        }
        sendContinuousFeedback(entry, value7Bit)
        if (rearmPickup && entry.policy == BindingTakeoverPolicy.PICKUP) {
            takeover.forcePickup(displayKey, controlId, value7Bit)
        } else {
            takeover.setLogical(displayKey, controlId, value7Bit, entry.policy)
        }
    }

    /**
     * Resolve a control's feedback CC and write [value7Bit] to it. Shared by the DMX-backed
     * and tempo-backed paths, which differ only in where the value comes from. The tracker is
     * written **before** the early returns: a non-motor fader is never driven, and a touched
     * motor is spared, but both still have a value the screen shows.
     */
    private fun sendControlFeedback(displayKey: String, control: ControlDescriptor, value7Bit: UByte) {
        controlStates.setValue(displayKey, control.controlId, value7Bit.toInt(), ringFor(control, lit = true))
        val controller = controllerLookup(displayKey) ?: return
        val (channel, cc) = when (control) {
            is FaderDescriptor -> {
                if (!control.hasMotor) return  // Non-motor: no feedback, just takeover tracking.
                if (touchState.isTouched(displayKey, control.controlId)) return
                control.channel to (control.motorCc ?: control.cc)
            }
            is EncoderDescriptor -> control.channel to (control.ringCc ?: return)
            else -> return
        }
        latencyTracker.measure(MidiLatencyStage.EGRESS_MOTOR) {
            controller.sendFeedback(MidiFeedbackMessage.ControlChangeFeedback(channel, cc, value7Bit))
        }
    }

    /** The no-value state: the tracker reads null, and an encoder ring is darkened. */
    private fun sendRingOff(displayKey: String, control: ControlDescriptor) {
        controlStates.setValue(displayKey, control.controlId, null, ringFor(control, lit = false))
        if (control !is EncoderDescriptor) return
        val cc = control.ringCc ?: return
        val off = ringOffValue(control.ringStyle) ?: return
        val controller = controllerLookup(displayKey) ?: return
        latencyTracker.measure(MidiLatencyStage.EGRESS_MOTOR) {
            controller.sendFeedback(MidiFeedbackMessage.ControlChangeFeedback(control.channel, cc, off))
        }
    }

    private fun ringFor(control: ControlDescriptor, lit: Boolean): RingState = when {
        control !is EncoderDescriptor || control.ringCc == null || control.ringStyle == EncoderRingStyle.NONE ->
            RingState.NONE
        lit -> RingState.ON
        else -> RingState.OFF
    }

    /**
     * Push the current tempo of each entry's master onto its control, and record it as the
     * logical value so PICKUP can arm. Called on every bank change and after each rebuild.
     */
    private fun resyncSpeedMasterEntries(entries: List<SpeedMasterEntry>, changedUuid: UUID? = null, onlyChanged: Boolean = false) {
        if (entries.isEmpty()) return
        val bank = try {
            speedMasterBankProvider?.invoke()
        } catch (_: Exception) {
            null
        } ?: return
        val states = bank.masterStates()
        // A null uuid means master 1, and must be resolved by *index* rather than by
        // matching uuids: `load()` replaces the synthetic slot-0 entry with the persisted
        // row, so `masterStates()[0].uuid` is a real uuid from then on and `uuid == null`
        // matches nothing. Comparing uuids directly here meant the default (unkeyed,
        // master-1) binding — the common case — got no feedback and never armed PICKUP.
        fun stateFor(masterUuid: UUID?) =
            if (masterUuid == null) states.firstOrNull { it.index == 1 }
            else states.firstOrNull { it.uuid == masterUuid }

        val changedState = if (onlyChanged) stateFor(changedUuid) else null
        for (entry in entries) {
            val state = stateFor(entry.masterUuid) ?: continue
            // Compare resolved masters, not raw uuids, so a null-uuid entry still matches a
            // change event that carries master 1's real uuid.
            if (onlyChanged && state.index != changedState?.index) continue
            val bpm = state.bpm
            val span = entry.maxBpm - entry.minBpm
            val value7Bit = if (span <= 0.0) 0u else {
                (((bpm - entry.minBpm) / span) * 127.0).roundToInt().coerceIn(0, 127).toUByte()
            }
            sendControlFeedback(entry.displayKey, entry.control, value7Bit)
            takeover.setLogical(entry.displayKey, entry.control.controlId, value7Bit, entry.policy)
        }
    }

    private fun sendContinuousFeedback(entry: ContinuousEntry, value7Bit: UByte) {
        if (logger.isDebugEnabled) {
            val pc = entry.channels.firstOrNull()
            val dmx = pc?.let { runCatching { currentFixtures?.controller(it.universe)?.getValue(it.channel) }.getOrNull() }
            logger.debug(
                "surface-out: control={} channels={} dmxCh={} dmx={} min={} max={} -> value7Bit={}",
                entry.control.controlId, entry.channels.size, pc?.channel, dmx?.toInt(),
                pc?.min?.toInt(), pc?.max?.toInt(), value7Bit.toInt(),
            )
        }
        sendControlFeedback(entry.displayKey, entry.control, value7Bit)
    }

    private fun sendLed(entry: LedEntry, on: Boolean) {
        controlStates.setLed(entry.displayKey, entry.control.controlId, if (on) LedState.ON else LedState.OFF)
        val controller = controllerLookup(entry.displayKey) ?: return
        val msg = if (on) {
            MidiFeedbackMessage.NoteOnFeedback(entry.control.channel, entry.control.note, 127u)
        } else {
            MidiFeedbackMessage.NoteOffFeedback(entry.control.channel, entry.control.note, 0u)
        }
        latencyTracker.measure(MidiLatencyStage.EGRESS_LED) { controller.sendFeedback(msg) }
    }
}
