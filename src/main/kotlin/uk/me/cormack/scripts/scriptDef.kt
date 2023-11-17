package uk.me.cormack.scripts

import uk.me.cormack.show.Fixtures
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
        defaultImports("uk.me.cormack.fixture.*", "uk.me.cormack.fixture.dmx.*", "uk.me.cormack.fixture.hue.*", "java.awt.Color")

        jvm {
            dependenciesFromCurrentContext(wholeClasspath = true)
        }
        ide {
            acceptedLocations(ScriptAcceptedLocation.Everywhere)
        }

        baseClass(LightingScript::class)
    }
)
