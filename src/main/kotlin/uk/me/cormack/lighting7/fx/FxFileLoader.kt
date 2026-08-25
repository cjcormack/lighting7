package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loads built-in FX definition scripts from `.fx.kts` resource files.
 *
 * Each file has YAML-like frontmatter in a block comment delimited by
 * slash-star-triple-dash and triple-dash-star-slash, followed by the
 * calculate script body. The frontmatter is parsed into [FxFileMetadata]
 * and the script body is compiled via [FxScriptCompiler].
 */
class FxFileLoader(
    private val compiler: FxScriptCompiler,
) {
    /** A built-in effect file parsed but not yet compiled. */
    private data class ParsedFx(
        val relativePath: String,
        val metadata: FxFileMetadata,
        val scriptBody: String,
        val effectMode: EffectMode,
        val outputType: FxOutputType,
        val parameters: List<ParameterInfo>,
    )

    /**
     * Load all .fx.kts files from the `fx/` resource directory and register them.
     *
     * Compilation dominates cold-boot time, so it runs in three stages: parse every file
     * (cheap), compile the bodies — [parallel] fans them across [Dispatchers.Default] — then
     * register the successful ones serially ([FxRegistry] is not built for concurrent writes).
     *
     * @param registry The FxRegistry to register effects into
     * @param parallel Compile bodies concurrently (default true). Set false to force sequential.
     * @param progress Optional callback invoked as each body finishes compiling, with
     *   `(done, total)` — used to drive the boot progress bar. May fire from worker threads.
     * @return Number of effects successfully loaded
     */
    fun loadBuiltInEffects(
        registry: FxRegistry,
        parallel: Boolean = true,
        progress: ((done: Int, total: Int) -> Unit)? = null,
    ): Int {
        this::class.java.classLoader.getResource("fx")
            ?: run {
                logger.error("fx/ resource directory not found — no built-in effects will load")
                return 0
            }

        // Read the index file which lists all .fx.kts files
        val indexResource = this::class.java.classLoader.getResource("fx/index.txt")
        if (indexResource == null) {
            logger.error("fx/index.txt not found — no built-in effects will load")
            return 0
        }

        val files = indexResource.readText().lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }

        // Stage 1: parse (cheap, sequential).
        val parsed = files.mapNotNull { relativePath ->
            try {
                val resource = this::class.java.classLoader.getResource("fx/$relativePath")
                if (resource == null) {
                    logger.error("fx/index.txt lists fx/{} but that resource is missing", relativePath)
                    return@mapNotNull null
                }
                val (metadata, scriptBody) = parseFxFile(resource.readText())
                ParsedFx(
                    relativePath = relativePath,
                    metadata = metadata,
                    scriptBody = scriptBody,
                    effectMode = EffectMode.valueOf(metadata.effectMode),
                    outputType = FxOutputType.valueOf(metadata.outputType),
                    parameters = metadata.parameters.map { p ->
                        ParameterInfo(p.name, p.type, p.default, p.description)
                    },
                )
            } catch (e: Exception) {
                logger.error("Failed to parse built-in fx/{}", relativePath, e)
                null
            }
        }

        val total = parsed.size
        val completed = AtomicInteger(0)

        // Stage 2: compile. Each compile is isolated in try/catch so one file's failure — e.g. a
        // corrupt/unreadable cached .jar (partial write after a kill, disk full) that makes the
        // scripting host throw — loses only that effect, not the whole built-in library. (Without
        // this, in parallel mode `awaitAll` would rethrow and cancel the siblings.) Parallel mode
        // shares one BasicJvmScriptingHost across workers; the FxScriptCompiler cache is a
        // ConcurrentHashMap and this path is exercised by FxRegistryTest, but if a JDK/host combo
        // ever proves unsafe under concurrency, `fx.parallelCompile=false` forces sequential.
        // `progress` advances only when an effect reaches its FINAL state. A failure in the
        // parallel pass is not final — it may still be retried below — so counting it here
        // would drive the (monotonic) boot bar to done == total before the sequential retry
        // even starts, leaving the UI reporting "compiled" while it recompiles in silence.
        fun compileOne(p: ParsedFx, isFinalAttempt: Boolean): CompiledFxScript? {
            val compiled = try {
                compiler.compile(p.scriptBody, p.effectMode)
            } catch (e: Exception) {
                logger.warn("Compiling fx/{} threw", p.relativePath, e)
                null
            }
            val succeeded = compiled != null && compiled.isSuccess
            if (succeeded || isFinalAttempt) progress?.invoke(completed.incrementAndGet(), total)
            return compiled
        }

        val compileInParallel = parallel && parsed.size > 1
        val firstPass: List<Pair<ParsedFx, CompiledFxScript?>> = if (compileInParallel) {
            runBlocking {
                coroutineScope {
                    parsed.map { p -> async(Dispatchers.Default) { p to compileOne(p, false) } }.awaitAll()
                }
            }
        } else {
            parsed.map { p -> p to compileOne(p, true) }
        }

        // Stage 2b: re-run the failures with no concurrency, but ONLY when the first pass was
        // actually parallel — the sole thing this pass can establish is whether contention on
        // the scripting host shared across workers caused the failure. After a sequential first
        // pass the conditions are identical, so a retry would just double the compile cost and
        // reach the same answer.
        //
        // Note what this cannot fix: when the compiled-script disk cache is enabled
        // (ScriptCache.kt, the default) the host resolves a jar keyed by source + config +
        // build fingerprint, so a *corrupt cached jar* fails identically on the retry. Clearing
        // `<appDataDir>/script-cache` is the fix for that, which is why the failure log below
        // says so rather than blaming the build outright.
        val failedFirstPass = firstPass.count { (_, c) -> c == null || !c.isSuccess }
        val compiledResults: List<Pair<ParsedFx, CompiledFxScript?>> = if (failedFirstPass == 0 || !compileInParallel) {
            firstPass
        } else {
            logger.warn(
                "{} of {} built-in effect(s) failed the parallel compile; retrying sequentially",
                failedFirstPass, total,
            )
            firstPass.map { (p, compiled) ->
                if (compiled != null && compiled.isSuccess) {
                    p to compiled
                } else {
                    // compile() caches failures (so a user hammering "Test" on a broken script
                    // doesn't re-run the compiler each time), so drop the entry or the retry
                    // would just hand back the cached error without recompiling.
                    compiler.invalidate(p.scriptBody, p.effectMode)
                    val retried = compileOne(p, true)
                    if (retried != null && retried.isSuccess) {
                        logger.warn(
                            "fx/{} compiled on sequential retry — the parallel compile pass is " +
                                "unreliable on this JVM; consider fx.parallelCompile=false",
                            p.relativePath,
                        )
                    }
                    p to (retried ?: compiled)
                }
            }
        }

        // Stage 3: register (serial). Also per-file try/catch so a bad factory/registration for
        // one effect doesn't abort the rest.
        var count = 0
        for ((p, compiled) in compiledResults) {
            if (compiled == null || !compiled.isSuccess) {
                // ERROR, not a warning: built-in effects ship with the app, so the library is
                // now incomplete for everyone on this build. Name the script cache explicitly —
                // a poisoned cached jar produces exactly this and is fixed by clearing it, and
                // it is not something an operator would guess from a bare compile error.
                logger.error(
                    "Failed to compile built-in fx/{}: {}. If this persists across restarts, try " +
                        "clearing the compiled-script cache (<appDataDir>/script-cache) or set " +
                        "scriptCache.enabled=false to rule it out.",
                    p.relativePath,
                    compiled?.diagnostics
                        ?.takeIf { it.isNotEmpty() }
                        ?.joinToString("; ") { d -> "${d.severity}: ${d.message} ${d.location ?: ""}" }
                        ?: "no diagnostics (the compiler threw — see the preceding warning)",
                )
                continue
            }

            try {
                val factory = ScriptEffectAdapter.createFactory(
                    compiled = compiled,
                    schema = p.parameters,
                    effectName = p.metadata.name,
                    outputType = p.outputType,
                    defaultStepTiming = p.metadata.defaultStepTiming,
                )

                val timingSource = try {
                    TimingSource.valueOf(p.metadata.timingSource)
                } catch (_: Exception) {
                    TimingSource.BEAT
                }

                registry.register(EffectRegistration(
                    id = p.metadata.id,
                    aliases = p.metadata.aliases?.toSet() ?: emptySet(),
                    name = p.metadata.name,
                    category = p.metadata.category,
                    outputType = p.outputType,
                    effectMode = p.effectMode,
                    parameters = p.parameters,
                    compatibleProperties = p.metadata.compatibleProperties,
                    source = EffectSource.BUILT_IN,
                    script = p.scriptBody,
                    defaultStepTiming = p.metadata.defaultStepTiming,
                    timingSource = timingSource,
                    factory = factory,
                ))

                count++
            } catch (e: Exception) {
                logger.error("Failed to register built-in fx/{}", p.relativePath, e)
            }
        }

        // Compare against the number of files the INDEX listed, not `total` (= parse successes).
        // Measuring against parse successes cannot see anything lost in stage 1: if a regex or
        // enum change made every file fail to parse, `total` and `count` would both be 0 and this
        // would cheerfully report success at INFO while the effect library was empty.
        if (count < files.size) {
            logger.error(
                "Loaded only {} of the {} built-in effects listed in fx/index.txt — the effect library is incomplete",
                count, files.size,
            )
        } else {
            logger.info("Loaded {} built-in effects", count)
        }
        return count
    }

    companion object {
        private val logger = LoggerFactory.getLogger(FxFileLoader::class.java)

        private val FRONTMATTER_REGEX = Regex("""/\*---\s*\n(.*?)\n\s*---\*/""", RegexOption.DOT_MATCHES_ALL)

        /** The keys a `parameters:` list item may carry; see [parseSimpleYaml]'s continuation branch. */
        private val PARAM_KEYS = setOf("name", "type", "default", "description")

        /**
         * Parse an .fx.kts file into metadata and script body.
         *
         * The frontmatter uses a block comment with triple-dash delimiters.
         * The script body is everything after the frontmatter block.
         */
        fun parseFxFile(content: String): Pair<FxFileMetadata, String> {
            val match = FRONTMATTER_REGEX.find(content)
                ?: throw IllegalArgumentException("No frontmatter found")

            val yamlContent = match.groupValues[1]
            val scriptBody = content.substring(match.range.last + 1).trimStart('\n', '\r')

            val metadata = parseSimpleYaml(yamlContent)
            return Pair(metadata, scriptBody)
        }

        /**
         * Parse the simplified YAML frontmatter into [FxFileMetadata].
         *
         * Supports:
         * - Simple key: value pairs
         * - Array values: [item1, item2]
         * - Nested list items (for parameters): - name: value
         */
        private fun parseSimpleYaml(yaml: String): FxFileMetadata {
            val lines = yaml.lines()
            var id = ""
            var name = ""
            var category = ""
            var outputType = "SLIDER"
            var effectMode = "STANDARD"
            var defaultStepTiming = false
            var timingSource = "BEAT"
            var compatibleProperties = listOf<String>()
            var aliases: List<String>? = null
            val parameters = mutableListOf<FxFileParameter>()

            var inParameters = false
            var currentParam: MutableMap<String, String>? = null

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                if (trimmed.startsWith("- ") && inParameters) {
                    // Save previous param if any
                    currentParam?.let { map ->
                        parameters.add(FxFileParameter(
                            name = map["name"] ?: "",
                            type = map["type"] ?: "string",
                            default = map["default"] ?: "",
                            description = map["description"] ?: "",
                        ))
                    }
                    // Start new param
                    currentParam = mutableMapOf()
                    // Parse inline key-value on the "- " line
                    val afterDash = trimmed.removePrefix("- ").trim()
                    if (afterDash.contains(":")) {
                        val (k, v) = afterDash.split(":", limit = 2)
                        currentParam[k.trim()] = v.trim().removeSurrounding("\"")
                    }
                    continue
                }

                // Continuation of the current param: `type`, `default`, `description`. Indentation
                // has to be read off the *raw* line — `trimmed` can never start with whitespace,
                // and testing it here meant this branch never ran, so every built-in parameter
                // parsed with type "string", no default and no description. Nothing noticed for a
                // long time because the script bodies ask for a type explicitly
                // (`params.ubyte("min")`) rather than dispatching on the declared one, and because
                // every authoring surface sends a full parameter map. The cost was
                // `TypedParams.raw`'s schema fallback being inert: an effect created without an
                // explicit value got 0 / black / false rather than its documented default, so
                // `effect("SineWave")` was a dead effect.
                //
                // Restricted to the keys a parameter actually has, because making the branch live
                // also made it greedy: it absorbs *any* indented line while a parameter is open, so
                // an indented top-level key written after the parameter list (`  compatibleProperties:
                // [dimmer]`) would be swallowed and the effect would register with no compatible
                // properties and no error. None of the 28 shipped files do that, but this parser has
                // just cost the built-ins their whole metadata once; an unrecognised key falls
                // through to the top-level `when` instead.
                if (currentParam != null &&
                    (line.startsWith("  ") || line.startsWith("\t")) &&
                    !trimmed.startsWith("- ") &&
                    trimmed.substringBefore(":").trim() in PARAM_KEYS
                ) {
                    if (trimmed.contains(":")) {
                        val (k, v) = trimmed.split(":", limit = 2)
                        currentParam[k.trim()] = v.trim().removeSurrounding("\"")
                    }
                    continue
                }

                // Not in a parameter entry anymore
                if (currentParam != null) {
                    parameters.add(FxFileParameter(
                        name = currentParam["name"] ?: "",
                        type = currentParam["type"] ?: "string",
                        default = currentParam["default"] ?: "",
                        description = currentParam["description"] ?: "",
                    ))
                    currentParam = null
                }

                if (!trimmed.contains(":")) continue
                val (key, value) = trimmed.split(":", limit = 2).map { it.trim() }

                when (key) {
                    "id" -> id = value
                    "name" -> name = value
                    "category" -> category = value
                    "outputType" -> outputType = value
                    "effectMode" -> effectMode = value
                    "defaultStepTiming" -> defaultStepTiming = value.toBooleanStrictOrNull() ?: false
                    "timingSource" -> timingSource = value
                    "compatibleProperties" -> compatibleProperties = parseYamlArray(value)
                    "aliases" -> aliases = parseYamlArray(value)
                    "parameters" -> {
                        inParameters = true
                        // If it's an inline empty array
                        if (value == "[]") inParameters = false
                    }
                }
            }

            // Flush last param
            currentParam?.let { map ->
                parameters.add(FxFileParameter(
                    name = map["name"] ?: "",
                    type = map["type"] ?: "string",
                    default = map["default"] ?: "",
                    description = map["description"] ?: "",
                ))
            }

            return FxFileMetadata(
                id = id,
                name = name,
                category = category,
                outputType = outputType,
                effectMode = effectMode,
                defaultStepTiming = defaultStepTiming,
                timingSource = timingSource,
                compatibleProperties = compatibleProperties,
                aliases = aliases,
                parameters = parameters,
            )
        }

        private fun parseYamlArray(value: String): List<String> {
            val trimmed = value.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                return trimmed.removeSurrounding("[", "]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotBlank() }
            }
            return emptyList()
        }
    }
}

/**
 * Parsed metadata from an .fx.kts file's frontmatter.
 */
data class FxFileMetadata(
    val id: String,
    val name: String,
    val category: String,
    val outputType: String = "SLIDER",
    val effectMode: String = "STANDARD",
    val defaultStepTiming: Boolean = false,
    val timingSource: String = "BEAT",
    val compatibleProperties: List<String> = emptyList(),
    val aliases: List<String>? = null,
    val parameters: List<FxFileParameter> = emptyList(),
)

data class FxFileParameter(
    val name: String,
    val type: String,
    val default: String,
    val description: String = "",
)
