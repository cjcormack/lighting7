package uk.me.cormack.lighting7.midi

import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `refuseAxisOnNonColour`: a [ColourAxis] on a property that is not a colour is refused at the
 * write boundary, by name, through every door. Needs a real database and a health context whose
 * fixtures are the patched rig, so it lives beside [ControlSurfaceBindingBatchTest] rather than
 * with the `FakeDatabase` resolver tests.
 */
class ControlSurfaceBindingAxisTest : RouteIntegrationTest() {

    private val deviceTypeKey = "x-touch-compact-standard"
    private val hex1 = Subject.Fixture("hex-1")
    private val wash = Subject.Group("front-wash")

    private fun service() = state.controlSurfaceBindingService

    @Before
    fun patchRig() {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        LocateTestSupport.seedGroup(state, projectId, "front-wash", "hex-1", "hex-2")
    }

    private fun create(controlId: String, binding: Binding) = service().create(
        projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = controlId, bank = null, binding = binding,
    )

    private fun refused(controlId: String, binding: Binding) {
        val refusal = assertFailsWith<BindingRefused> { create(controlId, binding) }
        assertEquals(CODE_BINDING_AXIS_NEEDS_COLOUR, refusal.code)
    }

    private fun level(on: Subject, attribute: String, axis: ColourAxis? = null) = Binding(Does.Level(attribute, axis), on)

    @Test
    fun `an axis on a slider is refused and on a colour accepted, for every carrier`() {
        refused("fader-1", level(hex1, "dimmer", ColourAxis.SATURATION))
        refused("fader-1", level(wash, "dimmer", ColourAxis.HUE_FINE))
        refused("fader-1", level(Subject.Selection, "uv", ColourAxis.BRIGHTNESS))
        refused("btn-1", Binding(Does.EncoderPage("dimmer", ColourAxis.SATURATION)))
        assertTrue(service().list(projectId).isEmpty(), "nothing was written on the way to a refusal")

        create("fader-1", level(hex1, "rgbColour", ColourAxis.SATURATION))
        create("fader-2", level(wash, "rgbColour", ColourAxis.HUE_FINE))
        create("fader-3", level(Subject.Selection, "rgbColour", ColourAxis.BRIGHTNESS))
        create("btn-1", Binding(Does.EncoderPage("rgbColour", ColourAxis.SATURATION)))
        assertEquals(4, service().list(projectId).size)
    }

    @Test
    fun `update and replace are guarded too`() {
        val existing = create("fader-1", level(hex1, "rgbColour", ColourAxis.SATURATION))
        val onUpdate = assertFailsWith<BindingRefused> {
            service().update(projectId, existing.id, binding = level(hex1, "dimmer", ColourAxis.SATURATION))
        }
        assertEquals(CODE_BINDING_AXIS_NEEDS_COLOUR, onUpdate.code)
        assertEquals(
            level(hex1, "rgbColour", ColourAxis.SATURATION),
            service().get(projectId, existing.id)?.binding,
            "the row is as it was",
        )

        val onReplace = assertFailsWith<BindingRefused> {
            service().replace(
                projectId,
                deleteIds = listOf(existing.id),
                creates = listOf(
                    ControlSurfaceBindingService.NewBinding(
                        deviceTypeKey = deviceTypeKey, controlId = "fader-2", bank = null,
                        binding = level(wash, "dimmer", ColourAxis.BRIGHTNESS),
                    ),
                ),
            )
        }
        assertEquals(CODE_BINDING_AXIS_NEEDS_COLOUR, onReplace.code)
        assertEquals(1, service().list(projectId).size, "nothing deleted on the way to a refusal")
    }

    @Test
    fun `a fixture or attribute this rule cannot judge is left to health`() {
        // A fixture that does not exist has no property type to refuse on; health says MissingFixture.
        create("fader-1", level(Subject.Fixture("nonesuch"), "dimmer", ColourAxis.SATURATION))
        // A property no head declares has an unknown type; health says UnknownAttribute.
        create("fader-2", level(hex1, "nonesuch", ColourAxis.SATURATION))
        // Same rule for the two bindings that name no head. The context's fixtures are the rig of
        // whichever show is *loaded*, not of `projectId`, so refusing a name this rig has never
        // heard of would reject a good binding authored against another project's rig.
        create("fader-3", level(Subject.Selection, "nonesuch", ColourAxis.SATURATION))
        create("btn-1", Binding(Does.EncoderPage("nonesuch", ColourAxis.HUE_FINE)))
        assertEquals(4, service().list(projectId).size)
        assertTrue(service().list(projectId).none { it.health == AssignmentHealth.Ok })
    }
}
