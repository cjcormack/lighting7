package uk.me.cormack.lighting7.scripts

import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureTarget
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fixture.trait.*
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.fx.group.*
import uk.me.cormack.lighting7.show.Show
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget

/**
 * Base class for FX application scripts.
 *
 * These scripts apply effects to fixtures and groups with an implicit [FxEngine] —
 * no need to pass `fxEngine` to every call. They have no access to DMX controllers,
 * scene management, or coroutines.
 *
 * Effects are named, not constructed: [effect] looks a type up in the show's [FxRegistry], so a
 * script reaches exactly the vocabulary the UI, cues and Looks reach — including user effects
 * defined in `fx_definitions`, which no compiled-in class could offer.
 *
 * Example:
 * ```
 * val wash = fixture<HexFixture>("front-wash-1")
 * val movers = group<MovingHead>("movers")
 *
 * wash.fx {
 *     dimmer(effect("SineWave", "min" to "40"), BeatDivision.HALF)
 *     colour(effect("ColourCycle"), BeatDivision.ONE_BAR)
 * }
 *
 * movers.fx {
 *     dimmer(effect("Pulse"), BeatDivision.QUARTER, distribution = DistributionStrategy.CENTER_OUT)
 * }
 *
 * // Run one effect off speed master 2 rather than the global tempo
 * wash.colourFx(effect("RainbowCycle"), speedMasterUuid = speedMasterUuidAt(2))
 *
 * setBpm(128.0)
 * ```
 */
@KotlinScript(
    fileExtension = "fxapp.kts",
    compilationConfiguration = FxApplicationScriptConfiguration::class,
)
abstract class FxApplicationScript(
    @PublishedApi internal val show: Show,
    @PublishedApi internal val fxEngine: FxEngine,
    val scriptName: String,
    val step: Int,
) {
    // --- Fixture/Group Lookup ---

    inline fun <reified T : Fixture> fixture(key: String): T = show.fixtures.fixture(key)
    inline fun <reified T : Fixture> group(key: String): FixtureGroup<T> = show.fixtures.group(key)

    // --- Tempo Control ---

    val masterClock: MasterClock get() = fxEngine.masterClock
    val speedMasters: SpeedMasterBank get() = fxEngine.speedMasters
    val bpm: Double get() = masterClock.bpm.value
    // Block bodies on purpose: the bank returns a TempoWriteOutcome, and an expression body
    // would leak it as the script API's return type. Master 1 can never be a follower.
    fun setBpm(bpm: Double) {
        speedMasters.setBpm(null, bpm, uk.me.cormack.lighting7.models.SpeedMasterSource.MANUAL)
    }
    fun tapTempo() {
        speedMasters.tap(null)
    }

    /** A specific speed master's clock by 1-based index, or master 1 when unknown. */
    fun speedMaster(index: Int): MasterClock =
        speedMasters.clockFor(speedMasters.masterStates().indexOfFirst { it.index == index })

    /**
     * The uuid of the speed master at 1-based [index], for the `speedMasterUuid` /
     * `rateSpeedMasterUuid` parameters below. Null when no such master exists, which those
     * parameters read as "master 1" and "unscaled" — the same degradation a dangling stored
     * reference gets, rather than an apply that throws mid-show.
     */
    fun speedMasterUuidAt(index: Int): java.util.UUID? =
        speedMasters.masterStates().firstOrNull { it.index == index }?.uuid

    // --- Effect Construction ---

    /**
     * Build an effect by registered type name, e.g. `effect("SineWave", "min" to "40")`.
     *
     * Type names are matched case-insensitively, ignoring spaces and underscores, against
     * built-in effects and any user effect in `fx_definitions`. Parameter names and their string
     * formats are the ones the effect declares — `GET /api/rest/fx/library` lists them, and a
     * `colour` parameter accepts the extended syntax (`"red;w128;uv64"`) or a `tmpl:{uuid}`
     * template reference.
     *
     * @throws IllegalArgumentException if no effect is registered under [id]
     */
    fun effect(id: String, params: Map<String, String>): Effect =
        show.fxRegistry.createEffectWithTemplates(show.templateRegistry, id, params)

    /**
     * [effect] with parameters as pairs: `effect("Pulse", "min" to "0", "max" to "200")`, and the
     * no-parameter form `effect("Pulse")`. Deliberately not a default on the [Map] overload —
     * that would make the bare `effect("Pulse")` call ambiguous between the two.
     */
    fun effect(id: String, vararg params: Pair<String, String>): Effect =
        effect(id, params.toMap())

    // --- Fixture FX Extensions (implicit engine) ---

    fun <T> T.dimmerFx(
        effect: Effect,
        timing: FxTiming = FxTiming(),
        blendMode: BlendMode = BlendMode.OVERRIDE,
        speedMasterUuid: java.util.UUID? = null,
        rateSpeedMasterUuid: java.util.UUID? = null,
    ): Long where T : FixtureTarget, T : WithDimmer =
        this.applyDimmerFx(fxEngine, effect, timing, blendMode, speedMasterUuid, rateSpeedMasterUuid)

    fun <T> T.uvFx(
        effect: Effect,
        timing: FxTiming = FxTiming(),
        blendMode: BlendMode = BlendMode.OVERRIDE,
        speedMasterUuid: java.util.UUID? = null,
        rateSpeedMasterUuid: java.util.UUID? = null,
    ): Long where T : FixtureTarget, T : WithUv =
        this.applyUvFx(fxEngine, effect, timing, blendMode, speedMasterUuid, rateSpeedMasterUuid)

    fun <T> T.colourFx(
        effect: Effect,
        timing: FxTiming = FxTiming(),
        blendMode: BlendMode = BlendMode.OVERRIDE,
        speedMasterUuid: java.util.UUID? = null,
        rateSpeedMasterUuid: java.util.UUID? = null,
    ): Long where T : FixtureTarget, T : WithColour =
        this.applyColourFx(fxEngine, effect, timing, blendMode, speedMasterUuid, rateSpeedMasterUuid)

    fun <T> T.positionFx(
        effect: Effect,
        timing: FxTiming = FxTiming(),
        blendMode: BlendMode = BlendMode.OVERRIDE,
        speedMasterUuid: java.util.UUID? = null,
        rateSpeedMasterUuid: java.util.UUID? = null,
    ): Long where T : FixtureTarget, T : WithPosition =
        this.applyPositionFx(fxEngine, effect, timing, blendMode, speedMasterUuid, rateSpeedMasterUuid)

    fun FixtureTarget.fx(block: FxBuilder.() -> Unit) {
        FxBuilder(fxEngine, this.targetKey).block()
    }

    fun FixtureTarget.clearFx(): Int =
        fxEngine.removeEffectsForFixture(this.targetKey)

    // --- Group FX Extensions (implicit engine) ---

    fun <T> FixtureGroup<T>.dimmerFx(
        effect: Effect,
        timing: FxTiming = FxTiming(),
        blendMode: BlendMode = BlendMode.OVERRIDE,
        distribution: DistributionStrategy = DistributionStrategy.fromName(metadata.defaultDistributionName),
        speedMasterUuid: java.util.UUID? = null,
        rateSpeedMasterUuid: java.util.UUID? = null,
    ): Long where T : GroupableFixture, T : WithDimmer =
        this.applyDimmerFx(
            fxEngine, effect, timing, blendMode, distribution, speedMasterUuid, rateSpeedMasterUuid,
        )

    fun <T> FixtureGroup<T>.uvFx(
        effect: Effect,
        timing: FxTiming = FxTiming(),
        blendMode: BlendMode = BlendMode.OVERRIDE,
        distribution: DistributionStrategy = DistributionStrategy.fromName(metadata.defaultDistributionName),
        speedMasterUuid: java.util.UUID? = null,
        rateSpeedMasterUuid: java.util.UUID? = null,
    ): Long where T : GroupableFixture, T : WithUv =
        this.applyUvFx(
            fxEngine, effect, timing, blendMode, distribution, speedMasterUuid, rateSpeedMasterUuid,
        )

    fun <T> FixtureGroup<T>.colourFx(
        effect: Effect,
        timing: FxTiming = FxTiming(),
        blendMode: BlendMode = BlendMode.OVERRIDE,
        distribution: DistributionStrategy = DistributionStrategy.fromName(metadata.defaultDistributionName),
        speedMasterUuid: java.util.UUID? = null,
        rateSpeedMasterUuid: java.util.UUID? = null,
    ): Long where T : GroupableFixture, T : WithColour =
        this.applyColourFx(
            fxEngine, effect, timing, blendMode, distribution, speedMasterUuid, rateSpeedMasterUuid,
        )

    fun <T> FixtureGroup<T>.positionFx(
        effect: Effect,
        timing: FxTiming = FxTiming(),
        blendMode: BlendMode = BlendMode.OVERRIDE,
        distribution: DistributionStrategy = DistributionStrategy.fromName(metadata.defaultDistributionName),
        speedMasterUuid: java.util.UUID? = null,
        rateSpeedMasterUuid: java.util.UUID? = null,
    ): Long where T : GroupableFixture, T : WithPosition =
        this.applyPositionFx(
            fxEngine, effect, timing, blendMode, distribution, speedMasterUuid, rateSpeedMasterUuid,
        )

    inline fun <reified T : GroupableFixture> FixtureGroup<T>.fx(
        block: GroupFxBuilder<T>.() -> Unit,
    ): List<Long> {
        val builder = GroupFxBuilder(fxEngine, this)
        builder.block()
        return builder.effectIds()
    }

    fun FixtureGroup<*>.clearFx(): Int {
        val groupRemoved = fxEngine.removeEffectsForGroup(name)
        val fixtureRemoved = allMembers.sumOf { fxEngine.removeEffectsForFixture(it.key) }
        return groupRemoved + fixtureRemoved
    }
}

object FxApplicationScriptConfiguration : ScriptCompilationConfiguration(
    {
        defaultImports(
            "uk.me.cormack.lighting7.fixture.*",
            "uk.me.cormack.lighting7.fixture.group.*",
            "uk.me.cormack.lighting7.fixture.trait.*",
            "uk.me.cormack.lighting7.fx.*",
            "uk.me.cormack.lighting7.fx.group.*",
            "java.awt.Color",
        )

        jvm {
            dependenciesFromCurrentContext(wholeClasspath = true)
            jvmTarget("17")
        }
        ide {
            acceptedLocations(ScriptAcceptedLocation.Everywhere)
        }

        baseClass(FxApplicationScript::class)
    },
)
