package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoMachineOverride
import uk.me.cormack.lighting7.models.DaoMachineOverrides
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.state.State
import java.nio.file.Files
import java.util.UUID

/**
 * Clones a project by round-tripping it through the cloud-sync export format:
 * **export → mint fresh UUIDs → import**.
 *
 * This exists instead of a hand-written table-by-table copy because the hand-written one
 * silently rotted: it covered 5 of 21 project-owned tables, so clones lost their patch
 * list, groups, universes, Layer 3 assignments, triggers, prompt book and more. Reusing
 * the exporter/importer means a table is cloned the moment it's wired into sync — which
 * `CLAUDE.md`'s schema-change rules already require — and `ProjectCloneTest` holds that
 * guarantee in place.
 *
 * What a clone deliberately does *not* inherit:
 *  * `isCurrent` / `activeStackId` — cloning must not move the operator's playhead
 *    ([ProjectImporter.import] forces both).
 *  * Cloud-sync state (`sync_configs`, `sync_state`, linked repo, session history) — the
 *    clone is a new project with no remote and no sync history.
 *  * AI conversations — local scratch, not show content (also absent from the export).
 *
 * What it *does* inherit beyond the portable graph: `machine_overrides`, notably the
 * per-universe controller IPs. Those are excluded from the export by design (they're
 * per-rig), but a clone lands on the same machine as its source, so carrying them means a
 * cloned project can actually output DMX without re-entering every address.
 */
class ProjectCloner(private val state: State) {

    private val logger = LoggerFactory.getLogger(ProjectCloner::class.java)

    /**
     * @property uuidMapping source → clone identities, covering the project row and every
     *   record including embedded children. Returned so callers can correlate the two graphs —
     *   the clone test inverts it to compare the exports byte-for-byte. Note the test checks
     *   identity disjointness against the exports themselves rather than against this map,
     *   which is disjoint by construction and so proves nothing on its own.
     */
    data class Result(
        val projectId: Int,
        val projectUuid: String,
        val name: String,
        val recordsCloned: Int,
        val uuidMapping: Map<UUID, UUID>,
    )

    /**
     * Clone [sourceProjectId] as [name]. [description] overrides the source's description
     * when non-null; null keeps the source's.
     *
     * @throws ImportError carrying the status the route should report: 404 for an unknown
     *   source project, 409 for a name collision.
     */
    fun clone(sourceProjectId: Int, name: String, description: String?): Result {
        val sourceName = transaction(state.database) {
            val source = DaoProject.findById(sourceProjectId)
                ?: throw ImportError.notFound("Project not found: $sourceProjectId")
            // The importer checks the name too, but failing here skips a full export of a
            // project that could never land.
            if (DaoProject.find { DaoProjects.name eq name }.firstOrNull() != null) {
                throw ImportError.conflict("A project named \"$name\" already exists. Provide a different name.")
            }
            source.name
        }

        val staging = Files.createTempDirectory("project-clone-")
        try {
            ProjectExporter(state).export(sourceProjectId, staging)
            val mapping = ExportUuidRemapper.remapToFreshUuids(staging)
            val imported = ProjectImporter(state).import(
                sourceDir = staging,
                nameOverride = name,
                descriptionOverride = description,
            )

            copyMachineOverrides(sourceProjectId, imported.projectId, mapping)

            logger.info(
                "Cloned project {} (\"{}\") to {} (\"{}\"): {} record(s)",
                sourceProjectId, sourceName, imported.projectId, name, mapping.size - 1,
            )
            return Result(
                projectId = imported.projectId,
                projectUuid = imported.projectUuid,
                name = imported.name,
                // One identity per copied row (embedded children included), minus the project
                // row itself, which isn't a record.
                recordsCloned = mapping.size - 1,
                uuidMapping = mapping,
            )
        } finally {
            runCatching { staging.toFile().deleteRecursively() }
                .onFailure { logger.warn("Failed to clean clone staging dir {}: {}", staging, it.message) }
        }
    }

    /**
     * Copy the source project's machine-local overrides onto the clone, translating each
     * `recordUuid` through [mapping].
     *
     * Best-effort after the import has committed, mirroring the PDF hydrate in
     * [ProjectImporter.import]: a clone missing its controller IPs is a nuisance the
     * operator can fix in the universe editor, not a reason to throw away the clone.
     */
    private fun copyMachineOverrides(
        sourceProjectId: Int,
        newProjectId: Int,
        mapping: Map<UUID, UUID>,
    ) {
        runCatching {
            transaction(state.database) {
                val newProject = DaoProject.findById(newProjectId)
                    ?: error("Cloned project $newProjectId vanished")
                DaoMachineOverride.find { DaoMachineOverrides.project eq sourceProjectId }
                    .forEach { source ->
                        val mapped = mapping[source.recordUuid]
                        if (mapped == null) {
                            // Stale override for a record that no longer exists — the export
                            // never saw it, so there's nothing to attach it to.
                            logger.debug(
                                "Skipping machine override {}.{} for unknown record {}",
                                source.targetTable, source.fieldName, source.recordUuid,
                            )
                            return@forEach
                        }
                        DaoMachineOverride.new {
                            project = newProject
                            targetTable = source.targetTable
                            recordUuid = mapped
                            fieldName = source.fieldName
                            valueJson = source.valueJson
                        }
                    }
            }
        }.onFailure {
            logger.warn(
                "Clone of project {} completed but machine overrides (e.g. controller IPs) " +
                    "were not copied: {}",
                sourceProjectId, it.message,
            )
        }
    }
}
