package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fx.KeyStackLayer.HeldBackReason
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Evaluates FX off the stage (FX editor plan §3.2, D7): what a head's attribute holds over a window
 * of time, for running FX and for drafts (an [FxInstance] not added to the engine), without touching
 * anything (P4). It never writes an instance's clocks, N-shot anchors, stateful state, envelope,
 * [FxInstance.lastPhase] or its expansion and plan caches, and never writes DMX.
 *
 * It is the engine's own maths, not a copy of it (P3): the phase is [FxPhase], the members and their
 * offsets [FxEngine]'s expansion and [DistributionPlan], each output the FX's `calculate` on a fresh
 * effect object, a step resolved per head by its target, and the blend and weight [FxBlend], folded
 * in the engine's own apply order over what is underneath ([LayerResolver.fallbackFor], read once
 * per head and attribute and held across the window). `FxSamplerParityTest` holds it to the tick.
 *
 * A stateful FX (Candle Flicker) is run on a fresh object per head, from the window's start, so its
 * samples are [FxSampledFx.representative] of it rather than what the stage shows.
 *
 * Each attribute is folded on its own. Where two attributes share channels — a colour FX's bundled
 * W, A and UV beside a `white` or `uv` FX, `pan` or `tilt` beside `position` — the stage folds them
 * in one apply order and the sampler does not (`FU-FX-PREVIEW-SHARED-CHANNELS`).
 */
internal class FxSampler(
    private val view: View,
    /** A new effect object for an FX, so a sample never shares one with the compose pass. */
    private val freshEffect: (FxInstance) -> Effect,
) {
    /** What the sampler reads off the engine; [FxEngine.sampler] answers it from the pass's own functions. */
    internal interface View {
        /** The order the compose pass folds FX in, a draft placed where adding it would put it. */
        fun applyOrder(): Comparator<FxInstance>

        /** Every running FX, in [applyOrder]. */
        fun running(): List<FxInstance>

        fun fixture(key: String): GroupableFixture

        fun underneath(target: FxTarget, fixture: GroupableFixture, key: String): FxOutput

        /** What [key] holds for [target]'s property on the stage now: read, never written. */
        fun current(target: FxTarget, key: String): FxOutput?

        /** Whether a running FX covers [key]'s [property], so the compose pass resets it every pass. */
        fun coveredByRunning(key: String, property: String): Boolean

        /** Whether every channel of [target]'s property on [fixture] is parked (Layer 1). */
        fun parked(target: FxTarget, fixture: GroupableFixture): Boolean

        fun expansion(effect: FxInstance, dynamics: FxDynamics): FxTargetExpansion

        fun plans(effect: FxInstance, expansion: FxTargetExpansion, dynamics: FxDynamics): FxDistributionPlans

        /** Why each FX is held back on a key, against one snapshot of the programmer and Blind. */
        fun heldBack(): (FxInstance, String, String) -> HeldBackReason?

        /** The bank slot of a beat FX's speed master ([FxInstance.speedMasterUuid]; null is master 1). */
        fun speedSlot(uuid: java.util.UUID?): Int

        /** The bank slot of a wall-clock FX's rate master, or [FxInstance.NO_RATE_MASTER] for none. */
        fun rateSlot(uuid: java.util.UUID?): Int

        /** [effect]'s stack's FX amount (D10): 1 but for a cue's FX. */
        fun stackAmount(effect: FxInstance): Double

        /** Every clock as it reads now. */
        fun clock(): FxSampleClock
    }

    /** Samples [request] over its window, 48 to a cycle of its first FX. */
    fun sample(request: FxSampleRequest, clock: FxSampleClock = view.clock()): FxSamples {
        val truncated = HashSet<FxSamples.Truncation>()
        val picked = request.fx.take(MAX_FX).also { if (request.fx.size > MAX_FX) truncated += FxSamples.Truncation.FX }
        require(picked.isNotEmpty()) { "nothing to sample" }
        val reads = Reads()
        val cycleMs = cycleMs(picked.first(), clock, reads)
        val sampleMs = cycleMs / SAMPLES_PER_CYCLE
        val lengthMs = when (val window = request.window) {
            is FxSampleWindow.Cycles -> window.cycles * cycleMs
            is FxSampleWindow.Beats -> window.beats * MasterClock.TICKS_PER_BEAT * clock.msPerTick(beatSlotOf(picked.first(), reads))
            is FxSampleWindow.Seconds -> window.seconds * 1000.0
        }
        var samples = ceil(lengthMs / sampleMs - TICK_EPSILON).toInt().coerceAtLeast(1)
        if (samples > MAX_SAMPLES) {
            samples = MAX_SAMPLES
            truncated += FxSamples.Truncation.SAMPLES
        }
        val offsets = DoubleArray(samples) { it * sampleMs }
        return sampleAt(picked, request.include, request.rows, clock, offsets, sampleMs, truncated, reads)
    }

    /**
     * Samples [fx] at [offsetsMs] from the moment [clock] was read: [FxSampleRequest]'s fields, at
     * moments the caller chose. [sampleMs] is the step a stateful FX is driven at.
     */
    fun sampleAt(
        fx: List<FxInstance>,
        include: FxSampleRequest.Include,
        rows: List<String>?,
        clock: FxSampleClock,
        offsetsMs: DoubleArray,
        sampleMs: Double,
        truncated: MutableSet<FxSamples.Truncation> = HashSet(),
    ): FxSamples = sampleAt(fx, include, rows, clock, offsetsMs, sampleMs, truncated, Reads())

    private fun sampleAt(
        fx: List<FxInstance>,
        include: FxSampleRequest.Include,
        rows: List<String>?,
        clock: FxSampleClock,
        offsetsMs: DoubleArray,
        sampleMs: Double,
        truncated: MutableSet<FxSamples.Truncation>,
        reads: Reads,
    ): FxSamples {
        val membersOf = reads::membersOf

        val applyOrder = view.applyOrder()
        val stack = when (include) {
            FxSampleRequest.Include.ONLY -> fx.distinct().sortedWith(applyOrder)
            FxSampleRequest.Include.STACK -> {
                val named = fx.toSet()
                val keys = rows?.toSet() ?: fx.flatMapTo(HashSet()) { membersOf(it).keys }
                val reaching = view.running().filter { it !in named && membersOf(it).keys.any { key -> key in keys } }
                (reaching + fx.distinct()).sortedWith(applyOrder)
            }
        }

        var rowKeys = rows ?: fx.flatMap { effect -> membersOf(effect).keys }.distinct()
        if (rowKeys.size > MAX_ROWS) {
            rowKeys = rowKeys.take(MAX_ROWS)
            truncated += FxSamples.Truncation.ROWS
        }

        val heldBack = view.heldBack()
        val effects = HashMap<FxInstance, Effect?>()
        val representative = HashSet<FxInstance>()
        val attributes = stack.map { it.target.propertyName }.distinct().mapNotNull { attribute ->
            val onAttribute = stack.filter { it.target.propertyName == attribute }
            val sampledRows = rowKeys.mapNotNull { key ->
                val painting = onAttribute.mapNotNull { effect -> membersOf(effect)[key]?.let { effect to it } }
                if (painting.isEmpty()) null
                else sampleRow(key, attribute, painting, reads, clock, offsetsMs, sampleMs, heldBack, effects, representative)
            }
            if (sampledRows.isEmpty()) null else FxSampledAttribute(attribute, sampledRows)
        }
        return FxSamples(
            sampleMs = sampleMs,
            offsetsMs = offsetsMs,
            rows = rowKeys,
            attributes = attributes,
            fx = stack.map { FxSampledFx(it, reads.dynamicsOf(it).isRunning, it in representative) },
            truncated = truncated,
        )
    }

    /**
     * One request's reads of each FX — its dynamics, the heads it paints and its masters' slots —
     * taken once, so every part of the request works from one snapshot, as the compose pass does.
     */
    private inner class Reads {
        private val dynamics = HashMap<FxInstance, FxDynamics>()
        private val members = HashMap<FxInstance, Map<String, Member>>()
        private val slots = HashMap<FxInstance, IntArray>()

        fun dynamicsOf(effect: FxInstance): FxDynamics = dynamics.getOrPut(effect) { effect.dynamics }

        fun membersOf(effect: FxInstance): Map<String, Member> = members.getOrPut(effect) { membersOf(effect, dynamicsOf(effect)) }

        /** Resolved from the uuids rather than read off the instance, whose slots only the engine binds. */
        fun speedSlotOf(effect: FxInstance): Int = slotsOf(effect)[0]

        fun rateSlotOf(effect: FxInstance): Int = slotsOf(effect)[1]

        private fun slotsOf(effect: FxInstance) = slots.getOrPut(effect) {
            intArrayOf(view.speedSlot(effect.speedMasterUuid), view.rateSlot(effect.rateSpeedMasterUuid))
        }
    }

    /** One FX's place on one head: its unit count for the phase, its offset and the context it is handed. */
    private class Member(val groupSize: Int, val offset: Double, val context: EffectContext)

    /**
     * Which heads [effect] paints, each with its member's place: the compose pass's own walk over its
     * expansion and plans ([FxEngine.processFixtureEffect], [FxEngine.processGroupEffect]).
     */
    private fun membersOf(effect: FxInstance, dyn: FxDynamics): Map<String, Member> {
        val expansion = view.expansion(effect, dyn)
        val out = LinkedHashMap<String, Member>()
        fun spread(keys: List<String>, plan: DistributionPlan) {
            for ((i, key) in keys.withIndex()) out[key] = Member(keys.size, plan.offsets[i], plan.contexts[i])
        }
        when (expansion.kind) {
            FxTargetExpansion.Kind.DIRECT_FIXTURE -> {
                val carry = effect.splitMember
                out[effect.target.targetKey] =
                    if (carry == null) Member(1, 0.0, EffectContext.SINGLE)
                    else Member(carry.groupSize, carry.offset, carry.context)
            }
            FxTargetExpansion.Kind.FIXTURE_ELEMENTS -> spread(expansion.flat, view.plans(effect, expansion, dyn).flat)
            FxTargetExpansion.Kind.GROUP_MEMBERS -> spread(expansion.members.map { it.key }, view.plans(effect, expansion, dyn).members)
            FxTargetExpansion.Kind.GROUP_ELEMENTS -> {
                val plans = view.plans(effect, expansion, dyn)
                when (dyn.elementMode) {
                    ElementMode.PER_FIXTURE -> expansion.perFixture.forEachIndexed { i, keys -> spread(keys, plans.perFixture[i]) }
                    ElementMode.FLAT -> spread(expansion.flat, plans.flat)
                }
            }
            FxTargetExpansion.Kind.NONE -> {}
        }
        return out
    }

    private fun sampleRow(
        key: String,
        attribute: String,
        painting: List<Pair<FxInstance, Member>>,
        reads: Reads,
        clock: FxSampleClock,
        offsetsMs: DoubleArray,
        sampleMs: Double,
        heldBack: (FxInstance, String, String) -> HeldBackReason?,
        effects: HashMap<FxInstance, Effect?>,
        representative: MutableSet<FxInstance>,
    ): FxSampledRow? {
        val fixture = try {
            view.fixture(key)
        } catch (_: Exception) {
            return null
        }
        // The row's layout and underneath from its first FX's target; each FX resolves and blends
        // through its own, as the compose pass applies it.
        val target = painting.first().first.target
        val kind = kindOf(target)
        val planes = if (target is ColourTarget) BUNDLED.filterTo(HashSet()) { target.hasBundled(fixture, it) } else emptySet()
        val underneath = withPlanes(view.underneath(target, fixture, key), planes)
        val n = offsetsMs.size
        // With no running FX on the key the pass neither resets nor paints it, so the stage keeps
        // the last frame of the paused FX that painted it.
        val frozen = if (painting.none { (effect, _) -> reads.dynamicsOf(effect).isRunning } && !view.coveredByRunning(key, attribute)) {
            view.current(target, key)?.let { withPlanes(it, planes) }
        } else null

        val result = Series.of(kind, n, planes)
        val outputs = painting.map { (effect, _) -> Series.of(kind, n, planes, steps = effect.effect.outputType == FxOutputType.STEPS) }
        val holding = painting.map { BooleanArray(n) }
        val reasons = painting.map { (effect, _) -> heldBack(effect, key, attribute) }
        val dynamics = painting.map { (effect, _) -> reads.dynamicsOf(effect) }
        val weights = painting.mapIndexed { i, (effect, _) -> FxBlend.weight(dynamics[i].size, ENVELOPE, view.stackAmount(effect)) }
        // An FX whose object cannot be built (its type no longer registered) holds, as one that throws.
        val stateful = painting.map { (effect, _) ->
            if (effect.effect !is StatefulEffect) null
            else (fresh(effect) as? StatefulEffect)?.also { it.initialize(); representative += effect }
        }
        val standard = painting.map { (effect, _) ->
            if (effect.effect is StatefulEffect) null
            else if (effect in effects) effects[effect]
            else fresh(effect).also { effects[effect] = it }
        }
        for (s in 0 until n) {
            var running = underneath
            for ((i, pair) in painting.withIndex()) {
                val (effect, member) = pair
                val dyn = dynamics[i]
                val before = running
                if (!dyn.isRunning) {
                    holding[i][s] = true
                    outputs[i].set(s, before)
                    continue
                }
                val phase = phaseOf(effect, member, dyn, clock, offsetsMs[s], reads)
                if (FxInstance.isHolding(phase)) {
                    holding[i][s] = true
                    outputs[i].set(s, before)
                    continue
                }
                // A stateful FX's fixed seed: its ticks count samples from the window's start.
                val output = try {
                    stateful[i]?.calculateStateful(
                        MasterClock.ClockTick(s.toLong(), 0, 0, 0.0, (s * sampleMs).roundToLong()),
                        if (s == 0) 0L else sampleMs.roundToLong(),
                        member.context,
                    ) ?: checkNotNull(standard[i]).calculate(phase, member.context)
                } catch (_: Throwable) {
                    // Throwable for the compose pass's reason (a script's StackOverflowError, a class
                    // that fails to link); an FX that throws paints nothing there, so it holds here.
                    holding[i][s] = true
                    outputs[i].set(s, before)
                    continue
                }
                val value = onHead(effect.target, fixture, before, output)
                if (output is FxOutput.Steps) outputs[i].setStep(s, output)
                outputs[i].set(s, value ?: before)
                if (value != null && reasons[i] == null && weights[i] > 0.0) {
                    running = withPlanes(blend(effect.target, before, value, effect.blendMode, weights[i]), planes)
                }
            }
            result.set(s, frozen ?: running)
        }

        return FxSampledRow(
            key = key,
            underneath = underneath,
            parked = view.parked(target, fixture),
            result = result.build(),
            fx = painting.mapIndexed { i, (effect, _) ->
                FxRowOutput(effect, outputs[i].build(), holdingRanges(holding[i]), reasons[i])
            },
        )
    }

    /** [effect]'s member phase [offsetMs] after [clock] was read, from its own clock. */
    private fun phaseOf(
        effect: FxInstance,
        member: Member,
        dyn: FxDynamics,
        clock: FxSampleClock,
        offsetMs: Double,
        reads: Reads,
    ): Double {
        val division = FxPhase.memberDivision(effect.timing.beatDivision, dyn, member.groupSize)
        return if (effect.timingSource == TimingSource.BEAT) {
            val slot = reads.speedSlotOf(effect)
            val start = clock.tickAt(slot)
            val ticks = start + floor(offsetMs / clock.msPerTick(slot) + TICK_EPSILON).toLong()
            val anchor = effect.nShotStartTick.takeUnless { it == FxInstance.UNANCHORED || it > start } ?: start
            FxPhase.beatPhaseAt(ticks, anchor, division, member.groupSize, dyn, member.offset)
        } else {
            val start = effect.accumulatedScaledMs
            val scaledMs = start + offsetMs * clock.rateScale(reads.rateSlotOf(effect))
            val anchor = effect.nShotStartScaledMs.takeUnless { it.isNaN() || it > start } ?: start
            FxPhase.wallClockPhaseAt(scaledMs, anchor, division, member.groupSize, dyn, member.offset)
        }
    }

    /**
     * [output] as this head's value before Amount — a step resolved on [fixture] by [target], as its
     * `applyValueToFixture` resolves it — or null where it shows only a step the head cannot take.
     */
    private fun onHead(target: FxTarget, fixture: GroupableFixture, before: FxOutput, output: FxOutput): FxOutput? =
        when (target) {
            is SliderTarget -> target.outputByte(fixture, output) { sliderOf(before) }?.let { FxOutput.Slider(it) }
            is SettingTarget -> target.getSetting(fixture)?.let { target.outputLevel(fixture, output, it) }?.let { FxOutput.Slider(it) }
            is ColourTarget -> target.outputColour(fixture, output) { colourOf(before) }?.let { FxOutput.Colour(it) }
            is PositionTarget -> positionOf(before).let { target.outputPosition(fixture, output, it.pan, it.tilt) }
        }

    /** [value], an FX's output on one head, blended over [before] and weighed ([FxBlend]). */
    private fun blend(target: FxTarget, before: FxOutput, value: FxOutput, blend: BlendMode, weight: Double): FxOutput =
        when (value) {
            is FxOutput.Slider ->
                if (target is SettingTarget) FxOutput.Slider(FxBlend.setting(sliderOf(before), value.value, weight))
                else FxOutput.Slider(FxBlend.slider(sliderOf(before), value.value, blend, weight))
            is FxOutput.Colour -> {
                val base = colourOf(before)
                val effect = value.color
                FxOutput.Colour(
                    ExtendedColour(
                        FxBlend.rgb(base.color, effect.color, blend, weight),
                        white = FxBlend.slider(base.white, effect.white, blend, weight),
                        amber = FxBlend.slider(base.amber, effect.amber, blend, weight),
                        uv = FxBlend.slider(base.uv, effect.uv, blend, weight),
                    ),
                )
            }
            is FxOutput.Position -> {
                val base = positionOf(before)
                FxOutput.Position(
                    FxBlend.positionAxis(base.pan, value.pan, blend, weight),
                    FxBlend.positionAxis(base.tilt, value.tilt, blend, weight),
                )
            }
            is FxOutput.Steps -> before
        }

    private fun sliderOf(value: FxOutput): UByte = (value as? FxOutput.Slider)?.value ?: 0u

    private fun colourOf(value: FxOutput): ExtendedColour = (value as? FxOutput.Colour)?.color ?: ExtendedColour.BLACK

    private fun positionOf(value: FxOutput): FxOutput.Position = value as? FxOutput.Position ?: FxOutput.Position(128u, 128u)

    /**
     * [underneath] with only the bundled emitters [planes] names, the rest at 0: what a head reads
     * where it has no such emitter, which a step crosses from as the target does.
     */
    private fun withPlanes(underneath: FxOutput, planes: Set<PropertyCategory>): FxOutput {
        val colour = (underneath as? FxOutput.Colour)?.color ?: return underneath
        return FxOutput.Colour(
            ExtendedColour(
                colour.color,
                white = if (PropertyCategory.WHITE in planes) colour.white else 0u,
                amber = if (PropertyCategory.AMBER in planes) colour.amber else 0u,
                uv = if (PropertyCategory.UV in planes) colour.uv else 0u,
            ),
        )
    }

    /** The picked FX's cycle in real milliseconds, its first unit's Timing included. */
    private fun cycleMs(effect: FxInstance, clock: FxSampleClock, reads: Reads): Double {
        val groupSize = reads.membersOf(effect).values.firstOrNull()?.groupSize ?: 1
        val division = FxPhase.memberDivision(effect.timing.beatDivision, reads.dynamicsOf(effect), groupSize)
        return if (effect.timingSource == TimingSource.BEAT) {
            division * MasterClock.TICKS_PER_BEAT * clock.msPerTick(reads.speedSlotOf(effect))
        } else {
            division * 1000.0 / clock.rateScale(reads.rateSlotOf(effect))
        }
    }

    private fun fresh(effect: FxInstance): Effect? = try {
        freshEffect(effect)
    } catch (_: Throwable) {
        null
    }

    /** The master a beat window counts beats on: [effect]'s own, or master 1 for one on the wall clock. */
    private fun beatSlotOf(effect: FxInstance, reads: Reads): Int =
        if (effect.timingSource == TimingSource.BEAT) reads.speedSlotOf(effect) else 0

    private fun kindOf(target: FxTarget): FxSampleKind = when (target) {
        is SliderTarget, is SettingTarget -> FxSampleKind.SLIDER
        is ColourTarget -> FxSampleKind.COLOUR
        is PositionTarget -> FxSampleKind.POSITION
    }

    /** Fills one row's samples, a byte a sample per plane; builds an [FxSampleSeries]. */
    private class Series private constructor(
        private val kind: FxSampleKind,
        private val n: Int,
        private val planes: Set<PropertyCategory>,
        private val steps: Boolean,
    ) {
        private val a = ByteArray(n)
        private val b = if (kind != FxSampleKind.SLIDER) ByteArray(n) else null
        private val c = if (kind == FxSampleKind.COLOUR) ByteArray(n) else null
        private val white = if (PropertyCategory.WHITE in planes) ByteArray(n) else null
        private val amber = if (PropertyCategory.AMBER in planes) ByteArray(n) else null
        private val uv = if (PropertyCategory.UV in planes) ByteArray(n) else null
        private val indices = if (steps) IntArray(n) else null
        private var labels: List<String> = emptyList()

        fun set(s: Int, value: FxOutput) {
            when (value) {
                is FxOutput.Slider -> a[s] = value.value.toByte()
                is FxOutput.Position -> { a[s] = value.pan.toByte(); b!![s] = value.tilt.toByte() }
                is FxOutput.Colour -> {
                    val colour = value.color
                    a[s] = colour.color.red.toByte()
                    b!![s] = colour.color.green.toByte()
                    c!![s] = colour.color.blue.toByte()
                    white?.set(s, colour.white.toByte())
                    amber?.set(s, colour.amber.toByte())
                    uv?.set(s, colour.uv.toByte())
                }
                is FxOutput.Steps -> {}
            }
        }

        /** A Steps FX's step at [s], beside its value on the head ([set]). */
        fun setStep(s: Int, output: FxOutput.Steps) {
            indices?.set(s, output.snapped)
            if (labels.isEmpty()) labels = labelsOf(output.steps)
        }

        fun build(): FxSampleSeries = when {
            indices != null -> FxSampleSeries.Steps(indices, labels, values = plain())
            else -> plain()
        }

        private fun plain(): FxSampleSeries = when (kind) {
            FxSampleKind.SLIDER -> FxSampleSeries.Bytes(a)
            FxSampleKind.POSITION -> FxSampleSeries.Position(a, b!!)
            FxSampleKind.COLOUR -> FxSampleSeries.Colour(a, b!!, c!!, white, amber, uv)
        }

        companion object {
            fun of(kind: FxSampleKind, n: Int, planes: Set<PropertyCategory>, steps: Boolean = false) = Series(kind, n, planes, steps)
        }
    }

    companion object {
        /** Samples a cycle of the picked FX. */
        const val SAMPLES_PER_CYCLE = 48

        /** Samples a row, at most. */
        const val MAX_SAMPLES = 1024

        /** Rows (heads) a request, at most. */
        const val MAX_ROWS = 96

        /** FX a request names, at most. */
        const val MAX_FX = 8

        /** The envelope, taken as full: a fade in or out is not drawn. */
        private const val ENVELOPE = 1.0

        /** A sample landing within this much of a tick counts as on it, against floating-point division. */
        private const val TICK_EPSILON = 1e-6

        private val BUNDLED = setOf(PropertyCategory.WHITE, PropertyCategory.AMBER, PropertyCategory.UV)

        private fun labelsOf(steps: StepList): List<String> = when (steps) {
            is PaletteStepList -> steps.steps.map { it.name }
            is SlotStepList -> steps.names
            else -> List(steps.size) { (it + 1).toString() }
        }

        /** The sample ranges a member holds in (an N-shot outside its runs, or a paused FX). */
        private fun holdingRanges(holding: BooleanArray): List<IntRange> {
            val ranges = ArrayList<IntRange>()
            var from = -1
            for (i in holding.indices) {
                if (holding[i] && from < 0) from = i
                if (!holding[i] && from >= 0) { ranges += from until i; from = -1 }
            }
            if (from >= 0) ranges += from until holding.size
            return ranges
        }
    }
}

/** What to sample: [fx], the first of them the one whose cycle sets the window. */
internal data class FxSampleRequest(
    val fx: List<FxInstance>,
    val include: Include = Include.STACK,
    /** The heads to draw, in order; null is every head [fx] paint, in their order. */
    val rows: List<String>? = null,
    val window: FxSampleWindow = FxSampleWindow.Cycles(2.0),
) {
    enum class Include {
        /** Every running FX reaching the rows, plus [fx]. */
        STACK,

        /** [fx] alone. */
        ONLY,
    }
}

/** The span sampled, from now: cycles of the picked FX, beats of its master, or seconds. */
internal sealed interface FxSampleWindow {
    data class Cycles(val cycles: Double) : FxSampleWindow
    data class Beats(val beats: Double) : FxSampleWindow
    data class Seconds(val seconds: Double) : FxSampleWindow
}

/**
 * Every clock at the moment sampling starts: each master's tick and its length, and each rate
 * master's scale on a wall-clock FX ([FxInstance.NO_RATE_MASTER] is unscaled).
 */
internal class FxSampleClock(
    private val ticks: (slot: Int) -> Long,
    private val msPerTicks: (slot: Int) -> Double,
    private val rateScales: (slot: Int) -> Double,
) {
    fun tickAt(slot: Int): Long = ticks(slot)

    fun msPerTick(slot: Int): Double = msPerTicks(slot)

    fun rateScale(slot: Int): Double =
        if (slot == FxInstance.NO_RATE_MASTER) 1.0 else rateScales(slot).takeIf { it > 0.0 } ?: 1.0
}

/** How an attribute's samples are laid out. */
internal enum class FxSampleKind { SLIDER, COLOUR, POSITION }

/**
 * One row's samples (§3.2's formats, as Kotlin values): bytes are unsigned, a byte a sample, read
 * with `toInt() and 0xFF`.
 */
internal sealed interface FxSampleSeries {
    /** A slider, or a setting's level. */
    class Bytes(val values: ByteArray) : FxSampleSeries

    /** RGB, and each bundled emitter the head has. */
    class Colour(
        val red: ByteArray,
        val green: ByteArray,
        val blue: ByteArray,
        val white: ByteArray?,
        val amber: ByteArray?,
        val uv: ByteArray?,
    ) : FxSampleSeries

    class Position(val pan: ByteArray, val tilt: ByteArray) : FxSampleSeries

    /** A Steps FX's own output: the step it shows, beside the steps' labels, and its value on the head. */
    class Steps(val indices: IntArray, val labels: List<String>, val values: FxSampleSeries) : FxSampleSeries
}

internal class FxSamples(
    val sampleMs: Double,
    /** When each sample is, after the clocks were read. */
    val offsetsMs: DoubleArray,
    /** The heads sampled, in drawing order. */
    val rows: List<String>,
    val attributes: List<FxSampledAttribute>,
    /** Every FX sampled, in apply order. */
    val fx: List<FxSampledFx>,
    val truncated: Set<Truncation>,
) {
    enum class Truncation { SAMPLES, ROWS, FX }

    fun attribute(name: String): FxSampledAttribute? = attributes.firstOrNull { it.attribute == name }
}

internal class FxSampledAttribute(val attribute: String, val rows: List<FxSampledRow>) {
    fun row(key: String): FxSampledRow? = rows.firstOrNull { it.key == key }
}

/**
 * One head's attribute over the window: [underneath], held constant; [result], what the stage gets
 * after every FX that is not held back, or the frame a paused FX left when nothing running covers the
 * key; and each FX's own output, before Amount, in apply order. [result] is before Layer 1: on a
 * [parked] row the park is what is sent.
 */
internal class FxSampledRow(
    val key: String,
    val underneath: FxOutput,
    /** Every channel of this attribute on this head is parked. */
    val parked: Boolean,
    val result: FxSampleSeries,
    val fx: List<FxRowOutput>,
)

/**
 * One FX's output on one head. A [holding] sample (an N-shot member outside its runs, a paused FX,
 * an FX that cannot be evaluated) repeats what it would have blended over. [heldBack] says why it is left out of the result, when it is.
 */
internal class FxRowOutput(
    val fx: FxInstance,
    val output: FxSampleSeries,
    val holding: List<IntRange>,
    val heldBack: HeldBackReason?,
)

/** An FX sampled: [running] unless paused; [representative] when stateful (see [FxSampler]). */
internal class FxSampledFx(val fx: FxInstance, val running: Boolean, val representative: Boolean)
