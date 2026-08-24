package uk.me.cormack.lighting7.state

import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.baseClass
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.jvm.compilationCache
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.CompiledScriptJarsCache

private val logger = LoggerFactory.getLogger("ScriptCache")

/**
 * Builds the [ScriptingHostConfiguration] shared by every Kotlin scripting host in the app
 * (the FX-calc compiler in [uk.me.cormack.lighting7.fx.FxScriptCompiler] and the show/cue
 * script host in [uk.me.cormack.lighting7.show.Show]).
 *
 * When `scriptCache.enabled` (default `true`) it installs a [CompiledScriptJarsCache] under
 * `<appDataDir>/script-cache` (override with `scriptCache.path`). The first compile of each
 * distinct script writes a `<hash>.jar`; every later boot — and every project switch within
 * a run — loads the compiled bytecode instead of paying the Kotlin-compiler cold start again.
 * This is the single biggest lever on start-up time, since built-in FX effects, cue-trigger
 * scripts and user effects are otherwise recompiled from scratch on every boot.
 *
 * ### Cache-key correctness
 * The jar file name is a SHA-256 of the script source, the template base class, and a **build
 * fingerprint** of the current classpath (see [computeBuildFingerprint]). Because all script
 * templates compile with `dependenciesFromCurrentContext(wholeClasspath = true)`, a compiled
 * script links against the app's own classes; the fingerprint must therefore change whenever
 * those classes change, or a rebuild could load bytecode compiled against a class that has since
 * changed shape (blowing up with `NoSuchMethodError` at evaluation). The fingerprint is computed
 * once per process — see [buildFingerprint] — not per script and not per [State], so it also
 * avoids re-stat-ing the whole classpath per compile.
 */
fun buildScriptingHostConfiguration(config: ApplicationConfig): ScriptingHostConfiguration {
    val enabled = config.optionalBoolean("scriptCache.enabled", default = true)
    if (!enabled) {
        logger.info("Compiled-script disk cache disabled (scriptCache.enabled=false)")
        return defaultJvmScriptingHostConfiguration
    }

    val cacheDir = config.optionalString("scriptCache.path")?.let { Paths.get(it) }
        ?: appDataDir().resolve("script-cache")

    return try {
        Files.createDirectories(cacheDir)
        logger.info("Compiled-script disk cache enabled at {}", cacheDir)
        ScriptingHostConfiguration(defaultJvmScriptingHostConfiguration) {
            jvm {
                compilationCache(
                    CompiledScriptJarsCache { source, compilationConfiguration ->
                        cacheDir.resolve("${cacheKey(source, compilationConfiguration, buildFingerprint)}.jar").toFile()
                    },
                )
            }
        }
    } catch (e: Exception) {
        // A broken cache dir must never stop the app from booting — fall back to no cache.
        logger.warn("Failed to initialise compiled-script cache at {} — continuing without it", cacheDir, e)
        defaultJvmScriptingHostConfiguration
    }
}

/** Per-script compiled-jar file name: source + template base class + the shared build fingerprint. */
@OptIn(ExperimentalStdlibApi::class)
private fun cacheKey(
    source: SourceCode,
    compilationConfiguration: ScriptCompilationConfiguration,
    buildFingerprint: String,
): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(source.text.toByteArray())
    compilationConfiguration[ScriptCompilationConfiguration.baseClass]?.typeName?.let {
        md.update(it.toByteArray())
    }
    md.update(buildFingerprint.toByteArray())
    return md.digest().toHexString()
}

/**
 * The process-wide build fingerprint.
 *
 * `java.class.path` cannot change within a JVM, so this is a pure function of the process and is
 * memoised. That matters well beyond tidiness: [buildScriptingHostConfiguration] runs once per
 * [State], and the test suite builds a `State` per test — without this, every one of them walked
 * the whole classpath again (~55 ms for ~5170 files under `build/classes`).
 *
 * A rebuild *while the app is live* under `gradle run` therefore keeps the fingerprint it started
 * with. That is correct: the running JVM is still executing the old classes, so scripts compiled
 * against them are still linked correctly, and picking up new classes already requires a restart.
 */
private val buildFingerprint: String by lazy { computeBuildFingerprint() }

/**
 * A fingerprint of the current JVM classpath that changes whenever the app's compiled code
 * changes, so cached jars are invalidated on rebuild. For **directory** classpath entries (the
 * `build/classes/kotlin/main` dir under `gradle run` — where rebuilds actually happen) we fold in
 * the newest last-modified time among all contained files; a nested `.class` rebuild bumps that
 * even though the directory entry's own mtime does not. For **file** entries (jars, the packaged
 * case) the entry's own mtime + size suffice. Call [buildFingerprint] rather than this directly.
 */
@OptIn(ExperimentalStdlibApi::class)
private fun computeBuildFingerprint(): String {
    val md = MessageDigest.getInstance("SHA-256")
    val classpath = System.getProperty("java.class.path").orEmpty()
    for (entry in classpath.split(File.pathSeparatorChar)) {
        if (entry.isBlank()) continue
        val file = File(entry)
        md.update(entry.toByteArray())
        when {
            file.isDirectory -> {
                var newest = file.lastModified()
                file.walkTopDown().forEach { if (it.isFile) newest = maxOf(newest, it.lastModified()) }
                md.update(newest.toString().toByteArray())
            }
            file.isFile -> {
                md.update(file.lastModified().toString().toByteArray())
                md.update(file.length().toString().toByteArray())
            }
        }
    }
    return md.digest().toHexString()
}
