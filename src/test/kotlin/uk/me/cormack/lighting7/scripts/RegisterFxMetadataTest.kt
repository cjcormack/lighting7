package uk.me.cormack.lighting7.scripts

import uk.me.cormack.lighting7.fx.FxOutputType
import uk.me.cormack.lighting7.fx.FxRegistration
import uk.me.cormack.lighting7.fx.ParameterInfo
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A script's `registerFx` checks a parameter's editor metadata as a built-in's load does (fx-settings plan D1). */
class RegisterFxMetadataTest : RouteIntegrationTest() {

    private fun registration(id: String, vararg parameters: ParameterInfo) = FxRegistration(
        id = id, name = id, family = "intensity", outputType = FxOutputType.SLIDER,
        parameters = parameters.toList(), compatibleAttributes = listOf("dimmer"),
        factory = { _, _, _ -> error("not built") },
    )

    @Test
    fun `a script FX whose metadata breaks a rule is refused, and a sound one registers`() {
        val script = object : FxDefinitionScript(state.show, "probe") {}
        val error = assertFailsWith<IllegalArgumentException> {
            script.registerFx(registration("BrokenProbe", ParameterInfo("low", "ubyte", "0", min = 10.0, max = 5.0, pair = "nope")))
        }
        assertTrue("above max" in error.message.orEmpty() && "pair" in error.message.orEmpty(), error.message)
        assertNull(state.show.fxRegistry.getRegistration("BrokenProbe"))

        script.registerFx(registration("SoundProbe", ParameterInfo("low", "ubyte", "0", label = "Low", min = 0.0, max = 255.0, unit = "%")))
        assertNotNull(state.show.fxRegistry.getRegistration("SoundProbe"))
        state.show.fxRegistry.unregister("SoundProbe")
    }
}
