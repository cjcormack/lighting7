package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rigging kinds a fixture stands on (stage-view plan session 6) are stated twice — here, for
 * `describe_rig`, and in the Stage view's `bodies/mount.ts`, which draws them — so both are pinned
 * to `src/test/resources/stage/standingRiggingKinds.json`, which the client's `mount.test.ts` reads
 * too.
 */
class StandingRiggingKindsTest {
    @Test
    fun `the desk stands a body on the kinds the Stage view does`() {
        val text = checkNotNull(javaClass.getResource("/stage/standingRiggingKinds.json")).readText()
        val listed = Json.parseToJsonElement(text).jsonObject["standing"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(listed, STANDING_RIGGING_KINDS)
    }

    @Test
    fun `every standing kind is a rigging kind set_stage takes, and the test is case-blind`() {
        assertTrue(RIGGING_KINDS.containsAll(STANDING_RIGGING_KINDS))
        assertTrue(standsOn("ledge") && standsOn("FLOOR_STAND"))
        assertTrue(!standsOn("BAR") && !standsOn(null))
    }
}
