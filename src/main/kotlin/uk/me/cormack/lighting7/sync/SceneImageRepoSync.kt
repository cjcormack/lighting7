package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.v1.core.eq
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoStageElement
import uk.me.cormack.lighting7.models.DaoStageElements
import uk.me.cormack.lighting7.models.SCENE_IMAGE_HASH
import uk.me.cormack.lighting7.models.paintHashesOf
import uk.me.cormack.lighting7.state.SceneImageFormat
import uk.me.cormack.lighting7.state.State
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Moves the images painted on scene cloths (scrim plan P2, §3.3) between the per-install store
 * ([State.sceneImages]) and a project's git working tree, where they live at
 * `sceneImages/{sha256}.{png|jpg}` — `PromptScriptRepoSync`'s twin, for the same reasons.
 *
 * Images are content-addressed and immutable, so they are **not records**: they never enter the
 * three-way diff, [RecordHasher] or [JGitClient.walkTree] (which decodes blobs as UTF-8). Because the
 * filename is the content hash, git merges them trivially — a path always holds the same bytes, so
 * an image is only ever added or removed, never conflicted. Every move is a raw-byte, crash-atomic
 * copy (temp file, then an atomic move).
 *
 * Only the hashes some element references travel: an upload whose element was never saved stays on
 * the machine that made it until the store's prune takes it.
 */
object SceneImageRepoSync {

    private val logger = LoggerFactory.getLogger(SceneImageRepoSync::class.java)

    /** Every hash [projectId]'s elements paint with. Must run inside a transaction. */
    fun referencedHashes(projectId: Int): Set<String> =
        DaoStageElement.find { DaoStageElements.project eq projectId }
            .flatMap { paintHashesOf(it.params) }
            .toSet()

    /**
     * Reconcile `[treeDir]/sceneImages/` to exactly [referencedHashes]:
     *
     *  * copy each referenced image from the store into the tree when the store has it and the tree
     *    does not;
     *  * delete every file whose hash is no longer referenced (an element repainted or deleted), and
     *    every file whose name is not an image's;
     *  * **never** delete a referenced hash's file, even when the store lacks the bytes — an install
     *    that pulled the element but never held the image must not drop the repo's copy and revert
     *    the deletion onto its peers.
     *
     * Called from [ProjectExporter.export] and from the auto-merge path, so the merge commit carries
     * the images the merged elements name.
     */
    fun reconcileTree(state: State, projectUuid: UUID, referencedHashes: Set<String>, treeDir: Path) {
        val dir = treeDir.resolve(RecordHasher.SCENE_IMAGES_DIR)
        val present = mutableSetOf<String>()
        if (Files.isDirectory(dir)) {
            Files.list(dir).use { it.toList() }.forEach { file ->
                val hash = imageHash(file)
                if (hash == null || hash !in referencedHashes) {
                    if (Files.isRegularFile(file)) Files.deleteIfExists(file)
                } else {
                    present += hash
                }
            }
        }
        for (hash in referencedHashes - present) {
            val src = state.sceneImages.original(projectUuid.toString(), hash)
            if (src == null) {
                logger.warn(
                    "Scene image {} referenced by project {} is absent from the local store; leaving the repo " +
                        "untouched (a peer holding the bytes will supply it).",
                    hash, projectUuid,
                )
                continue
            }
            copyAtomic(src.first, dir.resolve("$hash.${src.second.ext}"))
        }
    }

    /**
     * Copy every image under [treeDir]'s `sceneImages` into [projectUuid]'s store when the store
     * does not already hold it — on pull (from the checked-out tree) and on import, which is also
     * what fills a clone's store (cloning is export → import). The store checks that the bytes are
     * the image their name says before it takes them.
     */
    fun hydrateStore(state: State, projectUuid: UUID, treeDir: Path) {
        val dir = treeDir.resolve(RecordHasher.SCENE_IMAGES_DIR)
        if (!Files.isDirectory(dir)) return
        Files.list(dir).use { it.toList() }.forEach { file ->
            val hash = imageHash(file) ?: run {
                logger.warn("Skipping non-image file {} in {}'s scene images", file.fileName, projectUuid)
                return@forEach
            }
            state.sceneImages.hydrate(projectUuid.toString(), hash, file)
        }
    }

    /** A tree file's hash when its name is `{sha256}.{png|jpg}`, else null. */
    private fun imageHash(file: Path): String? {
        if (!Files.isRegularFile(file)) return null
        val name = file.fileName.toString()
        val hash = name.substringBefore('.')
        val ext = name.substringAfter('.', "")
        return hash.takeIf { SCENE_IMAGE_HASH.matches(it) && SceneImageFormat.entries.any { f -> f.ext == ext } }
    }

    private fun copyAtomic(src: Path, dst: Path) {
        Files.createDirectories(dst.parent)
        val tmp = Files.createTempFile(dst.parent, ".image-", ".tmp")
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
