package uk.me.cormack.lighting7.fx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parameter metadata a `.fx.kts` frontmatter declares (fx-settings plan D1), and the `choice`
 * type that leans on it. Metadata only: nothing here compiles a script body.
 */
class FxFileLoaderTest {

    private fun parse(parameters: String): List<ParameterInfo> =
        FxFileLoader.parseFxFile(
            "/*---\nid: Probe\nname: Probe\nfamily: intensity\noutputType: SLIDER\n" +
                "compatibleAttributes: [dimmer]\nparameters:\n" + parameters.trimIndent().prependIndent("  ") +
                "\n---*/\nFxOutput.Slider(0u)\n",
        ).first.parameters.map(FxFileParameter::toParameterInfo)

    @Test
    fun `each metadata key is parsed`() {
        val params = parse(
            """
            - name: shape
              type: choice
              default: "SINE"
              description: The wave's shape
              label: Shape
              options: ["SINE": Sine, SQUARE: "Square", PULSE]
            - name: low
              type: ubyte
              default: "0"
              label: Low
              min: 0
              max: 255
              step: 1
              unit: %
              pair: high
            - name: high
              type: ubyte
              default: "255"
              label: High
              min: 0
              max: 255
              unit: %
            - name: width
              type: double
              default: "0.5"
              label: Width
              min: 0.05
              max: 1
              step: 0.01
              unit: ratio
              role: detail
              shownWhen: shape = [SQUARE, PULSE]
            - name: attack
              type: double
              default: "0.1"
              shownWhen: shape = PULSE
            """,
        )
        val (shape, low, high, width, attack) = params

        assertEquals("Shape", shape.label)
        assertEquals(
            listOf(ParameterOption("SINE", "Sine"), ParameterOption("SQUARE", "Square"), ParameterOption("PULSE", "PULSE")),
            shape.options,
        )
        assertEquals("The wave's shape", shape.description)

        assertEquals("Low", low.label)
        assertEquals(0.0, low.min)
        assertEquals(255.0, low.max)
        assertEquals(1.0, low.step)
        assertEquals("%", low.unit)
        assertEquals("high", low.pair)
        assertNull(high.pair)
        assertNull(high.step)

        assertEquals(0.05, width.min)
        assertEquals("ratio", width.unit)
        assertEquals("detail", width.role)
        assertEquals(ParameterCondition("shape", listOf("SQUARE", "PULSE")), width.shownWhen)
        assertEquals(ParameterCondition("shape", listOf("PULSE")), attack.shownWhen)
        assertNull(attack.label, "a parameter declaring nothing is drawn as the editor guesses")
    }

    @Test
    fun `a declaration that breaks a rule fails to load`() {
        val broken = listOf(
            "type: choice\n  default: \"X\"" to "a choice needs options",
            "type: choice\n  default: \"X\"\n  options: [A, B]" to "is not one of its options",
            "type: ubyte\n  default: \"0\"\n  unit: furlongs" to "unit 'furlongs'",
            "type: ubyte\n  default: \"0\"\n  role: hidden" to "role 'hidden'",
            "type: ubyte\n  default: \"0\"\n  min: 10\n  max: 5" to "is above max",
            "type: colour\n  default: \"#fff\"\n  min: 0" to "a range needs a number",
            "type: ubyte\n  default: \"0\"\n  pair: nothing" to "pair names no other parameter",
            "type: ubyte\n  default: \"0\"\n  shownWhen: nothing = A" to "shownWhen names no other parameter",
            "type: ubyte\n  default: \"0\"\n  min: lots" to "min must be a number",
            "type: double\n  default: \"0.5\"\n  unit: %" to "unit '%' reads a ubyte, not a double",
            "type: ubyte\n  default: \"0\"\n  unit: ratio" to "unit 'ratio' reads a double or float",
            "type: choice\n  default: \"A\"\n  options: [A, a]" to "option 'A' twice",
            "type: ubyte\n  default: \"0\"\n- name: p\n  type: ubyte\n  default: \"1\"" to "parameter 'p' twice",
            "type: ubyte\n  default: \"0\"\n  descripton: typo" to "'descripton' is no key of a parameters item",
        )
        for ((body, problem) in broken) {
            val error = assertFailsWith<IllegalArgumentException>(body) { parse("- name: p\n  $body") }
            assertTrue(problem in error.message.orEmpty(), "expected '$problem' in '${error.message}'")
        }
    }

    @Test
    fun `a choice refuses a value none of its options is`() {
        val schema = parse(
            """
            - name: shape
              type: choice
              default: "SINE"
              options: [SINE: Sine, SQUARE: Square]
            """,
        )
        assertNull(choiceProblem(schema, mapOf("shape" to "square")), "an option matches ignoring case")
        assertNull(choiceProblem(schema, emptyMap()), "absent is the default")
        assertNull(
            choiceProblem(schema.map { it.copy(options = null) }, mapOf("shape" to "TRIANGLE")),
            "a choice an older desk's sync stripped of its options takes any value",
        )
        val problem = assertNotNull(choiceProblem(schema, mapOf("shape" to "TRIANGLE")))
        assertTrue("TRIANGLE" in problem && "SINE, SQUARE" in problem, problem)

        assertEquals("SQUARE", TypedParams(mapOf("shape" to "square"), schema).choice("shape"))
        assertEquals("SINE", TypedParams(mapOf("shape" to "TRIANGLE"), schema).choice("shape"), "an unknown value reads as the default")
        assertEquals("SINE", TypedParams(emptyMap(), schema).choice("shape"))
    }

    @Test
    fun `every built-in parameter declares a label and, where numeric, a range and a unit`() {
        val loader = FxFileLoader::class.java.classLoader
        val index = loader.getResource("fx/index.txt")!!.readText().lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val numeric = setOf("ubyte", "int", "double", "float")
        val missing = index.flatMap { path ->
            val metadata = FxFileLoader.parseFxFile(loader.getResource("fx/$path")!!.readText()).first
            metadata.parameters.flatMap { p ->
                listOfNotNull(
                    if (p.label.isNullOrBlank()) "${metadata.id}.${p.name}: no label" else null,
                    if (p.type in numeric && (p.min == null || p.max == null)) "${metadata.id}.${p.name}: no range" else null,
                    if (p.type in numeric && p.unit == null) "${metadata.id}.${p.name}: no unit" else null,
                )
            }
        }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun `the library carries the metadata and names every curve`() {
        val registry = FxRegistry()
        registry.register(
            FxRegistration(
                id = "Probe",
                name = "Probe",
                family = "intensity",
                outputType = FxOutputType.SLIDER,
                parameters = listOf(
                    ParameterInfo("low", "ubyte", "0", label = "Low", min = 0.0, max = 255.0, unit = "%", pair = "high"),
                    ParameterInfo("high", "ubyte", "255", label = "High"),
                    ParameterInfo("curve", "easingCurve", "LINEAR"),
                ),
                compatibleAttributes = listOf("dimmer"),
                factory = { _, _, _ -> error("not built") },
            ),
        )
        val (low, _, curve) = registry.getLibrary().single().parameters
        assertEquals("Low", low.label)
        assertEquals("high", low.pair)
        assertEquals(uk.me.cormack.lighting7.dmx.EasingCurve.entries.map { it.name }, curve.options?.map { it.value })
        assertEquals("Sine in out", curve.options?.first { it.value == "SINE_IN_OUT" }?.label)
    }

    private fun metadata(tail: String): FxFileMetadata =
        FxFileLoader.parseFxFile(
            "/*---\nid: Probe\nname: Probe\nfamily: intensity\noutputType: SLIDER\n" +
                "compatibleAttributes: [dimmer, zoom]\nparameters:\n" +
                "  - name: shape\n    type: choice\n    default: \"SINE\"\n    options: [SINE, SQUARE]\n" +
                "  - name: low\n    type: ubyte\n    default: \"0\"\n" +
                tail.trimIndent() + "\n---*/\nFxOutput.Slider(0u)\n",
        ).first

    @Test
    fun `presets and attribute defaults are parsed (fx-settings plan session 2)`() {
        val parsed = metadata(
            """
            attributeDefaults:
              - attribute: zoom
                parameters: {low: 64}
            presets:
              - name: Zoom Wave
                attribute: zoom
                parameters: {shape: SQUARE, low: 64}
                cycle: 2.0
                direction: bounce
              - name: Plain
                attribute: dimmer
            """,
        )
        assertEquals(mapOf("zoom" to mapOf("low" to "64")), parsed.attributeDefaults)
        assertEquals(
            listOf(
                FxPreset("Zoom Wave", "zoom", mapOf("shape" to "SQUARE", "low" to "64"), 2.0, "BOUNCE"),
                FxPreset("Plain", "dimmer"),
            ),
            parsed.presets,
        )
        assertEquals(listOf("shape", "low"), parsed.parameters.map { it.name }, "the parameter list ends where presets begins")
    }

    @Test
    fun `a preset or attribute default naming what the FX lacks fails to load`() {
        val broken = listOf(
            "presets:\n  - name: P\n    attribute: iris" to "'iris' is not a compatible attribute",
            "presets:\n  - name: P\n    attribute: dimmer\n    parameters: {width: 1}" to "preset 'P': no parameter 'width'",
            "presets:\n  - name: P\n    attribute: dimmer\n    parameters: {shape: SAW}" to "'shape' is one of",
            "presets:\n  - name: P\n    attribute: dimmer\n    direction: sideways" to "unknown direction",
            "attributeDefaults:\n  - attribute: zoom\n    parameters: {high: 9}" to "attributeDefaults 'zoom': no parameter 'high'",
            "presets:\n  - name: P\n    attribute: dimmer\n    cycel: 2.0" to "'cycel' is no key of a presets item",
        )
        for ((tail, problem) in broken) {
            val error = assertFailsWith<IllegalArgumentException>(tail) { metadata(tail) }
            assertTrue(problem in error.message.orEmpty(), "expected '$problem' in '${error.message}'")
        }
    }
}
