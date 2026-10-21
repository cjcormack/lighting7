package uk.me.cormack.lighting7.fx

import org.junit.Test
import uk.me.cormack.lighting7.models.EffectTargetRef
import uk.me.cormack.lighting7.models.TargetRef
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProgrammerLayerEffectKeyTest {

    private val pulse = StoredEffect(
        effectType = "Pulse", category = "dimmer", propertyName = "dimmer",
        beatDivision = 1.0, blendMode = "OVERRIDE", distribution = "LINEAR",
        phaseOffset = 0.0, elementMode = null, elementFilter = null, stepTiming = null,
        parameters = emptyMap(), speedMasterUuid = null, rateSpeedMasterUuid = null,
    )

    @Test
    fun `one effect on a fixture and on a group of the same name is two keys`() {
        val onFixture = ProgrammerLayerEffectKey.of(1, pulse, EffectTargetRef.Single(TargetRef.Fixture("wash")))
        val onGroup = ProgrammerLayerEffectKey.of(1, pulse, EffectTargetRef.Single(TargetRef.Group("wash")))
        assertNotEquals(onFixture, onGroup)
        assertEquals(2, setOf(onFixture, onGroup).size)
    }

    @Test
    fun `a stored effect's row uuid is no part of the key`() {
        val target = EffectTargetRef.Single(TargetRef.Fixture("wash"))
        assertEquals(
            ProgrammerLayerEffectKey.of(1, pulse.copy(uuid = UUID.randomUUID()), target),
            ProgrammerLayerEffectKey.of(1, pulse.copy(uuid = UUID.randomUUID()), target),
        )
    }
}
