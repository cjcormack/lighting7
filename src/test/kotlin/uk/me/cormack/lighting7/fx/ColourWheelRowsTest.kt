package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.MartinMac250Fixture
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A stored row on a colour wheel reaches the wheel (`FU-LOOK-COLOUR-WHEEL-ROW`). The MAC 250's wheel
 * used to be a property named `colour`, which `canonicalPropertyName` reads as the RGB alias and
 * rewrote to `rgbColour` — a name the MAC does not have — so every cue row and Look row on it was
 * dropped. It is `colourWheel` now, and its rows hold the slot's level, which the parse reads as a
 * slot because the wheel is slot-backed (`Fixture.Property.settingBacked`).
 */
class ColourWheelRowsTest {

    private val universe = Universe(0, 0)

    /** The MAC 250 at channel 40, so its wheel (channel 3 of the mode) is DMX channel 42. */
    private fun rig(): Fixtures {
        val fixtures = Fixtures()
        fixtures.register {
            addFixture(MartinMac250Fixture.Mode4Ch(universe, "mac-1", "MAC 1", firstChannel = 40))
        }
        return fixtures
    }

    private val red = MartinMac250Fixture.Colour.RED.level

    @Test
    fun `a cue row on the MAC 250's wheel reaches the wheel's channel`() {
        val fixtures = rig()
        val out = buildCueAssignmentsForCue(fixtures, CueApplyData(
            cueId = 7,
            cueName = "test",
            adHocEffects = emptyList(),
            propertyAssignments = listOf(
                CuePropertyAssignmentDto(
                    targetType = "fixture",
                    targetKey = "mac-1",
                    propertyName = "colourWheel",
                    value = red.toString(),
                ),
            ),
            cueStackId = 3,
            sortOrder = 2,
        ))
        val row = out.single()
        assertEquals("colourWheel", row.propertyName)
        assertEquals(PropertyCategory.COLOUR, row.category)
        assertEquals(CueAssignmentResolver.PropertyValue.Setting(red), row.value, "a level, read as the slot")

        val writes = PropertyChannelWriter.resolve(fixtures.untypedGroupableFixture("mac-1"), row.propertyName, row.value)
        assertEquals(listOf(42 to red.toInt()), writes.map { it.channel to it.value.toInt() })
    }

    @Test
    fun `a Look row on the MAC 250's wheel cooks to the slot and reaches the wheel's channel`() {
        val fixtures = rig()
        val look = LookSnapshot(
            lookId = 5,
            lookUuid = UUID.nameUUIDFromBytes("Red wheel".toByteArray()),
            name = "Red wheel",
            rows = listOf(LookRowEntry(target = TargetRef.Fixture("mac-1"), propertyName = "colourWheel", value = red.toString())),
            effects = emptyList(),
        )
        val result = CueComposer.cook(
            fixtures = fixtures,
            cueId = 9,
            priority = 3_002_001,
            layers = listOf(
                CookLayer(
                    source = LayerSource.look(look.lookId, look.lookUuid, look.name),
                    sortOrder = 0,
                    targets = emptyList(),
                    layerId = 1,
                ),
            ),
            localRows = emptyList(),
            resolveLook = { if (it == look.lookUuid) look else null },
            resolveTemplate = { null },
        )
        val row = result.rows.single()
        assertEquals("mac-1", row.targetKey)
        assertEquals("colourWheel", row.propertyName)
        assertEquals(CueAssignmentResolver.PropertyValue.Setting(red), row.value)
        assertEquals(setOf(FxEngine.PropertyKey("mac-1", "colourWheel")), result.assertedKeys)

        val writes = PropertyChannelWriter.resolve(fixtures.untypedGroupableFixture("mac-1"), row.propertyName, row.value)
        assertEquals(listOf(42 to red.toInt()), writes.map { it.channel to it.value.toInt() })
    }
}
