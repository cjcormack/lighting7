package uk.me.cormack.lighting7.sync

import org.eclipse.jgit.lib.Repository
import uk.me.cormack.lighting7.state.State
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Owner of the per-project cloud-sync working tree.
 *
 * Layout: each project's tree lives at `<state.syncWorkingTreeRoot>/{projectUuid}/repo/`.
 * The repo is initialised lazily on first snapshot. Two metadata files are
 * written-once and never rewritten by the engine:
 *
 *  * `.gitignore` — excludes OS junk (`.DS_Store`, `Thumbs.db`).
 *  * `.gitattributes` — `* text=auto eol=lf` so commits are byte-stable across
 *    macOS / Linux / Windows installs of the same project, plus rules marking
 *    the `promptScripts` and `sceneImages` trees binary so prompt-book PDFs and
 *    scene images aren't EOL-normalised. (Both rules are back-filled onto
 *    pre-existing repos.)
 *
 * The snapshot pipeline calls [cleanTrackedFiles] before re-running
 * [ProjectExporter], which lets `git status` correctly surface deletions for
 * rows that no longer exist in the database.
 */
class SyncWorkingTree(private val state: State) {

    /** Resolves (without creating) the working-tree directory for a given project UUID. */
    fun pathFor(projectUuid: UUID): Path =
        state.syncWorkingTreeRoot.resolve(projectUuid.toString()).resolve("repo")

    /**
     * Open the repo at [path], initialising it if no `.git/` exists yet. The
     * returned [Repository] is a closeable native resource; callers must wrap it
     * in `.use { ... }`.
     */
    fun ensureInitialised(path: Path): Repository {
        Files.createDirectories(path)
        val repo = JGitClient.open(path) ?: JGitClient.init(path)
        writeMetadataFiles(path)
        return repo
    }

    /**
     * Delete every file and directory under [path] except the metadata that has
     * to survive between snapshots: `.git/`, `.gitignore`, `.gitattributes`.
     * Idempotent. Safe to call on an unborn repo (the export step that follows
     * will repopulate it).
     */
    fun cleanTrackedFiles(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            // Reverse order so directories are emptied before they're removed.
            stream
                .filter { it != path && !isPreserved(path, it) }
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    private fun isPreserved(root: Path, candidate: Path): Boolean {
        val rel = root.relativize(candidate).toString()
        if (rel.isEmpty()) return true
        if (rel == ".git" || rel.startsWith(".git${File.separator}")) return true
        if (rel == ".gitignore" || rel == ".gitattributes") return true
        // Prompt-book PDFs and scene images are content-addressed binary blobs, not DB-derived
        // records, so the wipe must not delete them — an install lacking the bytes locally
        // would otherwise drop the repo copy and revert the deletion onto its peers. The
        // exporter reconciles each dir against the referenced hashes (add/orphan-remove).
        return RecordHasher.BINARY_DIRS.any { prefix -> rel == prefix || rel.startsWith("$prefix${File.separator}") }
    }

    private fun writeMetadataFiles(path: Path) {
        val gitignore = path.resolve(".gitignore")
        if (!Files.exists(gitignore)) Files.writeString(gitignore, GITIGNORE_CONTENT)
        val gitattributes = path.resolve(".gitattributes")
        if (!Files.exists(gitattributes)) {
            Files.writeString(gitattributes, GITATTRIBUTES_CONTENT)
        } else {
            // Existing repos (created before binary-PDF support, or before scene images)
            // lack one or both binary rules. Ensure each is present so committed PDFs and
            // images are never EOL-normalised or textually diffed. Idempotent — each is
            // appended once, on the first snapshot after upgrade.
            for (rule in BINARY_ATTRIBUTES) {
                val current = Files.readString(gitattributes)
                if (current.contains(rule)) continue
                val sep = if (current.isEmpty() || current.endsWith("\n")) "" else "\n"
                Files.writeString(gitattributes, "$sep$rule\n", StandardOpenOption.APPEND)
            }
        }
    }

    companion object {
        private val GITIGNORE_CONTENT = """
            # OS junk that should never be committed.
            .DS_Store
            Thumbs.db
            desktop.ini
        """.trimIndent() + "\n"

        /**
         * Marks the prompt-book PDF directory as binary so git skips EOL normalisation
         * and textual diffing on those blobs. Kept as a standalone constant so
         * [writeMetadataFiles] can detect and back-fill it on already-initialised repos.
         */
        const val PROMPT_SCRIPTS_ATTRIBUTE = "promptScripts/** binary"

        /** The scene images' rule (format v23), back-filled the same way. */
        const val SCENE_IMAGES_ATTRIBUTE = "sceneImages/** binary"

        private val BINARY_ATTRIBUTES = listOf(PROMPT_SCRIPTS_ATTRIBUTE, SCENE_IMAGES_ATTRIBUTE)

        // `text=auto eol=lf` normalises line endings on commit, so a Windows
        // install committing into the same repo as a macOS install doesn't
        // produce a diff for every file on first push. `promptScripts/** binary` and
        // `sceneImages/** binary` exempt the content-addressed blobs from that normalisation.
        private val GITATTRIBUTES_CONTENT = """
            * text=auto eol=lf
            $PROMPT_SCRIPTS_ATTRIBUTE
            $SCENE_IMAGES_ATTRIBUTE
        """.trimIndent() + "\n"
    }
}
