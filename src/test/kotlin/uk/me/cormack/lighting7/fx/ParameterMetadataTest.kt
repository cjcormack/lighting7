package uk.me.cormack.lighting7.fx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The checks a control over several parameters passes (venue-rig-fixes plan D20). */
class ParameterMetadataTest {

    private val parameters = listOf(
        ParameterInfo("shape", CHOICE_TYPE, "CIRCLE", options = listOf(ParameterOption("CIRCLE", "Circle"), ParameterOption("LINE", "Line"))),
        ParameterInfo("panSize", "ubyte", "64", shownWhen = ParameterCondition("shape", listOf("CIRCLE"))),
        ParameterInfo("tiltSize", "ubyte", "64", shownWhen = ParameterCondition("shape", listOf("CIRCLE"))),
        ParameterInfo("depth", "ubyte", "32"),
        ParameterInfo("spread", "ubyte", "16"),
        ParameterInfo("count", "ubyte", "4", shownWhen = ParameterCondition("depth", listOf("1"))),
        ParameterInfo("ratio", "double", "0.5"),
        ParameterInfo("low", "ubyte", "0", pair = "high"),
        ParameterInfo("high", "ubyte", "255"),
    )

    private fun problems(vararg controls: FxControl) = fxControlProblems(parameters, controls.toList())

    private fun assertRefused(expected: String, vararg controls: FxControl) {
        val found = problems(*controls)
        assertTrue(found.any { expected in it }, "expected '$expected' in $found")
    }

    @Test
    fun `a control over two numbers of one type passes`() {
        assertEquals(
            emptyList(),
            problems(FxControl("size", "Size", listOf("panSize", "tiltSize"), ParameterCondition("shape", listOf("CIRCLE")))),
        )
        assertEquals(emptyList(), problems(FxControl("both", "Both", listOf("depth", "spread"))))
    }

    @Test
    fun `a member shown when the control is not is refused`() {
        assertRefused("'panSize' is shown when the control is not", FxControl("size", "Size", listOf("panSize", "tiltSize")))
        assertRefused(
            "'depth' is shown when the control is not",
            FxControl("mixed", "Mixed", listOf("panSize", "depth"), ParameterCondition("shape", listOf("CIRCLE"))),
        )
        assertRefused(
            "'panSize' is shown when the control is not",
            FxControl("size", "Size", listOf("panSize", "tiltSize"), ParameterCondition("shape", listOf("CIRCLE", "LINE"))),
        )
    }

    @Test
    fun `a control shown by one of its own members is refused`() {
        assertRefused(
            "shownWhen names its own member 'depth'",
            FxControl("both", "Both", listOf("depth", "count"), ParameterCondition("depth", listOf("1"))),
        )
    }

    @Test
    fun `a control scaling one parameter is refused`() {
        assertRefused("scales two parameters or more, not 1", FxControl("size", "Size", listOf("panSize")))
    }

    @Test
    fun `a control over mixed types is refused`() {
        assertRefused("scales numbers of one type", FxControl("size", "Size", listOf("panSize", "ratio")))
        assertRefused("scales numbers of one type", FxControl("size", "Size", listOf("shape", "panSize")))
    }

    @Test
    fun `a member in a pair is refused`() {
        assertRefused("'low' is in a pair", FxControl("range", "Range", listOf("low", "depth")))
        assertRefused("'high' is in a pair", FxControl("range", "Range", listOf("depth", "high")))
    }

    @Test
    fun `a member twice is refused, in one control or two`() {
        assertRefused("'panSize' is scaled twice", FxControl("size", "Size", listOf("panSize", "panSize")))
        assertRefused(
            "'tiltSize' is scaled twice",
            FxControl("size", "Size", listOf("panSize", "tiltSize")),
            FxControl("other", "Other", listOf("tiltSize", "depth")),
        )
    }

    @Test
    fun `an unknown name is refused, in scales and in shownWhen`() {
        assertRefused("scales no parameter 'width'", FxControl("size", "Size", listOf("panSize", "width")))
        assertRefused(
            "shownWhen names no other parameter 'form'",
            FxControl("size", "Size", listOf("panSize", "tiltSize"), ParameterCondition("form", listOf("CIRCLE"))),
        )
        assertRefused(
            "shownWhen value 'SQUARE' is not an option of 'shape'",
            FxControl("size", "Size", listOf("panSize", "tiltSize"), ParameterCondition("shape", listOf("SQUARE"))),
        )
    }

    @Test
    fun `a control named as a parameter, twice, or with no label is refused`() {
        assertRefused("is also a parameter's name", FxControl("depth", "Depth", listOf("panSize", "tiltSize")))
        assertRefused("control 'size' twice", FxControl("size", "Size", listOf("panSize", "tiltSize")), FxControl("size", "Size", listOf("depth", "low")))
        assertRefused("needs a label", FxControl("size", "", listOf("panSize", "tiltSize")))
    }
}
