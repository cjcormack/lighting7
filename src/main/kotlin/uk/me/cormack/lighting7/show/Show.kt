package uk.me.cormack.lighting7.show

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ticker
import kotlinx.coroutines.selects.select
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.Project
import uk.me.cormack.lighting7.models.Projects
import uk.me.cormack.lighting7.models.Script
import uk.me.cormack.lighting7.models.Scripts
import uk.me.cormack.lighting7.scripts.LightingScript
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.text.toByteArray

@OptIn(DelicateCoroutinesApi::class)
object Show {
    val fixtures = Fixtures()
    val compiledScripts = ConcurrentHashMap<String, ResultWithDiagnostics<CompiledScript>>()

    val project = transaction {
        Project.find {
            Projects.name eq "Halloween"
        }.first()
    }

    init {
        GlobalScope.launch {
//            runShow()
        }
    }

    suspend fun start() {
        evalScriptByName("initial-state")
    }

    fun close() {
    }

    private fun CoroutineScope.runShow() {
        var isClosed = false

        val ticker = ticker(500)

        var consecutiveErrors = 0

        var step = 0

        launch(newSingleThreadContext("LightingShow")) {
            while(coroutineContext.isActive && !isClosed) {
                try {
                    select<Unit> {
                        ticker.onReceiveCatching {
                            if (it.isClosed) {
                                return@onReceiveCatching
                            }

                            evalScriptByName("runloop", step)
                            step++
                        }
                    }
                    consecutiveErrors = 0
                } catch (e: Exception) {
                    if (consecutiveErrors == 0) {
                        e.printStackTrace()
                    }
                    consecutiveErrors++

//                    if (consecutiveErrors > 20) {
//                        // if too many errors, we'll bail out and let this thing stop. A restart of NK is needed.
//                        println("Too many consecutive errors")
//                        throw e
//                    }
                    delay(10_000)
                }
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun String.cacheKey(): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(this.toByteArray()).toHexString()
    }

    suspend fun evalScriptByName(scriptName: String, step: Int = 0): EvaluationResult {
        val script = transaction {
            Script.find {
                (Scripts.name eq scriptName) and
                (Scripts.project eq project.id)
            }.first()
        }

        println("Running ${script.name}")

        val compiledScript = compileLiteralScript(script.script).valueOrThrow()

        return runLiteralScript(compiledScript, scriptName, step).valueOrThrow()
    }

    private suspend fun doCompileLiteralScript(key: String, script: String): ResultWithDiagnostics<CompiledScript> {
        val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<LightingScript>()

        val compiledScript = BasicJvmScriptingHost().compiler(script.toScriptSource(), compilationConfiguration)
        compiledScripts[key] = compiledScript

        return compiledScript
    }

    suspend fun compileLiteralScript(script: String): ResultWithDiagnostics<CompiledScript> {
        val key = script.cacheKey()

        return compiledScripts[key] ?: doCompileLiteralScript(key, script)
    }

    suspend fun runLiteralScript(compiledScript: CompiledScript, scriptName: String = "", step: Int = 0): ResultWithDiagnostics<EvaluationResult> =
        BasicJvmScriptingHost().evaluator(compiledScript, ScriptEvaluationConfiguration {
            providedProperties(Pair("fixtures", fixtures))
            providedProperties(Pair("scriptName", scriptName))
            providedProperties(Pair("step", step))
        })
}
