package uk.me.cormack.lighting7.scripts

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.show.Fixtures
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
    val fixtures: Fixtures.FixturesWithTransaction,
    val scriptName: String,
    val step: Int,
    val sceneName: String,
    val sceneIsActive: Boolean,
    val settings: Map<String, String>,
) {
    fun controller(subnet: Int, universe: Int): DmxController = fixtures.controller(Universe(subnet, universe))
    inline fun <reified T: Fixture> fixture(key: String): T = fixtures.fixture(key)
    fun fixtureGroup(groupName: String): List<Fixture> = fixtures.fixtureGroup(groupName)
}

object LightingScriptConfiguration : ScriptCompilationConfiguration(
    {
        // adds implicit import statements (in this case `import kotlin.script.experimental.dependencies.DependsOn`, etc.)
        // to each script on compilation
        defaultImports(
            "uk.me.cormack.lighting7.fixture.*",
            "uk.me.cormack.lighting7.fixture.dmx.*",
            "uk.me.cormack.lighting7.fixture.hue.*",
            "uk.me.cormack.lighting7.scriptSettings.*",
            "java.awt.Color",
            "uk.me.cormack.lighting7.dmx.*",
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
