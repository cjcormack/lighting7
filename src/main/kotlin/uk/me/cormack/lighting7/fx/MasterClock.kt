package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong

/**
 * One speed master's tempo clock.
 *
 * Historically the single global tempo; since the speed-masters work each clock is one
 * member of a [SpeedMasterBank], with the bank's slot 0 ("master 1") being the global
 * tempo every pre-existing surface (`setFxBpm`, `tapTempo`, `fxState.bpm`, `beatSync`)
 * maps to. The clock emits tick events (24 per beat, like MIDI clock) and beat events.
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
         * resyncs so effects resume smoothly from where they were.
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

    private val _beatFlow = MutableSharedFlow<BeatEvent>(replay = 0, extraBufferCapacity = 1)

    /** Emits on every beat (quarter note) */
    val beatFlow: SharedFlow<BeatEvent> = _beatFlow.asSharedFlow()

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

    private var clockJob: Job? = null

    // Tap tempo tracking. Guarded by its own monitor: taps arrive from WS handlers, REST
    // routes, scripts and the AI tool, which run on different dispatchers.
    private val tapTimestamps = mutableListOf<Long>()
    private val maxTapHistory = 4
    private val tapTimeoutMs = 2000L

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
     */
    fun start(scope: CoroutineScope) {
        if (clockJob?.isActive == true) return

        totalTicks.set(0)
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
                    _beatFlow.tryEmit(BeatEvent(beatNumber, tick.timestampMs))
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
        clockJob?.cancel()
        clockJob = null
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
        val now = System.currentTimeMillis()

        val averageIntervalMs = synchronized(tapTimestamps) {
            // Remove old taps that are beyond the timeout
            tapTimestamps.removeIf { now - it > tapTimeoutMs }

            // Add the new tap
            tapTimestamps.add(now)

            // Keep only the most recent taps
            while (tapTimestamps.size > maxTapHistory) {
                tapTimestamps.removeAt(0)
            }

            // Need at least 2 taps to calculate BPM
            if (tapTimestamps.size < 2) return

            tapTimestamps.zipWithNext { a, b -> b - a }.average()
        }

        if (averageIntervalMs > 0) {
            setBpm(60_000.0 / averageIntervalMs)
        }
    }

    /**
     * Get the duration of one beat in milliseconds at current BPM.
     */
    fun beatDurationMs(): Long = (60_000.0 / _bpm.value).toLong()

    /**
     * Get duration for a beat division in milliseconds.
     *
     * @param division The beat division (e.g., BeatDivision.QUARTER for one beat)
     * @return Duration in milliseconds
     */
    fun divisionDurationMs(division: Double): Long =
        (beatDurationMs() * division).toLong()
}
