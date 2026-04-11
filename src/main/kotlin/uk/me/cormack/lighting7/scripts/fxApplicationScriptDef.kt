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
 * Example:
 * ```
 * val wash = fixture<HexFixture>("front-wash-1")
 * val movers = group<MovingHead>("movers")
 *
 * wash.fx {
 *     dimmer(SineWave(), BeatDivision.HALF)
 *     colour(ColourCycle(), BeatDivision.ONE_BAR)
 * }
 *
 * movers.fx {
 *     dimmer(Pulse(), BeatDivision.QUARTER, distribution = DistributionStrategy.CENTER_OUT)
 * }
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
    val bpm: Double get() = masterClock.bpm.value
    fun setBpm(bpm: Double) = masterClock.setBpm(bpm)
    fun tapTempo() = masterClock.tap()

    // --- Palette ---

    val palette: List<ExtendedColour> get() = fxEngine.getPalette()
    fun setPalette(colours: List<ExtendedColour>) = fxEngine.setPalette(colours)

    // --- Fixture FX Extensions (implicit engine) ---

    fun <T> T.dimmerFx(
        effect: Effect,
        timing: FxTiming = FxTiming(),
        blendMode: BlendMode = BlendMode.OVERRIDE,
    ): Long where T : FixtureTarget, T : WithDimmer =
        this.applyDimmerFx(fxEngine, effect, timing, blendMode)

    fun <T> T.uvFx(
        effect: Effect,
        timing: FxTiming = FxTiming(),
        blendMode: BlendMode = BlendMode.OVERRIDE,
    ): Long where T : FixtureTarget, T : WithUv =
        this.applyUvFx(fxEngine, effect, timing, blendMode)

    fun <T> T.colourFx(
        effect: Effect,
        timing: FxTiming = FxTiming(),
        blendMode: BlendMode = BlendMode.OVERRIDE,
    ): Long where T : FixtureTarget, T : WithColour =
        this.applyColourFx(fxEngine, effect, timing, blendMode)

    fun <T> T.positionFx(
        effect: Effect,
        timing: FxTiming = FxTiming(),
        blendMode: BlendMode = BlendMode.OVERRIDE,
    ): Long where T : FixtureTarget, T : WithPosition =
        this.applyPositionFx(fxEngine, effect, timing, blendMode)

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
    ): Long where T : GroupableFixture, T : WithDimmer =
        this.applyDimmerFx(fxEngine, effect, timing, blendMode, distribution)

    fun <T> FixtureGroup<T>.uvFx(
        effect: Effect,
        timing: FxTiming = FxTiming(),
        blendMode: BlendMode = BlendMode.OVERRIDE,
        distribution: DistributionStrategy = DistributionStrategy.fromName(metadata.defaultDistributionName),
    ): Long where T : GroupableFixture, T : WithUv =
        this.applyUvFx(fxEngine, effect, timing, blendMode, distribution)

    fun <T> FixtureGroup<T>.colourFx(
        effect: Effect,
        timing: FxTiming = FxTiming(),
        blendMode: BlendMode = BlendMode.OVERRIDE,
        distribution: DistributionStrategy = DistributionStrategy.fromName(metadata.defaultDistributionName),
    ): Long where T : GroupableFixture, T : WithColour =
        this.applyColourFx(fxEngine, effect, timing, blendMode, distribution)

    fun <T> FixtureGroup<T>.positionFx(
        effect: Effect,
        timing: FxTiming = FxTiming(),
        blendMode: BlendMode = BlendMode.OVERRIDE,
        distribution: DistributionStrategy = DistributionStrategy.fromName(metadata.defaultDistributionName),
    ): Long where T : GroupableFixture, T : WithPosition =
        this.applyPositionFx(fxEngine, effect, timing, blendMode, distribution)

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
            "uk.me.cormack.lighting7.fx.effects.*",
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
