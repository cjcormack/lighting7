package uk.me.cormack.lighting7.ai

import uk.me.cormack.lighting7.fx.ParameterCondition
import uk.me.cormack.lighting7.fx.ParameterInfo
import uk.me.cormack.lighting7.fx.ParameterOption
import kotlin.test.Test
import kotlin.test.assertEquals

/** `describe_rig`'s FX library line carries each parameter's metadata (fx-settings plan D1). */
class ParameterBriefTest {

    @Test
    fun `a parameter's metadata rides in brackets after its default`() {
        assertEquals("seed:int=7", parameterBrief(ParameterInfo("seed", "int", "7")))
        assertEquals(
            "min:ubyte=0 [\"Low\" 0–255 % with max]",
            parameterBrief(ParameterInfo("min", "ubyte", "0", label = "Low", min = 0.0, max = 255.0, unit = "%", pair = "max")),
        )
        assertEquals(
            "width:double=0.5 [0.05–1 ratio detail when shape=SQUARE|PULSE]",
            parameterBrief(
                ParameterInfo(
                    "width", "double", "0.5", min = 0.05, max = 1.0, unit = "ratio", role = "detail",
                    shownWhen = ParameterCondition("shape", listOf("SQUARE", "PULSE")),
                ),
            ),
        )
        assertEquals(
            "shape:choice=SINE [SINE|SQUARE]",
            parameterBrief(ParameterInfo("shape", "choice", "SINE", options = listOf(ParameterOption("SINE", "Sine"), ParameterOption("SQUARE", "Square")))),
        )
        assertEquals(
            "curve:easingCurve=LINEAR",
            parameterBrief(ParameterInfo("curve", "easingCurve", "LINEAR", options = listOf(ParameterOption("LINEAR", "Linear")))),
            "a curve's options are the desk's own, and too many to list on every line",
        )
    }
}
