package uk.me.cormack.lighting7.fx

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
    /**
     * Load all .fx.kts files from the `fx/` resource directory and register them.
     *
     * @param registry The FxRegistry to register effects into
     * @return Number of effects successfully loaded
     */
    fun loadBuiltInEffects(registry: FxRegistry): Int {
        val fxDir = this::class.java.classLoader.getResource("fx")
            ?: run {
                System.err.println("FxFileLoader: fx/ resource directory not found")
                return 0
            }

        var count = 0

        // Read the index file which lists all .fx.kts files
        val indexResource = this::class.java.classLoader.getResource("fx/index.txt")
        if (indexResource == null) {
            System.err.println("FxFileLoader: fx/index.txt not found, no built-in effects to load")
            return 0
        }

        val files = indexResource.readText().lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }

        for (relativePath in files) {
            try {
                val resource = this::class.java.classLoader.getResource("fx/$relativePath")
                if (resource == null) {
                    System.err.println("FxFileLoader: Could not find resource fx/$relativePath")
                    continue
                }

                val content = resource.readText()
                val (metadata, scriptBody) = parseFxFile(content)

                val effectMode = EffectMode.valueOf(metadata.effectMode)
                val outputType = FxOutputType.valueOf(metadata.outputType)
                val parameters = metadata.parameters.map { p ->
                    ParameterInfo(p.name, p.type, p.default, p.description)
                }

                val compiled = compiler.compile(scriptBody, effectMode)
                if (!compiled.isSuccess) {
                    System.err.println("FxFileLoader: Failed to compile fx/$relativePath:")
                    compiled.diagnostics.forEach { d ->
                        System.err.println("  ${d.severity}: ${d.message} ${d.location ?: ""}")
                    }
                    continue
                }

                val factory = ScriptEffectAdapter.createFactory(
                    compiled = compiled,
                    schema = parameters,
                    effectName = metadata.name,
                    outputType = outputType,
                    defaultStepTiming = metadata.defaultStepTiming,
                )

                registry.register(EffectRegistration(
                    id = metadata.id,
                    aliases = metadata.aliases?.toSet() ?: emptySet(),
                    name = metadata.name,
                    category = metadata.category,
                    outputType = outputType,
                    effectMode = effectMode,
                    parameters = parameters,
                    compatibleProperties = metadata.compatibleProperties,
                    source = EffectSource.BUILT_IN,
                    script = scriptBody,
                    defaultStepTiming = metadata.defaultStepTiming,
                    factory = factory,
                ))

                count++
            } catch (e: Exception) {
                System.err.println("FxFileLoader: Error loading fx/$relativePath: ${e.message}")
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
