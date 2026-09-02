package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong

/**
 * One speed master's tempo clock.
 *
 * Historically the single global tempo; since the speed-masters work each clock is one
 * member of a [SpeedMasterBank], with the bank's slot 0 ("master 1") being the global tempo
 * that the script API's `setBpm`/`tapTempo`, the AI `set_bpm` tool, and every effect with no
 * explicit master resolve to. The clock emits tick events on [tickFlow] (24 per beat, like
 * MIDI clock), and reports beat boundaries through the [onBeat] callback.
 *
 * Tempo is expressed purely as tick **emission rate**: effect phase is a pure function of
 * the tick counter (see [phaseForDivision]), never of BPM, so a tempo change takes effect
 * within one tick and phase stays continuous — [totalTicks] is never reset by [setBpm].
 *
 * Usage:
 * ```
 * val clock = MasterClock()
 * clock.start(scope)
 * clock.setBpm(128.0)
 *
 * // Subscribe to ticks
 * clock.tickFlow.collect { tick ->
 *     // tick.phase is 0.0-1.0 within the current beat
 * }
 * ```
 */
class MasterClock {
    companion object {
        /**
         * A new clock's starting tempo, and separately the rate-master reference:
         * [SpeedMasterBank.rateScales] divides a master's live BPM by this same constant to
         * get its rate multiplier, so 1.0x means "at `DEFAULT_BPM`" rather than "unscaled" —
         * the two roles happen to share a value, not a concept.
         */
        const val DEFAULT_BPM = 120.0
        const val MIN_BPM = 20.0
        const val MAX_BPM = 300.0
        const val TICKS_PER_BEAT = 24  // Like MIDI clock resolution

        /**
         * How far behind the deadline the timer will "catch up" by emitting immediate
         * back-to-back ticks before it gives up and resyncs to now.
         *
         * The catch-up path is what makes long-run rates exact: per-`delay` overshoot
         * (typically 1–3 ms) accumulates as a small negative deficit that the loop works
         * off by shortening or skipping subsequent waits. Past this bound the stall is a
         * real pause (GC, debugger, machine sleep), and bursting the backlog would snap
         * every BEAT effect's phase forward in one frame — at 120 BPM this cap limits the
         * burst to ~12 ticks (half a beat), visually negligible, while anything longer
         * resyncs so effects resume smoothly from where they were. The burst scales with
         * tempo since it is a fixed *time* bound over a tick rate that speeds up with BPM:
         * at [MAX_BPM] it is ~30 ticks (1.25 beats), the largest a real tempo can produce.
         */
        const val MAX_CATCHUP_MS = 250L

        /**
         * Phase (0.0–1.0) within a [division]-beat cycle at [tickNumber].
         *
         * A pure function of the tick counter — deliberately **not** an instance method,
         * because it reads no clock state: with several clocks alive, an instance form made
         * "master 2's tick + master 1's clock" silently correct, which is exactly the
         * mistake a bank must not invite.
         */
        fun phaseForDivision(tickNumber: Long, division: Double): Double {
            val ticksPerCycle = (TICKS_PER_BEAT * division).toLong()
            if (ticksPerCycle <= 0) return 0.0
            val tickInCycle = tickNumber % ticksPerCycle
            return tickInCycle.toDouble() / ticksPerCycle
        }
    }

    private val _bpm = MutableStateFlow(DEFAULT_BPM)

    /** Current BPM as a StateFlow for reactive updates */
    val bpm: StateFlow<Double> = _bpm.asStateFlow()

    private val _isRunning = MutableStateFlow(false)

    /** Whether the clock is currently running */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val totalTicks = AtomicLong(0)

    private val _tickFlow = MutableSharedFlow<ClockTick>(replay = 0, extraBufferCapacity = 1)

    /** Emits on every tick (24 per beat) */
    val tickFlow: SharedFlow<ClockTick> = _tickFlow.asSharedFlow()

    /**
     * Most recent tick, readable without collecting [tickFlow]. The [SpeedMasterBank]
     * samples every clock's current tick into one coherent frame per engine pass.
     */
    @Volatile
    var currentTick: ClockTick = ClockTick(0, 0, 0, 0.0, System.currentTimeMillis())
        private set

    /**
     * Invoked after every tick, off the flow machinery — the bank uses it to nudge the
     * engine's conflated wake channel without paying a collector per clock.
     */
    @Volatile
    var onTick: (() -> Unit)? = null

    /**
     * Invoked on every beat boundary, off the flow machinery — the twin of [onTick], and
     * for the same reason. The bank fans every clock's beats into one keyed stream from
     * here; a `SharedFlow` per clock would need a collector coroutine per master and
     * explicit cancel-on-reload bookkeeping (there was one, consumed only by the retired
     * `beatSync` push, and it emitted per beat per master for nobody else). This callback is
     * wired once per clock *instance*, so a rename or reorder that keeps the instance keeps
     * the wiring.
     */
    @Volatile
    var onBeat: ((BeatEvent) -> Unit)? = null

    private var clockJob: Job? = null

    private val tapTempo = TapTempo()

    /**
     * Represents a single clock tick.
     */
    data class ClockTick(
        /** Total tick count since clock started */
        val tickNumber: Long,
        /** Current beat number (0-indexed) */
        val beatNumber: Long,
        /** Tick within current beat (0 to TICKS_PER_BEAT-1) */
        val tickInBeat: Int,
        /** Phase within current beat (0.0 to 1.0) */
        val phase: Double,
        /** Wall-clock timestamp in milliseconds */
        val timestampMs: Long
    )

    /**
     * Represents a beat event.
     */
    data class BeatEvent(
        /** Beat number (0-indexed) */
        val beatNumber: Long,
        /** Wall-clock timestamp in milliseconds */
        val timestampMs: Long
    )

    /**
     * Start the clock running.
     *
     * The timer keeps a fractional deadline rather than delaying a truncated interval.
     * `(60_000 / (bpm * 24)).toLong()` looked harmless with one clock — at 120 BPM the
     * 20.83 ms tick truncated to 20 ms, i.e. the whole show ran ~4% fast, invisibly. With
     * several clocks the truncation becomes *relative* drift: 120 vs 60 BPM truncate to
     * 20 ms vs 41 ms, a 2.05 ratio, so a half-speed effect visibly slides against its
     * parent. The deadline form keeps long-run ratios exact.
     *
     * @param scope The coroutine scope to run the clock in
     * @param resetCounter Start the tick counter at zero. False when a clock that was being
     *   [driven] by another master is unlinked and takes over its own timing: the counter is
     *   what effect phase is computed from, so resetting it there would snap every effect on
     *   this master at the moment the operator merely stopped following.
     */
    fun start(scope: CoroutineScope, resetCounter: Boolean = true) {
        if (clockJob?.isActive == true) return

        driven = false
        if (resetCounter) totalTicks.set(0)
        _isRunning.value = true

        clockJob = scope.launch(Dispatchers.Default) {
            var nextAtMs = System.currentTimeMillis().toDouble()
            while (isActive && _isRunning.value) {
                // Re-read BPM every iteration so a tempo change takes effect within one
                // tick; the counter is never reset, which is what keeps phase continuous
                // across the change.
                val currentBpm = _bpm.value
                nextAtMs += 60_000.0 / (currentBpm * TICKS_PER_BEAT)

                val now = System.currentTimeMillis()
                val delayMs = (nextAtMs - now).toLong()
                if (delayMs > 0) {
                    delay(delayMs)
                } else if (delayMs < -MAX_CATCHUP_MS) {
                    // Fell far behind (GC pause, process suspended, debugger). Resync the
                    // deadline instead of machine-gunning a large catch-up burst; deficits
                    // within MAX_CATCHUP_MS are worked off tick-by-tick, which is what keeps
                    // long-run rates exact under ordinary scheduler jitter.
                    nextAtMs = now.toDouble()
                }

                val currentTickNumber = totalTicks.incrementAndGet()
                val beatNumber = (currentTickNumber - 1) / TICKS_PER_BEAT
                val tickInBeat = ((currentTickNumber - 1) % TICKS_PER_BEAT).toInt()
                val phase = tickInBeat.toDouble() / TICKS_PER_BEAT

                val tick = ClockTick(
                    tickNumber = currentTickNumber,
                    beatNumber = beatNumber,
                    tickInBeat = tickInBeat,
                    phase = phase,
                    timestampMs = System.currentTimeMillis()
                )

                currentTick = tick
                _tickFlow.tryEmit(tick)

                if (tickInBeat == 0) {
                    onBeat?.invoke(BeatEvent(beatNumber, tick.timestampMs))
                }

                onTick?.invoke()
            }
        }
    }

    /**
     * Stop the clock.
     */
    fun stop() {
        _isRunning.value = false
        driven = false
        clockJob?.cancel()
        clockJob = null
    }

    /**
     * Whether this clock's ticks come from another master rather than from its own timer —
     * see [driveTo]. Set by [adoptDriven], cleared by [start] and [stop].
     */
    @Volatile
    var driven: Boolean = false
        private set

    /**
     * Hand this clock's timing over to a leader: cancel its timer without touching the tick
     * counter, and keep reporting as running, because from every reader's point of view it
     * still is — a follower's effects animate exactly as before, just on someone else's beat.
     *
     * Idempotent, so [SpeedMasterBank.load] can call it for every follower on every reload.
     *
     * The counter is zeroed on the manual→driven transition, and that is load-bearing rather
     * than tidy-up: a clock that has been free-running carries a counter of its own, and the
     * tick its new leader maps to is very often *below* it — a ½ follower's target is half the
     * leader's count, so two clocks started together give a mapped tick roughly half the
     * follower's. [driveTo] is monotonic, so without this the follower would refuse every
     * drive until its leader's counter overtook it: minutes on a desk that has been up
     * minutes, hours on one that has been up hours, with the master silently frozen the whole
     * time. Zeroing makes the leader's next tick assign the mapped value outright, which is
     * the "linking snaps once" the feature is asking for.
     *
     * [snap] extends that to an *already-driven* clock, and the caller owes it exactly when
     * the mapping itself changed — a new ratio or a new leader ([SpeedMasterBank.load]
     * decides). The same arithmetic bites: a follower driven at 2× carries twice its leader's
     * count, so re-pointing it at ½ maps it to a quarter of where it stands and it freezes
     * until the leader's counter quadruples. Without [snap] the freeze is silent and *looks*
     * like the ratio simply not taking — the tempo readout is right (that comes from the
     * derived bpm, not the clock), while the beat lamp never lights again and every effect on
     * the master stops dead. Ordinary reloads — a rename, a usage retag, a stored-tempo edit —
     * pass false and stay the no-op they were, which is what keeps effect phase continuous
     * across unrelated CRUD.
     */
    fun adoptDriven(snap: Boolean = false) {
        clockJob?.cancel()
        clockJob = null
        if (!driven || snap) totalTicks.set(0)
        driven = true
        _isRunning.value = true
    }

    /**
     * Advance a driven clock to [tickNumber], the absolute tick its leader's tick maps to.
     *
     * The counter is *assigned*, not incremented, which is the whole point: a follower's tick
     * is a pure function of its leader's, so its beat boundaries land on the leader's rather
     * than wherever its own counter happened to start. Ticks are monotonic — a leader that
     * resyncs backwards after a stall is ignored rather than rewinding effect phase.
     *
     * [onBeat] fires once per beat *crossed*, collapsed to the newest beat: a ratio above 1
     * maps one leader tick to several follower ticks, and a link mid-show jumps the counter
     * outright, neither of which should emit a burst of stale beat events. [onTick] is
     * deliberately NOT invoked — the bank drives followers from inside the leader's own tick
     * callback, which has already nudged the engine's (conflated) wake channel for this pass.
     */
    fun driveTo(tickNumber: Long, timestampMs: Long) {
        val previous = totalTicks.get()
        if (tickNumber <= previous) return
        totalTicks.set(tickNumber)

        // The counter is 1-based and a beat lands on its first tick, exactly as the timer loop
        // numbers them. Getting this convention wrong is invisible in every reading of the
        // clock *except* the one the whole feature is for: a follower's beats would sit one
        // tick off its leader's, close enough to look right in a test that compares rates and
        // wrong to an operator watching two beat lamps.
        val beatNumber = (tickNumber - 1) / TICKS_PER_BEAT
        val tickInBeat = ((tickNumber - 1) % TICKS_PER_BEAT).toInt()
        val tick = ClockTick(
            tickNumber = tickNumber,
            beatNumber = beatNumber,
            tickInBeat = tickInBeat,
            phase = tickInBeat.toDouble() / TICKS_PER_BEAT,
            timestampMs = timestampMs,
        )
        currentTick = tick
        _tickFlow.tryEmit(tick)

        val previousBeat = if (previous < 1) -1L else (previous - 1) / TICKS_PER_BEAT
        if (beatNumber != previousBeat) {
            onBeat?.invoke(BeatEvent(beatNumber, timestampMs))
        }
    }

    /**
     * Set a *derived* tempo — a follower's `leader.bpm × num / den`.
     *
     * Unlike [setBpm] this does not clamp to [MIN_BPM]..[MAX_BPM]. The range exists to keep
     * the tick *timer* sane, and a driven clock has no timer: its rate is its leader's rate
     * times the ratio, exactly, whatever number that comes to. Clamping the reported figure
     * would make the strip's BPM readout disagree with what the master is audibly doing, and
     * would feed a wrong multiplier to [SpeedMasterBank.rateScales] for wall-clock effects.
     */
    fun setDerivedBpm(newBpm: Double) {
        _bpm.value = newBpm
    }

    /**
     * Set the tempo in beats per minute.
     *
     * @param newBpm The new BPM value (clamped to MIN_BPM..MAX_BPM)
     */
    fun setBpm(newBpm: Double) {
        _bpm.value = newBpm.coerceIn(MIN_BPM, MAX_BPM)
    }

    /**
     * Tap tempo - call this method repeatedly to set BPM based on tap timing.
     * Requires at least 2 taps to calculate BPM.
     */
    fun tap() {
        tapTempo.tap()?.let { setBpm(it) }
    }
}
