package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PushRefusalsTest {
    @Test
    fun `every reason in the fixture parses to its code`() {
        val reasons = fixtureJson("repos/push-refusals.json").jsonObject.getValue("reasons").jsonArray.map { it.jsonObject }
        val expected = reasons.associate { entry ->
            entry.getValue("reason").jsonPrimitive.content to entry.getValue("code").let { if (it is JsonNull) null else it.jsonPrimitive.content }
        }
        assertEquals(PushRefusals.CODES, expected.values.filterNotNull().toSet(), "every code has a reason")
        for ((reason, code) in expected) {
            assertEquals(code, PushRefusals.codeOf(reason), reason)
        }
        assertEquals(null, PushRefusals.codeOf(null))
    }

    @Test
    fun `a reason the cloud writes parses back to its code`() {
        for (code in PushRefusals.CODES) {
            assertEquals(code, PushRefusals.codeOf(PushRefusals.reason(code, "words")))
        }
        assertFailsWith<IllegalArgumentException> { PushRefusals.reason(ErrorCode.REPO_NOT_FOUND, "not a push refusal") }
    }
}
