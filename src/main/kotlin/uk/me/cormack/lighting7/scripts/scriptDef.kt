package uk.me.cormack.lighting7.scripts

import uk.me.cormack.lighting7.show.Fixtures
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

@KotlinScript(
    fileExtension = "lightng.kts",
    compilationConfiguration = LightingScriptConfiguration::class
)
abstract class LightingScript(val fixtures: Fixtures, val scriptName: String, val step: Int)

object LightingScriptConfiguration : ScriptCompilationConfiguration(
    {
        // adds implicit import statements (in this case `import kotlin.script.experimental.dependencies.DependsOn`, etc.)
        // to each script on compilation
        defaultImports("uk.me.cormack.lighting7.fixture.*", "uk.me.cormack.lighting7.fixture.dmx.*", "uk.me.cormack.lighting7.fixture.hue.*", "java.awt.Color")

        jvm {
            dependenciesFromCurrentContext(wholeClasspath = true)
        }
        ide {
            acceptedLocations(ScriptAcceptedLocation.Everywhere)
        }

        baseClass(LightingScript::class)
    }
)
