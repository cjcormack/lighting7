package uk.me.cormack.lighting7.sync

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure unit tests for [ThreeWayDiff]. Each test row mirrors one entry of the outcome
 * matrix in `docs/sync-engineering.md`. Hash strings are arbitrary placeholders — the diff
 * doesn't read them, only compares for equality.
 *
 * Phase 7: each side carries `(hash, isDeleted)`. Tombstones share a single canonical body
 * hash because their on-disk content is the constant `{ "tombstone": true }`.
 */
class ThreeWayDiffTest {

    private val a = RecordKey("cues", UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val b = RecordKey("cues", UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val c = RecordKey("cueStacks", UUID.fromString("00000000-0000-0000-0000-000000000003"))

    private fun live(hash: String) = SnapshotMeta(hash, isDeleted = false)
    private fun tombstone() = SnapshotMeta("hTombstone", isDeleted = true)

    @Test
    fun `unchanged record on both sides is NoOp`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to live("h1")),
            remote = mapOf(a to live("h1")),
            lastSynced = mapOf(a to live("h1")),
        )
        assertEquals(DiffOutcome.NoOp, out[a])
    }

    @Test
    fun `local changed, remote unchanged from base — TakeLocal`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to live("hLocal")),
            remote = mapOf(a to live("hBase")),
            lastSynced = mapOf(a to live("hBase")),
        )
        assertEquals(DiffOutcome.TakeLocal, out[a])
    }

    @Test
    fun `remote changed, local unchanged from base — TakeRemote`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to live("hBase")),
            remote = mapOf(a to live("hRemote")),
            lastSynced = mapOf(a to live("hBase")),
        )
        assertEquals(DiffOutcome.TakeRemote, out[a])
    }

    @Test
    fun `both sides changed to the same value — NoOp (concurrent equal edits)`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to live("hSame")),
            remote = mapOf(a to live("hSame")),
            lastSynced = mapOf(a to live("hBase")),
        )
        assertEquals(DiffOutcome.NoOp, out[a])
    }

    @Test
    fun `both sides changed to different live values — Conflict EDIT_EDIT`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to live("hLocal")),
            remote = mapOf(a to live("hRemote")),
            lastSynced = mapOf(a to live("hBase")),
        )
        assertEquals(DiffOutcome.Conflict(ConflictKind.EDIT_EDIT), out[a])
    }

    @Test
    fun `record present on both sides with no shared base — Conflict (Phase 4 to 5 upgrade case)`() {
        // The very first Phase 5 sync after upgrading from Phase 4 finds an empty
        // sync_state. If local and remote disagree on a record, we conservatively
        // surface a conflict rather than silently picking a side.
        val out = ThreeWayDiff.compute(
            local = mapOf(a to live("hLocal")),
            remote = mapOf(a to live("hRemote")),
            lastSynced = emptyMap(),
        )
        assertEquals(DiffOutcome.Conflict(ConflictKind.EDIT_EDIT), out[a])
    }

    @Test
    fun `record present on both sides with no shared base but equal — NoOp`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to live("hSame")),
            remote = mapOf(a to live("hSame")),
            lastSynced = emptyMap(),
        )
        assertEquals(DiffOutcome.NoOp, out[a])
    }

    @Test
    fun `local-only live record — TakeLocal`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to live("hLocal")),
            remote = emptyMap(),
            lastSynced = emptyMap(),
        )
        assertEquals(DiffOutcome.TakeLocal, out[a])
    }

    @Test
    fun `remote-only live record — TakeRemote`() {
        val out = ThreeWayDiff.compute(
            local = emptyMap(),
            remote = mapOf(a to live("hRemote")),
            lastSynced = emptyMap(),
        )
        assertEquals(DiffOutcome.TakeRemote, out[a])
    }

    @Test
    fun `record absent on both sides but in sync_state — NoOp (orphan, GC by bootstrap)`() {
        val out = ThreeWayDiff.compute(
            local = emptyMap(),
            remote = emptyMap(),
            lastSynced = mapOf(a to live("hBase")),
        )
        assertEquals(DiffOutcome.NoOp, out[a])
    }

    @Test
    fun `multiple records mix — produces correct outcomes for each independently`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to live("hLocal"), b to live("hSame"), c to live("hLocalC")),
            remote = mapOf(a to live("hRemote"), b to live("hSame"), c to live("hRemoteC")),
            lastSynced = mapOf(a to live("hBase"), b to live("hBase"), c to live("hBaseC")),
        )
        assertEquals(DiffOutcome.Conflict(ConflictKind.EDIT_EDIT), out[a])
        assertEquals(DiffOutcome.NoOp, out[b])
        assertEquals(DiffOutcome.Conflict(ConflictKind.EDIT_EDIT), out[c])
        assertEquals(3, out.size)
    }

    // ─── Phase 7 tombstone-aware cases ────────────────────────────

    @Test
    fun `local tombstone, remote live, both moved from live base — Conflict DELETE_EDIT`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to tombstone()),
            remote = mapOf(a to live("hRemoteEdit")),
            lastSynced = mapOf(a to live("hBase")),
        )
        assertEquals(DiffOutcome.Conflict(ConflictKind.DELETE_EDIT), out[a])
    }

    @Test
    fun `local live, remote tombstone, both moved from live base — Conflict EDIT_DELETE`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to live("hLocalEdit")),
            remote = mapOf(a to tombstone()),
            lastSynced = mapOf(a to live("hBase")),
        )
        assertEquals(DiffOutcome.Conflict(ConflictKind.EDIT_DELETE), out[a])
    }

    @Test
    fun `local tombstone, remote unchanged from live base — TakeLocal (push the tombstone)`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to tombstone()),
            remote = mapOf(a to live("hBase")),
            lastSynced = mapOf(a to live("hBase")),
        )
        assertEquals(DiffOutcome.TakeLocal, out[a])
    }

    @Test
    fun `local unchanged, remote tombstone — TakeRemote (accept deletion)`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to live("hBase")),
            remote = mapOf(a to tombstone()),
            lastSynced = mapOf(a to live("hBase")),
        )
        assertEquals(DiffOutcome.TakeRemote, out[a])
    }

    @Test
    fun `both sides tombstoned — NoOp (covered by hash-equality)`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to tombstone()),
            remote = mapOf(a to tombstone()),
            lastSynced = mapOf(a to live("hBase")),
        )
        assertEquals(DiffOutcome.NoOp, out[a])
    }

    @Test
    fun `local tombstone present, remote absent (already propagated and GC'd elsewhere) — NoOp`() {
        val out = ThreeWayDiff.compute(
            local = mapOf(a to tombstone()),
            remote = emptyMap(),
            lastSynced = mapOf(a to tombstone()),
        )
        assertEquals(DiffOutcome.NoOp, out[a])
    }

    @Test
    fun `remote tombstone present, local absent (we never had it) — NoOp`() {
        val out = ThreeWayDiff.compute(
            local = emptyMap(),
            remote = mapOf(a to tombstone()),
            lastSynced = emptyMap(),
        )
        assertEquals(DiffOutcome.NoOp, out[a])
    }

    @Test
    fun `local live, remote absent, base says live — TakeLocal with warning (history rewrite case)`() {
        // Remote dropped a record without a tombstone (manual rm, history rewrite, or a
        // pre-Phase-7 peer that doesn't write tombstones). We can't tell from this side
        // whether the deletion was intentional, so we resurrect with a logged warning —
        // same fallback as Phase 5/6, not a regression.
        val out = ThreeWayDiff.compute(
            local = mapOf(a to live("hLocal")),
            remote = emptyMap(),
            lastSynced = mapOf(a to live("hBase")),
        )
        assertEquals(DiffOutcome.TakeLocal, out[a])
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

    @Test
    fun `RecordHasher recognises tombstone path and emits isDeleted snapshot`() {
        val uuid = UUID.fromString("00000000-0000-0000-0000-000000000099")
        val blobs = mapOf(
            "tombstones/cues/$uuid.json" to "{\n  \"tombstone\": true\n}\n",
        )
        val out = RecordHasher.fromBlobs(blobs)
        val key = RecordKey("cues", uuid)
        assertTrue(out.containsKey(key), "Tombstone must produce a snapshot")
        assertTrue(out[key]!!.isDeleted, "isDeleted must be true for tombstone snapshot")
        assertEquals(1, out[key]!!.files.size)
    }

    @Test
    fun `RecordHasher live record wins over tombstone collision (defensive)`() {
        // The wipe-and-export pipeline shouldn't produce both a live record and a
        // tombstone for the same key, but if some peer's commit ever did, the live
        // record wins so the data isn't lost. (A WARN is logged at runtime.)
        val uuid = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        val blobs = mapOf(
            "tombstones/cues/$uuid.json" to "{\n  \"tombstone\": true\n}\n",
            "cues/$uuid.json" to "{\"uuid\":\"$uuid\",\"name\":\"alive\"}",
        )
        val out = RecordHasher.fromBlobs(blobs)
        val key = RecordKey("cues", uuid)
        assertTrue(out.containsKey(key))
        assertEquals(false, out[key]!!.isDeleted, "Live record should win the collision")
    }
}
