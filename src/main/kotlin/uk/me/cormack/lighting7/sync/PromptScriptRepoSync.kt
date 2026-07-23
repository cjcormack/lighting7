package uk.me.cormack.lighting7.sync

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.state.State
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Moves prompt-book script PDFs between the per-install content store
 * ([State.promptScriptPath]) and a project's git working tree, where they live at
 * `promptScripts/{sha256}.pdf`. Every move is a raw-byte, crash-atomic copy
 * (temp file + `ATOMIC_MOVE`) so a partial write can't masquerade as a valid blob.
 *
 * PDFs are content-addressed and immutable, so they are deliberately **not records**:
 * they never enter the three-way diff, [RecordHasher], or [JGitClient.walkTree] (which
 * decodes blobs as UTF-8 and would corrupt binary bytes). Because the filename is the
 * content hash, git merges them trivially — the same path always holds identical bytes,
 * so a PDF is only ever added or removed, never conflicted.
 */
object PromptScriptRepoSync {

    private val logger = LoggerFactory.getLogger(PromptScriptRepoSync::class.java)

    /** Matches a store/tree PDF filename stem (SHA-256 hex). */
    private val HASH_REGEX = Regex("^[0-9a-f]{64}$")

    /**
     * Reconcile `[treeDir]/promptScripts/` to exactly [referencedHashes] (0 or 1 in
     * practice — a project has at most one prompt book):
     *
     *  * copy each referenced hash's PDF from the store into the tree when the store has
     *    it and the tree doesn't;
     *  * delete any `*.pdf` whose hash is no longer referenced (orphan GC — after a
     *    `scriptHash` change or a deleted book);
     *  * **never** delete a referenced hash's file, even if the store lacks the bytes.
     *    An install that pulled the book but never held the PDF locally must not drop
     *    the repo's copy and revert the deletion onto its peers.
     *
     * Called from [ProjectExporter.export] (snapshot + manual export) and from the
     * auto-merge path so the merge commit carries the PDF matching the merged
     * `scriptHash`.
     */
    fun reconcileTree(state: State, projectUuid: UUID, referencedHashes: Set<String>, treeDir: Path) {
        val dir = treeDir.resolve(RecordHasher.PROMPT_SCRIPTS_DIR)

        // Orphan GC: drop any .pdf not backing a currently-referenced hash. A junk
        // filename (non-hash) is never referenced, so it's cleaned up here too.
        if (Files.isDirectory(dir)) {
            Files.newDirectoryStream(dir, "*.pdf").use { stream ->
                for (pdf in stream) {
                    val hash = pdf.fileName.toString().removeSuffix(".pdf")
                    if (hash !in referencedHashes) Files.deleteIfExists(pdf)
                }
            }
        }

        // Add referenced PDFs the tree is missing, sourced byte-accurately from the store.
        for (hash in referencedHashes) {
            val dst = dir.resolve("$hash.pdf")
            if (Files.exists(dst)) continue
            val src = state.promptScriptPath(projectUuid.toString(), hash)
            if (!Files.exists(src)) {
                logger.warn(
                    "Prompt-book PDF {} referenced by project {} is absent from the local store; " +
                        "leaving the repo untouched (a peer holding the bytes will supply it).",
                    hash, projectUuid,
                )
                continue
            }
            copyAtomic(src, dst)
        }
    }

    /**
     * Copy every PDF under [treeDir]'s `promptScripts` directory into the local store
     * (keyed by [projectUuid]) when the store doesn't already hold it. Byte-accurate. Called on
     * pull (from the checked-out git working tree) and on manual import (from the export
     * folder) so the UI can render the PDF without a manual re-import.
     */
    fun hydrateStore(state: State, projectUuid: UUID, treeDir: Path) {
        val dir = treeDir.resolve(RecordHasher.PROMPT_SCRIPTS_DIR)
        if (!Files.isDirectory(dir)) return
        Files.newDirectoryStream(dir, "*.pdf").use { stream ->
            for (pdf in stream) {
                val hash = pdf.fileName.toString().removeSuffix(".pdf")
                // Guard against a malformed repo dropping arbitrary filenames into the store.
                if (!HASH_REGEX.matches(hash)) {
                    logger.warn("Skipping non-hash prompt-script file {} in {}", pdf.fileName, projectUuid)
                    continue
                }
                val dst = state.promptScriptPath(projectUuid.toString(), hash)
                if (Files.exists(dst)) continue
                copyAtomic(pdf, dst)
            }
        }
    }

    /**
     * Copy [src] to [dst] atomically: write to a temp file in the destination directory,
     * then `ATOMIC_MOVE` it into place. A crash mid-copy leaves only the discarded temp
     * file — never a truncated `{hash}.pdf`, which would silently violate the
     * content-addressed invariant (its bytes no longer hashing to its name) and could be
     * committed/pushed or served as if intact. Mirrors the upload route's write path
     * (`projectPromptBooks.kt`). Falls back to a plain replace on filesystems without
     * atomic move (rare; the temp still bounds the corruption window to same-dir).
     */
    private fun copyAtomic(src: Path, dst: Path) {
        Files.createDirectories(dst.parent)
        val tmp = Files.createTempFile(dst.parent, ".pdf-", ".tmp")
        try {
            Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING)
            try {
                Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
