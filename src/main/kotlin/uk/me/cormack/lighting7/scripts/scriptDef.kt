package uk.me.cormack.lighting7.scripts

import kotlinx.coroutines.*
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.show.Show
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget

@KotlinScript(
    fileExtension = "lightng.kts",
    compilationConfiguration = LightingScriptConfiguration::class
)
abstract class LightingScript(
    private val show: Show,
    val fixtures: Fixtures.FixturesWithTransaction,
    val fxEngine: FxEngine,
    val scriptName: String,
    val step: Int,
    val coroutineScope: CoroutineScope,
) {
    /** Access to the global master clock (master 1) for tempo control */
    val masterClock: MasterClock get() = fxEngine.masterClock

    /** The show's speed-master bank; master 1 is the global tempo. */
    val speedMasters: SpeedMasterBank get() = fxEngine.speedMasters

    /** Current BPM value (master 1) */
    val bpm: Double get() = masterClock.bpm.value

    /**
     * Set master 1's BPM. Routed through the bank so the change is tracked, pushed to
     * clients, and written through to the stored default.
     */
    fun setBpm(bpm: Double) =
        speedMasters.setBpm(null, bpm, uk.me.cormack.lighting7.models.SpeedMasterSource.MANUAL)

    /** Tap tempo (master 1) - call repeatedly to set BPM from timing */
    fun tapTempo() = speedMasters.tap(null)

    /** A specific speed master's clock by 1-based index, or master 1 when unknown. */
    fun speedMaster(index: Int): MasterClock =
        speedMasters.clockFor(speedMasters.masterStates().indexOfFirst { it.index == index })

    /**
     * The uuid of the speed master at 1-based [index], for the `speedMasterUuid` /
     * `rateSpeedMasterUuid` parameters on the `applyXxxFx` extensions. Null when no such master
     * exists, which those parameters read as "master 1" and "unscaled".
     */
    fun speedMasterUuidAt(index: Int): java.util.UUID? =
        speedMasters.masterStates().firstOrNull { it.index == index }?.uuid

    /**
     * Build an effect by registered type name, e.g. `effect("SineWave", "min" to "40")`.
     *
     * The registry is the single effect vocabulary — the same one the UI, cues and Looks use,
     * including user effects from `fx_definitions`. Names match case-insensitively, ignoring
     * spaces and underscores; `GET /api/rest/fx/library` lists each type's parameters.
     *
     * @throws IllegalArgumentException if no effect is registered under [id]
     */
    fun effect(id: String, params: Map<String, String>): Effect =
        show.fxRegistry.createEffectWithTemplates(show.templateRegistry, id, params)

    /**
     * [effect] with parameters as pairs, and the no-parameter form `effect("Pulse")`.
     * Deliberately not a default on the [Map] overload — that would make the bare
     * `effect("Pulse")` call ambiguous between the two.
     */
    fun effect(id: String, vararg params: Pair<String, String>): Effect =
        effect(id, params.toMap())

    fun controller(subnet: Int, universe: Int): DmxController = fixtures.controller(Universe(subnet, universe))
    inline fun <reified T: Fixture> fixture(key: String): T = fixtures.fixture(key)
    inline fun <reified T: Fixture> group(key: String): FixtureGroup<T> = fixtures.group(key)
}

object LightingScriptConfiguration : ScriptCompilationConfiguration(
    {
        // adds implicit import statements (in this case `import kotlin.script.experimental.dependencies.DependsOn`, etc.)
        // to each script on compilation
        defaultImports(
            "uk.me.cormack.lighting7.fixture.*",
            "uk.me.cormack.lighting7.fixture.dmx.*",
            "uk.me.cormack.lighting7.fixture.hue.*",
            "uk.me.cormack.lighting7.fixture.group.*",
            "uk.me.cormack.lighting7.fx.*",
            "java.awt.Color",
            "uk.me.cormack.lighting7.dmx.*",
            "kotlinx.coroutines.*",
        )

        jvm {
            dependenciesFromCurrentContext(wholeClasspath = true)
            jvmTarget("17")
        }
        ide {
            acceptedLocations(ScriptAcceptedLocation.Everywhere)
        }

        baseClass(LightingScript::class)
    }
)
