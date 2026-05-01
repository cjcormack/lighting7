package uk.me.cormack.lighting7.sync

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [CommitInfo.withAttribution] parses the `[install:{shortUuid}]` marker from a snapshot
 * commit message and pairs it with the matching install registry entry. Commits that
 * don't carry the marker (e.g. ones manually authored outside the engine) round-trip
 * unchanged.
 */
class CommitAttributionTest {

    private fun commit(message: String) = CommitInfo(
        sha = "0".repeat(40),
        shortSha = "0000000",
        authorName = "Studio Mac",
        authorEmail = "01234567@lighting7.local",
        whenMs = 1_700_000_000_000,
        message = message,
    )

    @Test
    fun `parses marker and resolves friendly name from the install registry`() {
        val installs = mapOf(
            "01234567-1111-2222-3333-444455556666" to "Studio Mac",
            "abcdef01-aaaa-bbbb-cccc-ddddeeeeffff" to "Touring Laptop",
        )
        val enriched = commit("Studio Mac: Snapshot 2026-01-01T12:00:00Z [install:01234567]")
            .withAttribution(installs)
        assertEquals("01234567", enriched.installShortUuid)
        assertEquals("Studio Mac", enriched.installFriendlyName)
    }

    @Test
    fun `unknown marker keeps shortUuid but leaves friendlyName null`() {
        val installs = mapOf("11111111-aaaa-bbbb-cccc-ddddeeeeffff" to "Other Rig")
        val enriched = commit("Stranger: Snapshot [install:99999999]").withAttribution(installs)
        assertEquals("99999999", enriched.installShortUuid)
        assertNull(enriched.installFriendlyName)
    }

    @Test
    fun `commits without the marker are returned unchanged`() {
        val enriched = commit("hand-typed commit message").withAttribution(emptyMap())
        assertNull(enriched.installShortUuid)
        assertNull(enriched.installFriendlyName)
    }

    @Test
    fun `marker can appear anywhere in the message`() {
        val installs = mapOf("01234567-aaaa-bbbb-cccc-ddddeeeeffff" to "Studio Mac")
        val enriched = commit("[install:01234567] then a description").withAttribution(installs)
        assertEquals("01234567", enriched.installShortUuid)
        assertEquals("Studio Mac", enriched.installFriendlyName)
    }
}
