package uk.me.cormack.lighting7.scripts

import kotlinx.coroutines.*
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.MasterClock
import uk.me.cormack.lighting7.grpc.TrackDetails
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
    val sceneName: String,
    val sceneIsActive: Boolean,
    val settings: Map<String, String>,
    val coroutineScope: CoroutineScope,
    val currentTrack: TrackDetails?,
) {
    /** Access to the global master clock for tempo control */
    val masterClock: MasterClock get() = fxEngine.masterClock

    /** Current BPM value */
    val bpm: Double get() = masterClock.bpm.value

    /** Set the master clock BPM */
    fun setBpm(bpm: Double) = masterClock.setBpm(bpm)

    /** Tap tempo - call repeatedly to set BPM from timing */
    fun tapTempo() = masterClock.tap()

    fun controller(subnet: Int, universe: Int): DmxController = fixtures.controller(Universe(subnet, universe))
    inline fun <reified T: Fixture> fixture(key: String): T = fixtures.fixture(key)
    fun fixtureGroup(groupName: String): List<Fixture> = fixtures.fixtureGroup(groupName)
    inline fun <reified T: Fixture> group(key: String): FixtureGroup<T> = fixtures.group(key)
    fun runScene(sceneName: String) {
        show.runScene(sceneName)
    }
    fun startScene(sceneName: String) {
        show.startScene(sceneName)
    }
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
            "uk.me.cormack.lighting7.scriptSettings.*",
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
