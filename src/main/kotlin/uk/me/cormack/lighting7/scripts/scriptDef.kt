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
            "uk.me.cormack.lighting7.fx.effects.*",
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
