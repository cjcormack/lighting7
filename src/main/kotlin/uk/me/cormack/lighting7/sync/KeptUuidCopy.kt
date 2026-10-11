package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.state.State
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * A second copy of a project in which every record keeps its uuid and only the project's own uuid
 * is fresh — what a venue version is (venue plan P2): the same records, in another project. It is
 * the clone's machinery less the record remap ([ProjectCloner] mints a uuid for everything): export,
 * remap the one project uuid, import. The importer refuses only a project-uuid collision, and record
 * uuids are not unique across projects (`docs/fixtures-engineering.md` §"The rig scope"), so the two
 * copies sit side by side.
 *
 * What the copy carries is exactly what an import carries: the portable graph, and the scene images
 * and prompt-book PDFs it names, hydrated into the copy's own store under its own uuid. Machine-local
 * rows (`machine_overrides`, sync config) are not copied — [ProjectCloner] copies the overrides for a
 * clone; a venue version deliberately has none (`docs/fixtures-engineering.md` §"Venue versions").
 */
object KeptUuidCopy {

    data class Result(val projectId: Int, val projectUuid: UUID)

    /**
     * Copy [sourceId] as [name] of [kind]. [projectUuid] is the copy's uuid, minted here when null.
     * [inTransaction] runs inside the import's transaction with the new project, so anything the
     * caller records about it commits or rolls back with the rows.
     */
    fun copy(
        state: State,
        sourceId: Int,
        name: String,
        kind: ProjectKind = ProjectKind.SHOW,
        projectUuid: UUID = UUID.randomUUID(),
        inTransaction: (DaoProject) -> Unit = {},
    ): Result {
        val staging = Files.createTempDirectory("kept-uuid-copy-")
        try {
            ProjectExporter(state).export(sourceId, staging)
            val sourceUuid = transaction(state.database) { DaoProject[sourceId].uuid }
            return fromArchive(state, staging, sourceUuid, name, kind, projectUuid, inTransaction)
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    /**
     * [copy] from an archive already on disk — a venue repo's tagged commit, read back
     * (`sync/VenueRepoSync.kt`) — whose project uuid is [sourceUuid]. [archive] is rewritten in place.
     */
    fun fromArchive(
        state: State,
        archive: Path,
        sourceUuid: UUID,
        name: String,
        kind: ProjectKind,
        projectUuid: UUID = UUID.randomUUID(),
        inTransaction: (DaoProject) -> Unit = {},
    ): Result {
        ExportUuidRemapper.applyMapping(archive, mapOf(sourceUuid to projectUuid))
        val imported = ProjectImporter(state).import(
            archive, nameOverride = name, kind = kind, inTransaction = inTransaction,
        )
        return Result(imported.projectId, UUID.fromString(imported.projectUuid))
    }
}
