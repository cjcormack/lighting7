package uk.me.cormack.lighting7.fx

/**
 * Configuration for effect timing relative to the master clock.
 *
 * @param beatDivision Length of one effect cycle in beats (see [BeatDivision])
 * @param startOnBeat If true, quantize effect start to the next beat
 */
data class FxTiming(
    val beatDivision: Double = BeatDivision.QUARTER,
    val startOnBeat: Boolean = true
)

/**
 * How an effect's output blends with the fixture's current value.
 */
enum class BlendMode {
    /** Effect value completely replaces fixture value */
    OVERRIDE,

    /** Effect value is added to fixture value (clamped to 0-255) */
    ADDITIVE,

    /** Effect value is multiplied with fixture value */
    MULTIPLY,

    /** Maximum of effect and fixture value */
    MAX,

    /** Minimum of effect and fixture value */
    MIN
}

/**
 * A running instance of an effect bound to a specific target.
 *
 * FxInstance tracks the state of an active effect, including its phase
 * and whether it's currently running. Multiple instances of the same
 * effect can run simultaneously on different targets.
 *
 * @param effect The effect to run
 * @param target The fixture property to apply the effect to
 * @param timing Timing configuration relative to master clock
 * @param blendMode How to blend effect output with fixture value
 */
class FxInstance(
    val effect: Effect,
    val target: FxTarget,
    val timing: FxTiming,
    val blendMode: BlendMode = BlendMode.OVERRIDE
) {
    /** Unique identifier assigned by FxEngine */
    var id: Long = 0

    /** Whether this effect is currently running */
    var isRunning: Boolean = true

    /** Most recently calculated phase (for state reporting) */
    var lastPhase: Double = 0.0

    /** Phase offset for syncing multiple effects (e.g., for chase effects) */
    var phaseOffset: Double = 0.0

    /** Timestamp when the effect started (for timing calculations) */
    var startedAtMs: Long = System.currentTimeMillis()

    /** Beat number when the effect started (for beat-quantized start) */
    var startedAtBeat: Long = 0

    /**
     * Calculate the current phase for this effect based on clock timing.
     *
     * @param tick The current clock tick
     * @param clock The master clock for timing calculations
     * @return Phase from 0.0 to 1.0 within the effect cycle
     */
    fun calculatePhase(tick: MasterClock.ClockTick, clock: MasterClock): Double {
        val basePhase = clock.phaseForDivision(tick, timing.beatDivision)
        val phase = (basePhase + phaseOffset) % 1.0
        lastPhase = phase
        return phase
    }

    /** Pause the effect */
    fun pause() {
        isRunning = false
    }

    /** Resume the effect */
    fun resume() {
        isRunning = true
    }
}
