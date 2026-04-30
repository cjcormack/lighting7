package uk.me.cormack.lighting7.sync

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure unit tests for [ThreeWayDiff]. The test cases mirror the outcome matrix in the
 * Phase 5 plan / `docs/sync-engineering.md` — each row is one assertion below. The hash
 * values are arbitrary placeholders; the diff doesn't care about their content, only
 * about equality.
 */
class ThreeWayDiffTest {

    private val a = RecordKey("cues", UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val b = RecordKey("cues", UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val c = RecordKey("cueStacks", UUID.fromString("00000000-0000-0000-0000-000000000003"))

    @Test
    fun `unchanged record on both sides is NoOp`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to "h1"),
            remote = mapOf(a to "h1"),
            lastSynced = mapOf(a to "h1"),
        )
        assertEquals(DiffOutcome.NoOp, out[a])
    }

    @Test
    fun `local changed, remote unchanged from base — TakeLocal`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to "hLocal"),
            remote = mapOf(a to "hBase"),
            lastSynced = mapOf(a to "hBase"),
        )
        assertEquals(DiffOutcome.TakeLocal, out[a])
    }

    @Test
    fun `remote changed, local unchanged from base — TakeRemote`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to "hBase"),
            remote = mapOf(a to "hRemote"),
            lastSynced = mapOf(a to "hBase"),
        )
        assertEquals(DiffOutcome.TakeRemote, out[a])
    }

    @Test
    fun `both sides changed to the same value — NoOp (concurrent equal edits)`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to "hSame"),
            remote = mapOf(a to "hSame"),
            lastSynced = mapOf(a to "hBase"),
        )
        assertEquals(DiffOutcome.NoOp, out[a])
    }

    @Test
    fun `both sides changed to different values — Conflict`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to "hLocal"),
            remote = mapOf(a to "hRemote"),
            lastSynced = mapOf(a to "hBase"),
        )
        assertEquals(DiffOutcome.Conflict(ConflictKind.EDIT_EDIT), out[a])
    }

    @Test
    fun `record present on both sides with no shared base — Conflict (Phase 4 to 5 upgrade case)`() {
        // The very first Phase 5 sync after upgrading from Phase 4 finds an empty
        // sync_state. If local and remote disagree on a record, we conservatively
        // surface a conflict rather than silently picking a side.
        val out = ThreeWayDiff.compute(
            local = mapOf(a to "hLocal"),
            remote = mapOf(a to "hRemote"),
            lastSynced = emptyMap(),
        )
        assertEquals(DiffOutcome.Conflict(ConflictKind.EDIT_EDIT), out[a])
    }

    @Test
    fun `record present on both sides with no shared base but equal — NoOp`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to "hSame"),
            remote = mapOf(a to "hSame"),
            lastSynced = emptyMap(),
        )
        assertEquals(DiffOutcome.NoOp, out[a])
    }

    @Test
    fun `local-only record — TakeLocal (Phase 5 punts on tombstones)`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to "hLocal"),
            remote = emptyMap(),
            lastSynced = emptyMap(),
        )
        assertEquals(DiffOutcome.TakeLocal, out[a])
    }

    @Test
    fun `remote-only record — TakeRemote (Phase 5 punts on tombstones)`() {
        val out = ThreeWayDiff.compute(
            local = emptyMap(),
            remote = mapOf(a to "hRemote"),
            lastSynced = emptyMap(),
        )
        assertEquals(DiffOutcome.TakeRemote, out[a])
    }

    @Test
    fun `record absent on both sides but in sync_state — NoOp (orphan, GC by bootstrap)`() {
        val out = ThreeWayDiff.compute(
            local = emptyMap(),
            remote = emptyMap(),
            lastSynced = mapOf(a to "hBase"),
        )
        assertEquals(DiffOutcome.NoOp, out[a])
    }

    @Test
    fun `multiple records mix — produces correct outcomes for each independently`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to "hLocal", b to "hSame", c to "hLocalC"),
            remote = mapOf(a to "hRemote", b to "hSame", c to "hRemoteC"),
            lastSynced = mapOf(a to "hBase", b to "hBase", c to "hBaseC"),
        )
        assertEquals(DiffOutcome.Conflict(ConflictKind.EDIT_EDIT), out[a])
        assertEquals(DiffOutcome.NoOp, out[b])
        assertEquals(DiffOutcome.Conflict(ConflictKind.EDIT_EDIT), out[c])
        assertEquals(3, out.size)
    }

    @Test
    fun `RecordHasher script-pair groups meta + body and hashes the concatenation`() {
        val uuid = UUID.fromString("00000000-0000-0000-0000-000000000010")
        val blobs = mapOf(
            "scripts/$uuid.meta.json" to "{\"name\":\"a\"}\n",
            "scripts/$uuid.kts" to "println(\"hi\")\n",
        )
        val out = RecordHasher.fromBlobs(blobs)
        val key = RecordKey("scripts", uuid)
        assertTrue(out.containsKey(key), "Script pair must be a single record")
        assertEquals(2, out[key]!!.files.size)
        // A body-only edit must change the hash.
        val bodyEdited = blobs.toMutableMap().apply {
            this["scripts/$uuid.kts"] = "println(\"bye\")\n"
        }
        val out2 = RecordHasher.fromBlobs(bodyEdited)
        kotlin.test.assertNotEquals(out[key]!!.hash, out2[key]!!.hash)
    }

    @Test
    fun `RecordHasher orphan script meta with no body is silently skipped`() {
        val uuid = UUID.fromString("00000000-0000-0000-0000-000000000020")
        val blobs = mapOf("scripts/$uuid.meta.json" to "{\"name\":\"a\"}\n")
        val out = RecordHasher.fromBlobs(blobs)
        assertTrue(out.isEmpty(), "Orphan halves must not produce a record")
    }

    @Test
    fun `RecordHasher ignores top-level metadata files`() {
        val blobs = mapOf(
            "formatVersion.json" to "{\"formatVersion\":1}",
            "project.json" to "{\"name\":\"x\"}",
            ".gitignore" to ".DS_Store",
        )
        val out = RecordHasher.fromBlobs(blobs)
        assertTrue(out.isEmpty())
    }
}
