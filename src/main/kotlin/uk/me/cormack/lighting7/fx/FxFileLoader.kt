package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
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
                System.err.println("FxFileLoader: fx/ resource directory not found")
                return 0
            }

        // Read the index file which lists all .fx.kts files
        val indexResource = this::class.java.classLoader.getResource("fx/index.txt")
        if (indexResource == null) {
            System.err.println("FxFileLoader: fx/index.txt not found, no built-in effects to load")
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
                    System.err.println("FxFileLoader: Could not find resource fx/$relativePath")
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
                System.err.println("FxFileLoader: Error loading fx/$relativePath: ${e.message}")
                e.printStackTrace()
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
        fun compileOne(p: ParsedFx): CompiledFxScript? {
            val compiled = try {
                compiler.compile(p.scriptBody, p.effectMode)
            } catch (e: Exception) {
                System.err.println("FxFileLoader: Error compiling fx/${p.relativePath}: ${e.message}")
                null
            }
            progress?.invoke(completed.incrementAndGet(), total)
            return compiled
        }

        val compiledResults: List<Pair<ParsedFx, CompiledFxScript?>> = if (parallel && parsed.size > 1) {
            runBlocking {
                coroutineScope {
                    parsed.map { p -> async(Dispatchers.Default) { p to compileOne(p) } }.awaitAll()
                }
            }
        } else {
            parsed.map { p -> p to compileOne(p) }
        }

        // Stage 3: register (serial). Also per-file try/catch so a bad factory/registration for
        // one effect doesn't abort the rest.
        var count = 0
        for ((p, compiled) in compiledResults) {
            if (compiled == null || !compiled.isSuccess) {
                System.err.println("FxFileLoader: Failed to compile fx/${p.relativePath}:")
                compiled?.diagnostics?.forEach { d ->
                    System.err.println("  ${d.severity}: ${d.message} ${d.location ?: ""}")
                }
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
                System.err.println("FxFileLoader: Error registering fx/${p.relativePath}: ${e.message}")
                e.printStackTrace()
            }
        }

        println("FxFileLoader: Loaded $count built-in effects")
        return count
    }

    companion object {
        private val FRONTMATTER_REGEX = Regex("""/\*---\s*\n(.*?)\n\s*---\*/""", RegexOption.DOT_MATCHES_ALL)

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

                if (currentParam != null && (trimmed.startsWith("  ") || trimmed.startsWith("\t")) && !trimmed.startsWith("- ")) {
                    // Continuation of current param
                    val paramLine = trimmed.trim()
                    if (paramLine.contains(":")) {
                        val (k, v) = paramLine.split(":", limit = 2)
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
