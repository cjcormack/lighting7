package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fx.AppliedSource
import uk.me.cormack.lighting7.fx.CueRunState
import uk.me.cormack.lighting7.fx.FxTarget
import uk.me.cormack.lighting7.fx.LayerResolver
import uk.me.cormack.lighting7.fx.ProgrammerLayerStack
import uk.me.cormack.lighting7.fx.ProgrammerStore
import uk.me.cormack.lighting7.fx.SpeedMasterBank
import uk.me.cormack.lighting7.fx.StackFxAmounts
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.perf.MidiLatencyStage
import uk.me.cormack.lighting7.perf.MidiLatencyTracker
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.show.FixturesChangeListener
import uk.me.cormack.lighting7.show.LocateManager
import uk.me.cormack.lighting7.state.BuskPageState
import uk.me.cormack.lighting7.state.DeskSelection
import uk.me.cormack.lighting7.state.WindowRegistry
import java.util.UUID
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
     * Decide whether an inbound move should be taken, by the jump rule ([JumpPolicy]). Returns
     * false to drop it; the publisher shows a driven control its value again itself.
     */
    fun acceptInboundFader(
        displayKey: String,
        deviceTypeKey: String,
        controlId: String,
        value7Bit: UByte,
    ): Boolean

    /**
     * Notify that a button or an encoder's push has just been released. The publisher re-asserts
     * its LED, bypassing delta suppression, for surfaces (notably the X-Touch Compact in Momentary
     * mode) that drive the LED off on release even when we've told them to keep it on.
     */
    fun onButtonRelease(displayKey: String, controlId: String, part: ControlPart = ControlPart.MAIN) {}
}

/**
 * Drives MIDI feedback to attached control surfaces — motor positions, LED rings and button LEDs —
 * from what each bound function says it shows (surface-setup plan P3): a button function's
 * [Does.led], a continuous function's [Does.read].
 *
 * ## The index
 *
 * One [Entry] per bound control part on each attached device, at the device's own layer and encoder
 * page (D16), indexed by the [Signal]s its light or readout follows and by its control part. It is
 * rebuilt on a binding, device, layer, encoder-page, fixture or record-list change, and on nothing
 * else: a selection change re-reads the selection's entries, whose heads are expanded again on the
 * next read.
 *
 * ## The readback, off the transmit thread
 *
 * A fixture control shows what Local holds for its key over the cue layer, without FX
 * (`LayerResolver.localOver`, D6): the staged value in Blind, beneath a held flash, blind to park.
 * None of that is on a DMX channel, so the publisher does not listen to the transmit path at all. A
 * loop polls every [pollIntervalMs] — the programmer's `revision` and Blind and the cue-layer snapshot's
 * identity — and re-reads the [Signal.PROGRAMMER] entries when one moved. Every other signal is a
 * subscription.
 *
 * ## One value, everywhere it lands
 *
 * A reading goes through the jump rule ([SoftTakeoverStateMachine.setLogical]), then to the
 * `surfaceControls.*` stream's [ControlStateTracker] — written before any early return, so the
 * tracker records what a non-motor fader would have been told and what a touched motor was spared —
 * and then to the hardware. A mixed or absent value darkens a ring and drives no motor.
 *
 * ## Lifecycle
 *
 * [start] launches the subscriptions and the poll; [stop] cancels them. Project switches re-attach
 * internally via [onProjectChanged].
 */
class SurfaceFeedbackPublisher(
    private val deviceMatcher: DeviceMatcher,
    private val controllerLookup: (String) -> MidiController?,
    private val bindingService: ControlSurfaceBindingService,
    private val bankState: ActiveBankState,
    private val encoderBankState: EncoderBankState,
    private val holds: SurfaceHolds,
    private val projectIdProvider: () -> Int,
    private val fixturesProvider: () -> Fixtures,
    private val globalScalerStateProvider: () -> GlobalScalerState,
    /** The live show's programmer store, polled for its epoch and Blind. A provider: it is per show. */
    private val programmerStoreProvider: (() -> ProgrammerStore)? = null,
    /** The live show's layer resolver: the readback's `localOver`, and the cue layer the poll watches. */
    private val layerResolverProvider: (() -> LayerResolver)? = null,
    private val speedMasterBankProvider: (() -> SpeedMasterBank)? = null,
    /** The desk selection. A value: it is State-scoped and survives a project switch. Null disables the selection's arms. */
    private val deskSelection: DeskSelection? = null,
    private val locateManagerProvider: (() -> LocateManager)? = null,
    /** The programmer's layer stack, whose applied state the record LEDs read, as a busk pad's ring does. */
    private val programmerLayerStackProvider: (() -> ProgrammerLayerStack)? = null,
    private val buskPageState: BuskPageState? = null,
    /** The desk's windows, for the window-addressed bindings' health. Null disables it. */
    private val windowRegistry: WindowRegistry? = null,
    /** Whether a cue is held loose, for a cue's and a cue pad's LED (cue model plan D11). */
    private val cueLooseHeldProvider: ((cueId: Int) -> Boolean)? = null,
    /** A cue uuid's id in the live project, for a cue's LED; read once per index. */
    private val cueIdResolver: ((cue: UUID) -> Int?)? = null,
    /** Whether a cue stack runs, for a GO's and a stack pad's LED. */
    private val stackRunningProvider: ((stackId: Int) -> Boolean)? = null,
    /**
     * What each busk pad presses and what id each busk page has. Called at most once per index, and
     * only when a bound control on an attached device names a pad or a page.
     */
    private val buskRefsProvider: ((projectId: Int) -> BuskRefs)? = null,
    private val stackFxAmountsProvider: (() -> StackFxAmounts)? = null,
    /** A stack uuid's id in the live show — the input path's own lookup — read once per index. */
    private val stackIdResolver: ((stackUuid: String) -> Int?)? = null,
    private val selectionFan: FanRing? = null,
    private val fxSpeed: FxSpeedRing? = null,
    /** The live show's running-FX list, which an FX speed control's value follows. */
    private val fxStateProvider: (() -> Flow<*>)? = null,
    /** What a live job follows; a change re-derives the job's controls without re-arming pickup. Null disables it. */
    private val jobLive: SurfaceJobLive? = null,
    private val types: () -> List<ControlSurfaceRegistry.DeviceTypeInfo> = { ControlSurfaceRegistry.allTypes },
    /** How often the readback polls the programmer: about 50 Hz. */
    private val pollIntervalMs: Long = 20,
    val touchState: TouchStateTracker = TouchStateTracker(),
    val takeover: SoftTakeoverStateMachine = SoftTakeoverStateMachine(),
    /** The `surfaceControls.*` stream's store — see [ControlStateTracker]. */
    val controlStates: ControlStateTracker = ControlStateTracker(),
    private val latencyTracker: MidiLatencyTracker = MidiLatencyTracker(),
) : SurfaceFeedbackHooks {

    companion object {
        private val logger = LoggerFactory.getLogger(SurfaceFeedbackPublisher::class.java)

        /**
         * The ring CC value that darkens an encoder ring, per style. `0` for every style the
         * X-Touch Compact can be put in is the working assumption until the rig confirms it
         * (`FU-MANUAL-SURFACE-SETUP-S2`): if the desk lights the first dot on `0`, this is the one
         * constant to change. The tracker's [RingState.OFF] is the truth the view reads regardless.
         */
        internal fun ringOffValue(style: EncoderRingStyle): UByte? = when (style) {
            EncoderRingStyle.NONE -> null
            EncoderRingStyle.SINGLE_DOT, EncoderRingStyle.FAN, EncoderRingStyle.PAN -> 0u
        }
    }

    /**
     * One bound control part. Exactly one of [light] and [readout] is set: a button or an encoder's
     * push has a light, a fader or an encoder's turn a readout. [ledChannel] and [ledNote] are where a
     * light is sent; [jump] is a readout's rule for inbound moves.
     */
    private class Entry(
        val displayKey: String,
        val control: ControlDescriptor,
        val part: ControlPart,
        val binding: ControlSurfaceBindingService.ResolvedBinding,
        val light: Led.Light? = null,
        val readout: Readout? = null,
        val jump: JumpPolicy = JumpPolicy.JUMP,
        val ledChannel: Int = 0,
        val ledNote: Int = 0,
    ) {
        val controlId: String get() = control.controlId
        val signals: Set<Signal> get() = light?.signals ?: readout?.signals.orEmpty()
    }

    private class Index(
        val bySignal: Map<Signal, List<Entry>>,
        val byDisplay: Map<String, List<Entry>>,
        val byPart: Map<SurfaceDispatch.HoldKey, Entry>,
    ) {
        companion object {
            val EMPTY = Index(emptyMap(), emptyMap(), emptyMap())
        }
    }

    private val index = AtomicReference(Index.EMPTY)

    @Volatile
    private var currentFixtures: Fixtures? = null

    private val fixtureListener = object : FixturesChangeListener {
        override fun fixturesChanged() { rebuildAndResync() }
        // A create or delete changes which master uuids resolve, and so the health a binding reads.
        override fun speedMasterListChanged() { rebuildAndResync() }
        // A pad's resolved record is baked into its light, so these stale the index: a deleted Look,
        // palette or pad must stop lighting its button, and a layout write moves a pad between banks.
        override fun lookListChanged() { rebuildAndResync() }
        override fun paletteListChanged() { rebuildAndResync() }
        override fun buskLayoutChanged(pageIds: List<Int>) { rebuildAndResync() }
        // Discrete: these fire on transitions, not per fade frame.
        override fun cueRunStateChanged(runState: CueRunState) { refresh(Signal.STACK_RUN) }
        override fun cueHoldsChanged() { refresh(Signal.CUE_HOLDS) }
    }

    private val jobs = mutableListOf<Job>()
    private val showJobs = mutableListOf<Job>()
    private var publisherScope: CoroutineScope? = null
    private var running = false

    /**
     * Held by [start], [stop] and [onProjectChanged]'s re-subscribe: a project switch's collector and
     * shutdown run on different threads, and both walk the job lists.
     */
    private val lifecycle = Any()

    fun start(scope: CoroutineScope): Unit = synchronized(lifecycle) {
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
            bankState.changes.collect { deviceChanged(it.displayKey) }
        }
        // The encoder page moved: every encoder following it on that device now means another
        // attribute, so the index is stale and pickup re-arms — the meaning moved under the operator's hand.
        jobs += scope.launch(CoroutineName("FeedbackPublisher-encoder-banks")) {
            encoderBankState.changes.collect { deviceChanged(it.displayKey) }
        }
        jobs += scope.launch(CoroutineName("FeedbackPublisher-holds")) {
            holds.changes.collect { change -> index.get().byPart[change.key]?.let { apply(it, Pass(), rearm = false) } }
        }
        deskSelection?.let { selection ->
            // Membership only: nothing here reads the attribute mask.
            jobs += scope.launch(CoroutineName("FeedbackPublisher-selection")) {
                selection.state.map { it.targets }.distinctUntilChanged().collect { refresh(Signal.SELECTION) }
            }
        }
        windowRegistry?.let { registry ->
            // By name set, not by row: a re-announce that moves a window's view is not a health event.
            jobs += scope.launch(CoroutineName("FeedbackPublisher-windows")) {
                registry.windows
                    .map { rows -> rows.mapTo(HashSet()) { it.name } }
                    .distinctUntilChanged()
                    .collect { bindingService.invalidateHealth(projectIdProvider()) }
            }
        }
        buskPageState?.let { pages ->
            jobs += pages.pageId.onEach { refresh(Signal.BUSK_PAGE) }.launchIn(scope)
        }
        jobLive?.let { live -> jobs += live.changes.onEach { rederiveJobs() }.launchIn(scope) }
        // Machine-scoped like the selection they read, so subscribed once rather than per show.
        selectionFan?.let { fan -> jobs += fan.shown.onEach { refresh(Signal.FAN) }.launchIn(scope) }
        fxSpeed?.let { speed -> jobs += speed.shown.onEach { refresh(Signal.FX_SPEED_SHOWN, force = true) }.launchIn(scope) }
        jobs += scope.launch(CoroutineName("FeedbackPublisher-poll")) {
            while (isActive) {
                delay(pollIntervalMs)
                // The readback's only programmer signal: a throw here must not end it.
                try {
                    poll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Surface feedback poll failed: {}", e.toString())
                }
            }
        }
        subscribeShow(scope)
    }

    /**
     * (Re)subscribe to the live show's own state: the scaler, the speed masters, the stack FX amounts,
     * the running-FX list, locate and the layer stack. Each is per show, so a project switch
     * subscribes again rather than observing the previous show's.
     */
    private fun subscribeShow(scope: CoroutineScope) {
        showJobs.forEach { it.cancel() }
        showJobs.clear()
        fun <T> provided(provider: (() -> T)?): T? = try {
            provider?.invoke()
        } catch (_: Exception) {
            null
        }
        provided { globalScalerStateProvider() }?.let { scaler ->
            showJobs += combine(scaler.blackoutEnabled, scaler.grandMasterEnabled) { b, g -> b to g }
                .onEach { refresh(Signal.SCALER) }
                .launchIn(scope)
            // The level apart from the LEDs above, which only move when it crosses 0: a fader dragged
            // from the browser moves it at frame rate, and only the faders need to follow.
            showJobs += scaler.grandMasterLevel.onEach { refresh(Signal.GRAND_MASTER) }.launchIn(scope)
        }
        provided(speedMasterBankProvider)?.let { bank -> showJobs += bank.changes.onEach { refresh(Signal.TEMPO) }.launchIn(scope) }
        provided(stackFxAmountsProvider)?.let { amounts ->
            showJobs += amounts.state.onEach { refresh(Signal.STACK_FX) }.launchIn(scope)
        }
        if (fxSpeed != null) {
            provided(fxStateProvider)?.let { flow -> showJobs += flow.onEach { refresh(Signal.FX_LIST) }.launchIn(scope) }
        }
        provided(locateManagerProvider)?.let { manager ->
            showJobs += manager.activeTargets.onEach { refresh(Signal.LOCATE) }.launchIn(scope)
        }
        provided(programmerLayerStackProvider)?.let { stack ->
            showJobs += merge(stack.layersFlow.map { }, stack.referencesRevision.map { })
                .onEach { refresh(Signal.LAYERS) }
                .launchIn(scope)
        }
    }

    fun stop(): Unit = synchronized(lifecycle) {
        running = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        showJobs.forEach { it.cancel() }
        showJobs.clear()
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
        synchronized(lifecycle) {
            // A switch that lands after shutdown subscribes nothing.
            if (!running) return
            publisherScope?.let(::subscribeShow)
        }
        for (displayKey in deviceMatcher.attached.value.keys) sendFullResync(displayKey, rearmPickup = true)
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
        // Motor catch-up: whatever the function reads back as now, drive to it.
        if (!down) index.get().byPart[SurfaceDispatch.HoldKey(displayKey, controlId, ControlPart.MAIN)]?.let { apply(it, Pass(), rearm = false) }
    }

    override fun onButtonRelease(displayKey: String, controlId: String, part: ControlPart) {
        val entry = index.get().byPart[SurfaceDispatch.HoldKey(displayKey, controlId, part)]?.takeIf { it.light != null } ?: return
        controllerLookup(displayKey)?.invalidateFeedbackCache(MidiControlKey(entry.ledChannel, MidiControlKey.Type.NOTE, entry.ledNote))
        apply(entry, Pass(), rearm = false)
    }

    override fun acceptInboundFader(
        displayKey: String,
        deviceTypeKey: String,
        controlId: String,
        value7Bit: UByte,
    ): Boolean {
        // The physical position is a fact about the hardware whether or not the move is taken — it
        // is what the pickup indicator is drawn beside.
        controlStates.setPhysical(displayKey, controlId, value7Bit.toInt())
        val entry = index.get().byPart[SurfaceDispatch.HoldKey(displayKey, controlId, ControlPart.MAIN)]
        val policy = entry?.jump ?: JumpPolicy.JUMP
        val verdict = takeover.acceptInboundFader(
            displayKey, controlId, value7Bit, policy, touched = touchState.isTouched(displayKey, controlId),
        )
        if (verdict == SoftTakeoverStateMachine.Verdict.RESHOW && entry != null) {
            val shown = takeover.stateFor(displayKey, controlId).shown
            if (shown != null) {
                invalidateControlFeedback(displayKey, entry.control)
                sendControlFeedback(displayKey, entry.control, shown)
            }
        }
        return verdict == SoftTakeoverStateMachine.Verdict.ACCEPT
    }

    /** The jump rule a bound continuous control is on, or null when nothing continuous is bound there. */
    internal fun jumpPolicyFor(displayKey: String, controlId: String): JumpPolicy? =
        index.get().byPart[SurfaceDispatch.HoldKey(displayKey, controlId, ControlPart.MAIN)]?.takeIf { it.readout != null }?.jump

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

    /** One device's layer or encoder page moved: its controls mean something else, so pickup re-arms on it alone. */
    private fun deviceChanged(displayKey: String) {
        rebuildIndex()
        if (displayKey in deviceMatcher.attached.value) sendFullResync(displayKey, rearmPickup = true)
    }

    /**
     * The index is stale and every attached device is re-fed from it, **without** re-arming pickup:
     * the operator's faders have not moved, so an undriven one waits only if its value diverged.
     */
    private fun rebuildAndResync() {
        rebuildIndex()
        for (displayKey in deviceMatcher.attached.value.keys) sendFullResync(displayKey, rearmPickup = false)
    }

    /**
     * A live job's state moved (the selection's heads, the busk page's pads): what its controls do has
     * moved with it, so the index is rebuilt and each control a job derives is fed again — **without**
     * re-arming pickup, since the operator's hand has not moved. A part a job derived before and no
     * longer does (a head fewer, a pad fewer) goes dark.
     */
    private fun rederiveJobs() {
        val before = index.get()
        rebuildIndex()
        val after = index.get()
        val pass = Pass()
        for (entry in after.byPart.values) if (entry.binding.job != null) apply(entry, pass, rearm = false)
        for ((key, gone) in before.byPart) {
            if (gone.binding.job == null || key in after.byPart) continue
            if (gone.light != null) sendLed(gone, false) else sendRingOff(gone.displayKey, gone.control)
        }
    }

    /** Re-read every entry following [signal]; [force] sends past the transport's last-sent cache. */
    private fun refresh(signal: Signal, force: Boolean = false) {
        val entries = index.get().bySignal[signal] ?: return
        val pass = Pass()
        for (entry in entries) apply(entry, pass, rearm = false, force = force)
    }

    private var lastRevision = Long.MIN_VALUE
    private var lastBlind: Boolean? = null
    private var lastCueLayer: Any? = null

    /**
     * One tick of the readback's poll: re-read the programmer's entries when the store or the cue layer
     * moved. It watches the store's `revision`, not its `epoch`: a put bumps the epoch before it installs
     * the slot, so a read between the two would see the old value under the new epoch and never read
     * again; the revision moves once more after the install.
     */
    private fun poll() {
        if (index.get().bySignal[Signal.PROGRAMMER].isNullOrEmpty()) return
        val store = runCatching { programmerStoreProvider?.invoke() }.getOrNull() ?: return
        val cueLayer = runCatching { layerResolverProvider?.invoke()?.current }.getOrNull()
        val revision = store.revision
        val blind = store.blind
        if (revision == lastRevision && blind == lastBlind && cueLayer === lastCueLayer) return
        lastRevision = revision
        lastBlind = blind
        lastCueLayer = cueLayer
        refresh(Signal.PROGRAMMER)
    }

    internal fun rebuildIndexForTest() = rebuildIndex()

    /** One poll, as the loop makes it — tests drive the readback without waiting for the loop. */
    internal fun pollForTest() = poll()

    // --- Rebuild & resync ---

    /**
     * What a [FeedbackSite] looks up, for one [rebuildIndex] pass: the record ids and busk refs, each
     * read at most once, and the [Does.Level] heads, expanded once per subject.
     */
    private inner class Lookups(private val projectId: Int) {
        private val refs: BuskRefs by lazy {
            try {
                buskRefsProvider?.invoke(projectId) ?: BuskRefs.EMPTY
            } catch (e: Exception) {
                logger.debug("Busk refs unavailable for project {}: {}", projectId, e.message)
                BuskRefs.EMPTY
            }
        }
        private val stackIds = HashMap<UUID, Int?>()
        private val cueIds = HashMap<UUID, Int?>()
        private val heads = HashMap<Triple<Subject, String, ColourAxis?>, LevelHeads>()

        fun stackId(stack: UUID): Int? = stackIds.getOrPut(stack) {
            runCatching { stackIdResolver?.invoke(stack.toString()) }.getOrNull()
        }

        fun cueId(cue: UUID): Int? = cueIds.getOrPut(cue) { runCatching { cueIdResolver?.invoke(cue) }.getOrNull() }
        fun pad(pad: UUID): PadRef? = refs.pads[pad]
        fun pageId(page: UUID): Int? = refs.pageIds[page]

        fun levelHeads(on: Subject, attribute: String, axis: ColourAxis?): LevelHeads =
            heads.getOrPut(Triple(on, attribute, axis)) { LevelReader(on, attribute, axis) }
    }

    private inner class Site(
        override val displayKey: String,
        override val hold: SurfaceDispatch.HoldKey,
        override val control: ControlDescriptor,
        override val encoderPage: EncoderBankSelection,
        private val lookups: Lookups,
    ) : FeedbackSite {
        override fun stackId(stack: UUID) = lookups.stackId(stack)
        override fun cueId(cue: UUID) = lookups.cueId(cue)
        override fun pad(pad: UUID) = lookups.pad(pad)
        override fun pageId(page: UUID) = lookups.pageId(page)
        override fun levelHeads(on: Subject, attribute: String, axis: ColourAxis?) = lookups.levelHeads(on, attribute, axis)
    }

    /** One head a level reads: its fixture, the target its key composes under and what the control stands on. */
    private class ReadHead(val fixture: GroupableFixture, val target: FxTarget, val read: AttributeChannelResolver.AttributeRead)

    /**
     * A [Does.Level]'s heads and their reading. A named subject's heads are expanded on the first read
     * after the index is built; the selection's again whenever the selection has moved since.
     *
     * With a colour [axis] named, only colour heads are kept: a slider head reads nothing on an axis,
     * and a selection mixing the two under one name would otherwise darken the ring for the heads a
     * turn does reach. The write skips the same heads ([AttributeChannelResolver.toAttributeValue]).
     */
    private inner class LevelReader(
        private val on: Subject,
        private val attribute: String,
        private val axis: ColourAxis?,
    ) : LevelHeads {
        @Volatile
        private var expanded: Pair<List<CueTargetDto>, List<ReadHead>>? = null

        private fun heads(): List<ReadHead> {
            val selection = if (on == Subject.Selection) deskSelection?.state?.value?.targets.orEmpty() else emptyList()
            expanded?.let { (at, heads) -> if (at == selection) return heads }
            val fixtures = currentFixtures ?: return emptyList()
            val heads = Heads.of(fixtures, on, attribute) { selection }.mapNotNull { head ->
                val read = AttributeChannelResolver.describeAttributeRead(head.fixture, attribute) ?: return@mapNotNull null
                if (axis != null && AttributeChannelResolver.isColour(head.fixture, attribute) != true) return@mapNotNull null
                val target = AttributeChannelResolver.composedTarget(head.fixture, attribute) ?: return@mapNotNull null
                ReadHead(head.fixture, target, read)
            }
            expanded = selection to heads
            return heads
        }

        /** The one value the heads agree on ([AttributeChannelResolver.commonValue]); mixed when they do not. */
        override fun read(): Reading {
            val heads = heads()
            if (heads.isEmpty()) return Reading.Nothing
            val resolver = runCatching { layerResolverProvider?.invoke() }.getOrNull() ?: return Reading.Nothing
            val values = ArrayList<AttributeChannelResolver.HeadValue>(heads.size)
            for (head in heads) {
                val value = resolver.localOver(head.target, head.fixture, head.fixture.targetKey)
                values += AttributeChannelResolver.readHead(head.read, value, axis) ?: return Reading.Mixed
            }
            val common = AttributeChannelResolver.commonValue(values) ?: return Reading.Mixed
            return Reading.Value(common, values.maxOf { it.tolerance })
        }
    }

    private fun rebuildIndex() {
        val projectId = try {
            projectIdProvider()
        } catch (_: Exception) {
            index.set(Index.EMPTY)
            return
        }
        val profilesByKey = types().associateBy { it.typeKey }
        val lookups = Lookups(projectId)
        val entries = ArrayList<Entry>()
        for ((displayKey, attached) in deviceMatcher.attached.value) {
            val profile = profilesByKey[attached.typeKey] ?: continue
            val layer = bankState.bankFor(displayKey)
            val page = encoderBankState.selectionFor(displayKey)
            for (control in profile.controls) {
                for (part in ControlPart.entries) {
                    val input = control.inputFor(part) ?: continue
                    val binding = bindingService.resolve(projectId, attached.typeKey, control.controlId, layer, page, part) ?: continue
                    val hold = SurfaceDispatch.HoldKey(displayKey, control.controlId, part)
                    val site = Site(displayKey, hold, control, page, lookups)
                    val entry = when (input) {
                        ControlKind.CONTINUOUS -> binding.binding.does.read(binding.binding, site)?.let { readout ->
                            Entry(displayKey, control, part, binding, readout = readout, jump = JumpPolicy.of(control, binding.takeoverPolicy))
                        }
                        ControlKind.BUTTON -> {
                            val (channel, note) = ledAt(control, part) ?: continue
                            (binding.binding.does.led(binding.binding, site) as? Led.Light)?.let { light ->
                                Entry(displayKey, control, part, binding, light = light, ledChannel = channel, ledNote = note)
                            }
                        }
                    } ?: continue
                    entries += entry
                }
            }
        }
        val bySignal = HashMap<Signal, MutableList<Entry>>()
        for (entry in entries) for (signal in entry.signals) bySignal.getOrPut(signal) { ArrayList() } += entry
        index.set(
            Index(
                bySignal = bySignal,
                byDisplay = entries.groupBy { it.displayKey },
                byPart = entries.associateBy { SurfaceDispatch.HoldKey(it.displayKey, it.controlId, it.part) },
            ),
        )
    }

    /** Where a button part's LED is sent, or null when it has none: a button's own note, an encoder's push note. */
    private fun ledAt(control: ControlDescriptor, part: ControlPart): Pair<Int, Int>? = when (control) {
        is ButtonDescriptor -> if (part == ControlPart.MAIN && control.ledFeedback != LedFeedback.NONE) control.channel to control.note else null
        is EncoderDescriptor -> control.pushNote?.takeIf { part == ControlPart.PUSH && control.pushLed != LedFeedback.NONE }?.let { control.channel to it }
        else -> null
    }

    /**
     * Full resync: drive motors, rings and LEDs for every bound control part on a device, and publish
     * the device's whole [ControlStateTracker] snapshot afterwards. With [rearmPickup] every undriven
     * fader waits to pick up again (the physical position is stale: attach, a layer, encoder-page or
     * project change), and the transport's delta cache for the device is dropped: it records what we
     * last *sent*, not what the hardware *holds*, and those diverge exactly then.
     */
    private fun sendFullResync(displayKey: String, rearmPickup: Boolean) {
        if (rearmPickup) controllerLookup(displayKey)?.invalidateAllFeedback()
        val profile = deviceMatcher.attached.value[displayKey]?.typeKey
            ?.let { typeKey -> types().firstOrNull { it.typeKey == typeKey } }
        // Every profile control at "unbound" first, so a control that lost its binding reads as such.
        if (profile != null) controlStates.reset(displayKey, profile.controls.map { it.controlId })
        val pass = Pass()
        for (entry in index.get().byDisplay[displayKey].orEmpty()) apply(entry, pass, rearmPickup)
        controlStates.publishSnapshot(displayKey)
    }

    private fun apply(entry: Entry, pass: Pass, rearm: Boolean, force: Boolean = false) {
        entry.light?.let { light ->
            sendLed(entry, runCatching { light.lit(pass) }.getOrDefault(false))
            return
        }
        val readout = entry.readout ?: return
        val reading = try {
            readout.read(pass)
        } catch (e: Exception) {
            logger.debug("Feedback read on {}/{} failed: {}", entry.displayKey, entry.controlId, e.message)
            Reading.Nothing
        }
        val value = (reading as? Reading.Value)?.value7Bit
        // A forced re-show is the function correcting the device, so it shows the function's value
        // rather than the position the control was last moved to.
        val shown = takeover.setLogical(
            entry.displayKey, entry.controlId, value, entry.jump,
            tolerance = (reading as? Reading.Value)?.tolerance ?: 0, rearm = rearm, reshow = force,
        )
        if (shown == null) {
            sendRingOff(entry.displayKey, entry.control)
        } else {
            if (force) invalidateControlFeedback(entry.displayKey, entry.control)
            sendControlFeedback(entry.displayKey, entry.control, shown)
        }
    }

    /** The live state for one pass, each source read at most once. */
    private inner class Pass : FeedbackPass {
        override val selection: List<CueTargetDto> by lazy { deskSelection?.state?.value?.targets.orEmpty() }
        private val appliedState by lazy { runCatching { programmerLayerStackProvider?.invoke()?.appliedState() }.getOrNull().orEmpty() }
        private val locatedState by lazy { runCatching { locateManagerProvider?.invoke()?.activeTargets?.value }.getOrNull().orEmpty() }
        private val scaler by lazy { runCatching { globalScalerStateProvider() }.getOrNull() }

        override fun covers(target: CueTargetDto): Boolean = deskSelection?.covers(target) ?: false
        override fun located(): Set<TargetRef> = locatedState
        override fun applied(): List<AppliedSource> = appliedState
        override fun blackout(): Boolean = scaler?.blackoutEnabled?.value ?: false
        override fun grandMasterEnabled(): Boolean = scaler?.grandMasterEnabled?.value ?: false
        override fun grandMasterLevel(): Double? = scaler?.grandMasterLevel?.value

        override fun tempo(master: UUID?): Double? {
            val states = runCatching { speedMasterBankProvider?.invoke()?.masterStates() }.getOrNull() ?: return null
            // Master 1 by *index*: once the bank has loaded, slot 0 carries its persisted row's uuid.
            return (if (master == null) states.firstOrNull { it.index == 1 } else states.firstOrNull { it.uuid == master })?.bpm
        }

        override fun stackRunning(stackId: Int): Boolean = runCatching { stackRunningProvider?.invoke(stackId) }.getOrNull() == true
        override fun stackFxAmount(stackId: Int): Double? = runCatching { stackFxAmountsProvider?.invoke()?.of(stackId) }.getOrNull()
        override fun cueLooseHeld(cueId: Int): Boolean = runCatching { cueLooseHeldProvider?.invoke(cueId) }.getOrNull() == true
        override fun buskPage(): Int? = buskPageState?.pageId?.value
        override fun held(key: SurfaceDispatch.HoldKey): Boolean = holds.isHeld(key)
        override fun encoderPage(displayKey: String): EncoderBankSelection = encoderBankState.selectionFor(displayKey)
        override fun fanAmount(on: Subject, axis: FanAxis): Int? = selectionFan?.amountFor(axis, on)
        override fun fxLeadSpeed(on: Subject): Double? = runCatching { fxSpeed?.leadSpeed(on) }.getOrNull()
        override fun blind(): Boolean = runCatching { programmerStoreProvider?.invoke()?.blind }.getOrNull() == true
    }

    /**
     * Write [value7Bit] to a control's motor or ring. The tracker is written **before** the early
     * returns: a non-motor fader is never driven, and a touched motor is spared, but both still have a
     * value the screen shows.
     */
    private fun sendControlFeedback(displayKey: String, control: ControlDescriptor, value7Bit: UByte) {
        controlStates.setValue(displayKey, control.controlId, value7Bit.toInt(), ringFor(control, lit = true))
        val controller = controllerLookup(displayKey) ?: return
        val (channel, cc) = when (control) {
            is FaderDescriptor -> {
                if (!control.hasMotor) return
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

    /** Drop the transport's last-sent bytes for [control]'s feedback, so the next send goes out. */
    private fun invalidateControlFeedback(displayKey: String, control: ControlDescriptor) {
        val controller = controllerLookup(displayKey) ?: return
        val (channel, cc) = when (control) {
            is FaderDescriptor -> control.channel to (control.motorCc ?: control.cc)
            is EncoderDescriptor -> control.channel to (control.ringCc ?: return)
            else -> return
        }
        controller.invalidateFeedbackCache(MidiControlKey(channel, MidiControlKey.Type.CC, cc))
    }

    private fun sendLed(entry: Entry, on: Boolean) {
        controlStates.setLed(entry.displayKey, entry.controlId, if (on) LedState.ON else LedState.OFF)
        val controller = controllerLookup(entry.displayKey) ?: return
        val msg = if (on) {
            MidiFeedbackMessage.NoteOnFeedback(entry.ledChannel, entry.ledNote, 127u)
        } else {
            MidiFeedbackMessage.NoteOffFeedback(entry.ledChannel, entry.ledNote, 0u)
        }
        latencyTracker.measure(MidiLatencyStage.EGRESS_LED) { controller.sendFeedback(msg) }
    }
}

/**
 * The database half of a record LED: what each busk pad presses, and what id each busk page has.
 *
 * Read in one pass by `State` and handed to [SurfaceFeedbackPublisher] as a value, because that
 * class deliberately touches no database — every other thing it needs from the show arrives as a
 * provider too. Keyed by uuid on both sides, because a binding addresses a record by uuid.
 */
data class BuskRefs(
    val pads: Map<UUID, PadRef>,
    val pageIds: Map<UUID, Int>,
) {
    companion object {
        val EMPTY = BuskRefs(emptyMap(), emptyMap())
    }
}

/**
 * What one busk pad presses, as much of it as an LED needs: a palette's or a Look's ring is read from
 * the programmer's applied state, a cue's from its loose hold, a stack's from whether it runs.
 */
sealed interface PadRef {
    /** A palette or a Look, by the uuid `ProgrammerLayerStack.appliedState` reports. */
    data class Layer(val sourceUuid: UUID, val isLook: Boolean) : PadRef

    /** A cue pad's cue; its LED is the cue's loose hold. */
    data class Cue(val cueId: Int) : PadRef

    /** A stack pad's busk stack; its LED is lit while the stack runs. */
    data class Stack(val stackId: Int) : PadRef
}
