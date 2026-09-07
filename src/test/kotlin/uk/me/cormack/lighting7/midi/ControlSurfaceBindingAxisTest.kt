package uk.me.cormack.lighting7.midi

import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.CueTargetDto
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
    private val wash = CueTargetDto("group", "front-wash")

    private fun service() = state.controlSurfaceBindingService

    @Before
    fun patchRig() {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        LocateTestSupport.seedGroup(state, projectId, "front-wash", "hex-1", "hex-2")
    }

    private fun create(controlId: String, target: BindingTarget) = service().create(
        projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = controlId, bank = null, target = target,
    )

    private fun refused(controlId: String, target: BindingTarget) {
        val refusal = assertFailsWith<BindingRefused> { create(controlId, target) }
        assertEquals(CODE_BINDING_AXIS_NEEDS_COLOUR, refusal.code)
    }

    @Test
    fun `an axis on a slider is refused and on a colour accepted, for every carrier`() {
        refused("fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer", ColourAxis.SATURATION))
        refused("fader-1", BindingTarget.GroupProperty("front-wash", "dimmer", ColourAxis.HUE_FINE))
        refused("fader-1", BindingTarget.SelectionProperty("uv", ColourAxis.BRIGHTNESS))
        refused("btn-1", BindingTarget.EncoderBankSet("dimmer", ColourAxis.SATURATION))
        refused("btn-1", BindingTarget.Flash(BindingTarget.FixtureProperty("hex-1", "dimmer", ColourAxis.SATURATION)))
        assertTrue(service().list(projectId).isEmpty(), "nothing was written on the way to a refusal")

        create("fader-1", BindingTarget.FixtureProperty("hex-1", "rgbColour", ColourAxis.SATURATION))
        create("fader-2", BindingTarget.GroupProperty("front-wash", "rgbColour", ColourAxis.HUE_FINE))
        create("fader-3", BindingTarget.SelectionProperty("rgbColour", ColourAxis.BRIGHTNESS))
        create("btn-1", BindingTarget.EncoderBankSet("rgbColour", ColourAxis.SATURATION))
        create("btn-2", BindingTarget.Flash(BindingTarget.FixtureProperty("hex-1", "rgbColour", ColourAxis.SATURATION)))
        assertEquals(5, service().list(projectId).size)
    }

    @Test
    fun `update and replace are guarded too`() {
        val existing = create("fader-1", BindingTarget.FixtureProperty("hex-1", "rgbColour", ColourAxis.SATURATION))
        val onUpdate = assertFailsWith<BindingRefused> {
            service().update(projectId, existing.id, target = BindingTarget.FixtureProperty("hex-1", "dimmer", ColourAxis.SATURATION))
        }
        assertEquals(CODE_BINDING_AXIS_NEEDS_COLOUR, onUpdate.code)
        assertEquals(
            BindingTarget.FixtureProperty("hex-1", "rgbColour", ColourAxis.SATURATION),
            service().get(projectId, existing.id)?.target,
            "the row is as it was",
        )

        val onReplace = assertFailsWith<BindingRefused> {
            service().replace(
                projectId,
                deleteIds = listOf(existing.id),
                creates = listOf(
                    ControlSurfaceBindingService.NewBinding(
                        deviceTypeKey = deviceTypeKey, controlId = "fader-2", bank = null,
                        target = BindingTarget.GroupProperty("front-wash", "dimmer", ColourAxis.BRIGHTNESS),
                    ),
                ),
            )
        }
        assertEquals(CODE_BINDING_AXIS_NEEDS_COLOUR, onReplace.code)
        assertEquals(1, service().list(projectId).size, "nothing deleted on the way to a refusal")
    }

    @Test
    fun `a fixture or property this rule cannot judge is left to health`() {
        // A fixture that does not exist has no property type to refuse on; health says MissingFixture.
        create("fader-1", BindingTarget.FixtureProperty("nonesuch", "dimmer", ColourAxis.SATURATION))
        // A property no head declares has an unknown type; health says UnknownProperty.
        create("fader-2", BindingTarget.FixtureProperty("hex-1", "nonesuch", ColourAxis.SATURATION))
        // Same rule for the two targets that name no head. The context's fixtures are the rig of
        // whichever show is *loaded*, not of `projectId`, so refusing a name this rig has never
        // heard of would reject a good binding authored against another project's rig.
        create("fader-3", BindingTarget.SelectionProperty("nonesuch", ColourAxis.SATURATION))
        create("btn-1", BindingTarget.EncoderBankSet("nonesuch", ColourAxis.HUE_FINE))
        assertEquals(4, service().list(projectId).size)
        assertTrue(service().list(projectId).none { it.health == uk.me.cormack.lighting7.models.AssignmentHealth.Ok })
    }
}
