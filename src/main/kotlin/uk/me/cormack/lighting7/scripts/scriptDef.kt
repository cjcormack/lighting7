package uk.me.cormack.lighting7.scripts

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

@OptIn(ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class)
@KotlinScript(
    fileExtension = "lightng.kts",
    compilationConfiguration = LightingScriptConfiguration::class
)
abstract class LightingScript(val fixtures: Fixtures, val scriptName: String, val step: Int) {
    fun controller(subnet: Int, universe: Int): DmxController = fixtures.controller(subnet, universe)
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
            "java.awt.Color",
            "uk.me.cormack.lighting7.dmx.DmxController",
            "uk.me.cormack.lighting7.dmx.ArtNetController",
        )

        jvm {
            dependenciesFromCurrentContext(wholeClasspath = true)
        }
        ide {
            acceptedLocations(ScriptAcceptedLocation.Everywhere)
        }

        baseClass(LightingScript::class)
    }
)
