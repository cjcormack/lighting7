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
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
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
        val ledsByDisplay: Map<String, List<LedEntry>>,
        val flashByBindingId: Map<Int, LedEntry>,
        val blackoutLeds: List<LedEntry>,
        val grandMasterLeds: List<LedEntry>,
    ) {
        companion object {
            val EMPTY = Index(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyList())
        }
    }

    private val index = AtomicReference(Index.EMPTY)

    @Volatile
    private var currentFixtures: Fixtures? = null

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
    private var running = false

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
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
        val scaler = try {
            globalScalerStateProvider()
        } catch (_: Exception) {
            null
        }
        if (scaler != null) {
            jobs += combine(scaler.blackoutEnabled, scaler.grandMasterEnabled) { b, g -> b to g }
                .onEach { (blackout, grandMaster) -> onScalerChanged(blackout, grandMaster) }
                .launchIn(scope)
        }
    }

    fun stop() {
        running = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        detachFromFixtures()
    }

    fun onProjectChanged() {
        detachFromFixtures()
        touchState.clearAll()
        takeover.clearAll()
        attachToFixtures()
        rebuildIndex()
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
