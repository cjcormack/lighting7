package uk.me.cormack.lighting7.fx

import org.junit.Test
import uk.me.cormack.lighting7.models.FxTargetsRef
import uk.me.cormack.lighting7.models.TargetRef
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProgrammerLayerFxKeyTest {

    private val pulse = StoredFx(
        fxType = "Wave", family = "intensity", attribute = "dimmer",
        cycle = 1.0, blendMode = "OVERRIDE", order = "NORMAL",
        phaseOffset = 0.0, elementMode = null, elementFilter = null, timing = null,
        parameters = emptyMap(), speedMasterUuid = null, rateSpeedMasterUuid = null,
    )

    @Test
    fun `one effect on a fixture and on a group of the same name is two keys`() {
        val onFixture = ProgrammerLayerFxKey.of(1, pulse, FxTargetsRef.Single(TargetRef.Fixture("wash")))
        val onGroup = ProgrammerLayerFxKey.of(1, pulse, FxTargetsRef.Single(TargetRef.Group("wash")))
        assertNotEquals(onFixture, onGroup)
        assertEquals(2, setOf(onFixture, onGroup).size)
    }

    @Test
    fun `a stored effect's row uuid is no part of the key`() {
        val target = FxTargetsRef.Single(TargetRef.Fixture("wash"))
        assertEquals(
            ProgrammerLayerFxKey.of(1, pulse.copy(uuid = UUID.randomUUID()), target),
            ProgrammerLayerFxKey.of(1, pulse.copy(uuid = UUID.randomUUID()), target),
        )
    }
}
