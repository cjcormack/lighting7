package uk.me.cormack.lighting7.fx

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookEffect
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.EffectTargetRef
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.captureLogs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `loadLookSnapshot` over Look effects written straight to the database, past the write boundary: a
 * Look effect names its own target (absolute-looks plan D2), so one an older database left deferred
 * is dropped at load, and the log names its Look.
 */
class LookSnapshotLoadTest : RouteIntegrationTest() {

    private fun DaoLook.effect(targetType: String, targetKey: String, effectType: String, sortOrder: Int) = DaoLookEffect.new {
        look = this@effect
        this.targetType = targetType
        this.targetKey = targetKey
        this.fxType = effectType
        family = "intensity"
        attribute = "dimmer"
        cycle = 1.0
        blendMode = "OVERRIDE"
        order = "NORMAL"
        parameters = emptyMap()
        this.sortOrder = sortOrder
    }

    @Test
    fun `a deferred effect written straight to the database is dropped at load, and logged`() {
        val lookUuid = transaction(state.database) {
            val look = DaoLook.new {
                project = DaoProject[projectId]
                name = "S5 Deferred FX"
            }
            look.effect(DEFERRED_TARGET_TYPE, "", "ColourCycle", 0)
            look.effect("group", "front-wash", "Pulse", 1)
            look.uuid
        }

        val (snapshot, logs) = captureLogs("loadLookSnapshot") { loadLookSnapshot(state.database, lookUuid) }

        assertEquals(
            listOf(EffectTargetRef.Single(TargetRef.Group("front-wash")) to "Pulse"),
            snapshot!!.effects.map { it.target to it.effect.effectType },
        )
        assertEquals(1, logs.size, "$logs")
        assertTrue("'S5 Deferred FX'" in logs.single() && "ColourCycle" in logs.single(), logs.single())
    }
}
