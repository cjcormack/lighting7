package uk.me.cormack.lighting7.fx

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookFxEntity
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.FxTargetsRef
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

    private fun DaoLook.effect(targetType: String, targetKey: String, fxType: String, sortOrder: Int) = DaoLookFxEntity.new {
        look = this@effect
        this.targetType = targetType
        this.targetKey = targetKey
        this.fxType = fxType
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
            look.effect("group", "front-wash", "Wave", 1)
            look.uuid
        }

        val (snapshot, logs) = captureLogs("loadLookSnapshot") { loadLookSnapshot(state.database, lookUuid) }

        assertEquals(
            listOf(FxTargetsRef.Single(TargetRef.Group("front-wash")) to "Wave"),
            snapshot!!.effects.map { it.target to it.effect.fxType },
        )
        assertEquals(1, logs.size, "$logs")
        assertTrue("'S5 Deferred FX'" in logs.single() && "ColourCycle" in logs.single(), logs.single())
    }
}
