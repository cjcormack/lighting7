package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.sync.canonicalEncode
import uk.me.cormack.lighting7.sync.dto.ProjectJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the classify-or-reject decision behind the `lightingOnly` repo listing: given the
 * text of a repo's `project.json`, do we treat it as a lighting project and surface its
 * name/description, or reject it? The HTTP fetch + caching around this are exercised
 * manually against a real GitHub install (no MockEngine harness exists in this module).
 */
class OAuthRepoProbeTest {

    @Test
    fun `parses a valid project_json into metadata`() {
        val text = """
            {
              "uuid": "11111111-2222-3333-4444-555555555555",
              "name": "Main Show",
              "description": "The big room rig"
            }
        """.trimIndent()
        val parsed = parseProjectJsonOrNull(text)
        assertEquals("Main Show", parsed?.name)
        assertEquals("The big room rig", parsed?.description)
        assertEquals("11111111-2222-3333-4444-555555555555", parsed?.uuid)
    }

    @Test
    fun `round-trips the exporter's canonical output`() {
        val original = ProjectJson(uuid = "abc", name = "Round Trip", description = null, stageWidthM = 8.0)
        val parsed = parseProjectJsonOrNull(canonicalEncode(ProjectJson.serializer(), original))
        assertEquals(original.uuid, parsed?.uuid)
        assertEquals(original.name, parsed?.name)
        assertNull(parsed?.description)
        assertEquals(8.0, parsed?.stageWidthM)
    }

    @Test
    fun `ignores unknown keys for forward-compat`() {
        val text = """{ "uuid": "u", "name": "N", "somethingNew": 42 }"""
        assertEquals("N", parseProjectJsonOrNull(text)?.name)
    }

    @Test
    fun `rejects null, blank, malformed, or incomplete json`() {
        assertNull(parseProjectJsonOrNull(null))
        assertNull(parseProjectJsonOrNull("   "))
        assertNull(parseProjectJsonOrNull("not json"))
        assertNull(parseProjectJsonOrNull("{ }")) // missing uuid + name
        assertNull(parseProjectJsonOrNull("""{ "uuid": "u" }""")) // missing name
    }
}
