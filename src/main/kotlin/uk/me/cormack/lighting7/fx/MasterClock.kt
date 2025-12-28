package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Global tempo reference for FX synchronization.
 *
 * The Master Clock provides a BPM-based timing reference that all effects
 * synchronize to. It emits tick events (24 per beat, like MIDI clock) and
 * beat events that effects can subscribe to.
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
    }

    private val _bpm = MutableStateFlow(DEFAULT_BPM)

    /** Current BPM as a StateFlow for reactive updates */
    val bpm: StateFlow<Double> = _bpm.asStateFlow()

    private val _isRunning = MutableStateFlow(false)

    /** Whether the clock is currently running */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val startTimeMs = AtomicLong(System.currentTimeMillis())
    private val totalTicks = AtomicLong(0)

    private val _tickFlow = MutableSharedFlow<ClockTick>(replay = 0, extraBufferCapacity = 1)

    /** Emits on every tick (24 per beat) */
    val tickFlow: SharedFlow<ClockTick> = _tickFlow.asSharedFlow()

    private val _beatFlow = MutableSharedFlow<BeatEvent>(replay = 0, extraBufferCapacity = 1)

    /** Emits on every beat (quarter note) */
    val beatFlow: SharedFlow<BeatEvent> = _beatFlow.asSharedFlow()

    private var clockJob: Job? = null

    // Tap tempo tracking
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
     * @param scope The coroutine scope to run the clock in
     */
    fun start(scope: CoroutineScope) {
        if (clockJob?.isActive == true) return

        startTimeMs.set(System.currentTimeMillis())
        totalTicks.set(0)
        _isRunning.value = true

        clockJob = scope.launch(Dispatchers.Default) {
            while (isActive && _isRunning.value) {
                val currentBpm = _bpm.value
                val tickIntervalMs = (60_000.0 / (currentBpm * TICKS_PER_BEAT)).toLong()

                delay(tickIntervalMs.coerceAtLeast(1))

                val currentTick = totalTicks.incrementAndGet()
                val beatNumber = (currentTick - 1) / TICKS_PER_BEAT
                val tickInBeat = ((currentTick - 1) % TICKS_PER_BEAT).toInt()
                val phase = tickInBeat.toDouble() / TICKS_PER_BEAT

                val now = System.currentTimeMillis()
                val tick = ClockTick(
                    tickNumber = currentTick,
                    beatNumber = beatNumber,
                    tickInBeat = tickInBeat,
                    phase = phase,
                    timestampMs = now
                )

                _tickFlow.tryEmit(tick)

                if (tickInBeat == 0) {
                    _beatFlow.tryEmit(BeatEvent(beatNumber, now))
                }
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

        // Remove old taps that are beyond the timeout
        tapTimestamps.removeIf { now - it > tapTimeoutMs }

        // Add the new tap
        tapTimestamps.add(now)

        // Keep only the most recent taps
        while (tapTimestamps.size > maxTapHistory) {
            tapTimestamps.removeAt(0)
        }

        // Need at least 2 taps to calculate BPM
        if (tapTimestamps.size >= 2) {
            val intervals = mutableListOf<Long>()
            for (i in 1 until tapTimestamps.size) {
                intervals.add(tapTimestamps[i] - tapTimestamps[i - 1])
            }

            val averageIntervalMs = intervals.average()
            if (averageIntervalMs > 0) {
                val calculatedBpm = 60_000.0 / averageIntervalMs
                setBpm(calculatedBpm)
            }
        }
    }

    /**
     * Reset tap tempo history.
     */
    fun resetTap() {
        tapTimestamps.clear()
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

    /**
     * Calculate the current phase for a given beat division.
     *
     * @param tick The current clock tick
     * @param division The beat division (e.g., BeatDivision.HALF for two-beat cycle)
     * @return Phase from 0.0 to 1.0 within the division cycle
     */
    fun phaseForDivision(tick: ClockTick, division: Double): Double {
        val ticksPerCycle = (TICKS_PER_BEAT * division).toLong()
        val tickInCycle = tick.tickNumber % ticksPerCycle
        return tickInCycle.toDouble() / ticksPerCycle
    }
}
