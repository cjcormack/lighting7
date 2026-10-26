package uk.me.cormack.lighting7.fx

import io.sentry.ISpan
import uk.me.cormack.lighting7.models.timingOf
import uk.me.cormack.lighting7.models.Timing
import uk.me.cormack.lighting7.telemetry.ScriptCacheProbe
import uk.me.cormack.lighting7.telemetry.Tracing
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
        val fxMode: FxMode,
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
    fun loadBuiltInFx(
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
                    fxMode = FxMode.valueOf(metadata.fxMode),
                    outputType = FxOutputType.valueOf(metadata.outputType),
                    parameters = metadata.parameters.map(FxFileParameter::toParameterInfo),
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
        // Workers run on threads the caller's Sentry scope is not current on, so each span names its parent.
        var phase: ISpan? = null
        fun compileOne(p: ParsedFx, isFinalAttempt: Boolean): CompiledFxScript? = Tracing.childOf(phase, "fx.compile", p.relativePath) { span ->
            ScriptCacheProbe.take()
            val compiled = try {
                compiler.compile(p.scriptBody, p.fxMode)
            } catch (e: Exception) {
                logger.warn("Compiling fx/{} threw", p.relativePath, e)
                null
            }
            span?.setData("cache", ScriptCacheProbe.tag(ScriptCacheProbe.take()))
            val succeeded = compiled != null && compiled.isSuccess
            if (succeeded || isFinalAttempt) progress?.invoke(completed.incrementAndGet(), total)
            compiled
        }

        val compiledResults: List<Pair<ParsedFx, CompiledFxScript?>> = Tracing.span("fx.compile", "built-in effects") { span ->
            phase = span
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
            if (failedFirstPass == 0 || !compileInParallel) {
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
                        compiler.invalidate(p.scriptBody, p.fxMode)
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
                val factory = ScriptFxAdapter.createFactory(
                    compiled = compiled,
                    schema = p.parameters,
                    fxName = p.metadata.name,
                    outputType = p.outputType,
                    defaultTiming = p.metadata.defaultTiming,
                    registrationId = p.metadata.id,
                    defaultDirection = p.metadata.defaultDirection,
                )

                val timingSource = try {
                    TimingSource.valueOf(p.metadata.timingSource)
                } catch (_: Exception) {
                    TimingSource.BEAT
                }

                registry.register(FxRegistration(
                    id = p.metadata.id,
                    aliases = p.metadata.aliases?.toSet() ?: emptySet(),
                    name = p.metadata.name,
                    family = p.metadata.family,
                    outputType = p.outputType,
                    fxMode = p.fxMode,
                    parameters = p.parameters,
                    compatibleAttributes = p.metadata.compatibleAttributes,
                    source = FxSource.BUILT_IN,
                    script = p.scriptBody,
                    defaultTiming = p.metadata.defaultTiming,
                    defaultDirection = p.metadata.defaultDirection,
                    defaultCycle = p.metadata.defaultCycle,
                    timingSource = timingSource,
                    presets = p.metadata.presets,
                    attributeDefaults = p.metadata.attributeDefaults,
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
        private val PARAM_KEYS = setOf(
            "name", "type", "default", "description",
            "label", "min", "max", "step", "unit", "options", "role", "shownWhen", "pair",
        )

        /** The keys a `presets:` list item may carry. */
        private val PRESET_KEYS = setOf("name", "attribute", "parameters", "cycle", "direction")

        /** The keys an `attributeDefaults:` list item may carry. */
        private val ATTRIBUTE_DEFAULT_KEYS = setOf("attribute", "parameters")

        /** The keys the frontmatter itself takes, which an indented line after a list may still be. */
        private val TOP_LEVEL_KEYS = setOf(
            "id", "name", "family", "outputType", "fxMode", "defaultTiming", "defaultDirection", "defaultCycle",
            "timingSource", "compatibleAttributes", "aliases", "parameters", "presets", "attributeDefaults",
        )

        /** The frontmatter's three lists, each item a map of one of these key sets. */
        private val LIST_KEYS = mapOf(
            "parameters" to PARAM_KEYS,
            "presets" to PRESET_KEYS,
            "attributeDefaults" to ATTRIBUTE_DEFAULT_KEYS,
        )

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
            var family = ""
            var outputType = "SLIDER"
            var fxMode = "STANDARD"
            var defaultTiming = Timing.CYCLE
            var defaultDirection = FxDirection.FORWARD
            var defaultCycle = CycleLength.ONE_BAR
            var timingSource = "BEAT"
            var compatibleAttributes = listOf<String>()
            var aliases: List<String>? = null
            val items = LIST_KEYS.keys.associateWith { mutableListOf<Map<String, String>>() }

            // The list whose items the lines below belong to, and the item being read.
            var inList: String? = null
            var currentParam: MutableMap<String, String>? = null

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                if (trimmed.startsWith("- ") && inList != null) {
                    // Save previous item if any
                    currentParam?.let { map -> items.getValue(inList).add(map) }
                    // Start new item
                    currentParam = mutableMapOf()
                    // Parse inline key-value on the "- " line
                    val afterDash = trimmed.removePrefix("- ").trim()
                    if (afterDash.contains(":")) {
                        val (k, v) = afterDash.split(":", limit = 2)
                        currentParam[k.trim()] = v.trim().removeSurrounding("\"")
                    }
                    continue
                }

                // Continuation of the current param: one of [PARAM_KEYS]. Indentation
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
                // an indented top-level key written after the parameter list (`  compatibleAttributes:
                // [dimmer]`) would be swallowed and the effect would register with no compatible
                // properties and no error. None of the 28 shipped files do that, but this parser has
                // just cost the built-ins their whole metadata once; an unrecognised key falls
                // through to the top-level `when` instead.
                if (currentParam != null &&
                    (line.startsWith("  ") || line.startsWith("\t")) &&
                    !trimmed.startsWith("- ") &&
                    trimmed.substringBefore(":").trim() in LIST_KEYS.getValue(inList!!)
                ) {
                    if (trimmed.contains(":")) {
                        val (k, v) = trimmed.split(":", limit = 2)
                        currentParam[k.trim()] = v.trim().removeSurrounding("\"")
                    }
                    continue
                }

                // An indented key neither a list item nor the frontmatter takes (a typo) would otherwise
                // end the list here, and every item after it would be lost without a word. An indented
                // top-level key ends the list and is read as itself, below.
                require(
                    currentParam == null || !(line.startsWith("  ") || line.startsWith("\t")) || trimmed.startsWith("- ") ||
                        trimmed.substringBefore(":").trim() in TOP_LEVEL_KEYS,
                ) {
                    "FX '$id': '${trimmed.substringBefore(":").trim()}' is no key of a $inList item"
                }

                // Not in a list item anymore
                if (currentParam != null) {
                    items.getValue(inList!!).add(currentParam)
                    currentParam = null
                }
                inList = null

                if (!trimmed.contains(":")) continue
                val (key, value) = trimmed.split(":", limit = 2).map { it.trim() }

                when (key) {
                    "id" -> id = value
                    "name" -> name = value
                    "family" -> family = value
                    "outputType" -> outputType = value
                    "fxMode" -> fxMode = value
                    "defaultTiming" -> defaultTiming = timingOf(value)
                        ?: throw IllegalArgumentException("Unknown defaultTiming '$value'")
                    "defaultDirection" -> defaultDirection = FxDirection.byName(value)
                        ?: throw IllegalArgumentException("Unknown defaultDirection '$value'")
                    "defaultCycle" -> defaultCycle = value.toDoubleOrNull()?.takeIf { it > 0.0 }
                        ?: throw IllegalArgumentException("defaultCycle must be a positive number of beats, got '$value'")
                    "timingSource" -> timingSource = value
                    "compatibleAttributes" -> compatibleAttributes = parseYamlArray(value)
                    "aliases" -> aliases = parseYamlArray(value)
                    "parameters", "presets", "attributeDefaults" -> {
                        // An inline empty array opens no list
                        inList = key.takeUnless { value == "[]" }
                    }
                }
            }

            // Flush last item
            currentParam?.let { map -> items.getValue(inList!!).add(map) }
            val parameters = items.getValue("parameters").map(::parseParameter)

            val problems = parameterMetadataProblems(parameters.map(FxFileParameter::toParameterInfo))
            require(problems.isEmpty()) { "FX '$id': ${problems.joinToString("; ")}" }
            val presets = items.getValue("presets").map(::parsePreset)
            val attributeDefaults = items.getValue("attributeDefaults").associate { map ->
                val attribute = map["attribute"]?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("FX '$id': an attributeDefaults item needs an attribute")
                attribute to parseInlineMap(map["parameters"].orEmpty())
            }
            val schema = parameters.map(FxFileParameter::toParameterInfo)
            val namesProblems = (presets.map { "preset '${it.name}'" to it.parameters } +
                attributeDefaults.map { (attribute, values) -> "attributeDefaults '$attribute'" to values })
                .flatMap { (at, values) ->
                    values.keys.filter { key -> schema.none { it.name == key } }.map { "$at: no parameter '$it'" } +
                        listOfNotNull(choiceProblem(schema, values)?.let { "$at: $it" })
                } + presets.filter { it.attribute !in compatibleAttributes }.map { "preset '${it.name}': '${it.attribute}' is not a compatible attribute" }
            require(namesProblems.isEmpty()) { "FX '$id': ${namesProblems.joinToString("; ")}" }

            return FxFileMetadata(
                id = id,
                name = name,
                family = family,
                outputType = outputType,
                fxMode = fxMode,
                defaultTiming = defaultTiming,
                defaultDirection = defaultDirection,
                defaultCycle = defaultCycle,
                timingSource = timingSource,
                compatibleAttributes = compatibleAttributes,
                aliases = aliases,
                parameters = parameters,
                presets = presets,
                attributeDefaults = attributeDefaults,
            )
        }

        /**
         * One `presets:` item (fx-settings plan D4): a familiar name for this FX on one attribute,
         * with the parameters, cycle and Direction it starts with.
         *
         * ```
         * - name: Focus Pull
         *   attribute: focus
         *   parameters: {shape: RAMP_UP, curve: SINE_IN_OUT}
         *   cycle: 2.0          # beats; absent is the FX's defaultCycle
         *   direction: BOUNCE   # absent is the FX's defaultDirection
         * ```
         */
        private fun parsePreset(map: Map<String, String>): FxPreset {
            val name = map["name"]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("a preset needs a name")
            return FxPreset(
                name = name,
                attribute = map["attribute"]?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("preset '$name' needs an attribute"),
                parameters = parseInlineMap(map["parameters"].orEmpty()),
                cycle = map["cycle"]?.let {
                    it.toDoubleOrNull()?.takeIf { c -> c > 0.0 }
                        ?: throw IllegalArgumentException("preset '$name': cycle must be a positive number of beats, got '$it'")
                },
                direction = map["direction"]?.let {
                    (FxDirection.byName(it) ?: throw IllegalArgumentException("preset '$name': unknown direction '$it'")).name
                },
            )
        }

        /** `{key: value, key: value}` as a map; values hold no commas. Empty for a blank string. */
        private fun parseInlineMap(value: String): Map<String, String> {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return emptyMap()
            require(trimmed.startsWith("{") && trimmed.endsWith("}")) { "expected {key: value, …}, got '$value'" }
            return trimmed.removeSurrounding("{", "}").split(",").filter { it.isNotBlank() }.associate { entry ->
                val (k, v) = entry.split(":", limit = 2).takeIf { it.size == 2 }
                    ?: throw IllegalArgumentException("expected key: value, got '${entry.trim()}'")
                k.trim() to v.trim().removeSurrounding("\"")
            }
        }

        /**
         * One `parameters:` item. The editor's metadata (fx-settings plan D1) is written as:
         *
         * ```
         * min: 0                                  # max, step likewise; in the stored value's terms
         * unit: %                                 # one of PARAMETER_UNITS
         * options: [SINE: Sine, RAMP_UP: Ramp up] # a choice's values, each `value: label` or a bare value; no commas
         * role: detail
         * shownWhen: shape = [SQUARE, PULSE]      # or `shape = SQUARE`
         * pair: max                               # on a low, its high
         * ```
         */
        private fun parseParameter(map: Map<String, String>): FxFileParameter {
            val name = map["name"] ?: ""
            fun number(key: String): Double? = map[key]?.let {
                it.toDoubleOrNull() ?: throw IllegalArgumentException("parameter '$name': $key must be a number, got '$it'")
            }
            return FxFileParameter(
                name = name,
                type = map["type"] ?: "string",
                default = map["default"] ?: "",
                description = map["description"] ?: "",
                label = map["label"],
                min = number("min"),
                max = number("max"),
                step = number("step"),
                unit = map["unit"],
                options = map["options"]?.let { value ->
                    parseYamlArray(value).map { item ->
                        val (optionValue, optionLabel) = if (item.contains(":")) item.split(":", limit = 2) else listOf(item, item)
                        ParameterOption(optionValue.trim().removeSurrounding("\""), optionLabel.trim().removeSurrounding("\""))
                    }
                },
                role = map["role"],
                shownWhen = map["shownWhen"]?.let { value ->
                    val (param, values) = value.split("=", limit = 2).takeIf { it.size == 2 }
                        ?: throw IllegalArgumentException("parameter '$name': shownWhen is `other = [values]`, got '$value'")
                    val list = values.trim()
                    ParameterCondition(param.trim(), if (list.startsWith("[")) parseYamlArray(list) else listOf(list))
                },
                pair = map["pair"],
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
    val family: String,
    val outputType: String = "SLIDER",
    val fxMode: String = "STANDARD",
    val defaultTiming: Timing = Timing.CYCLE,
    val defaultDirection: FxDirection = FxDirection.FORWARD,
    /** A new instance's cycle, in beats (one bar unless the frontmatter says otherwise). */
    val defaultCycle: Double = CycleLength.ONE_BAR,
    val timingSource: String = "BEAT",
    val compatibleAttributes: List<String> = emptyList(),
    val aliases: List<String>? = null,
    val parameters: List<FxFileParameter> = emptyList(),
    val presets: List<FxPreset> = emptyList(),
    /** Parameters a new FX on an attribute starts with, by attribute (fx-settings plan session 2). */
    val attributeDefaults: Map<String, Map<String, String>> = emptyMap(),
)

data class FxFileParameter(
    val name: String,
    val type: String,
    val default: String,
    val description: String = "",
    val label: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val unit: String? = null,
    val options: List<ParameterOption>? = null,
    val role: String? = null,
    val shownWhen: ParameterCondition? = null,
    val pair: String? = null,
) {
    fun toParameterInfo(): ParameterInfo = ParameterInfo(
        name = name,
        type = type,
        defaultValue = default,
        description = description,
        label = label,
        min = min,
        max = max,
        step = step,
        unit = unit,
        options = options,
        role = role,
        shownWhen = shownWhen,
        pair = pair,
    )
}
