package uk.me.cormack.lighting7.testsupport

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.ExportUuidRemapper
import uk.me.cormack.lighting7.sync.ProjectExporter
import uk.me.cormack.lighting7.sync.ProjectImporter
import java.nio.file.Files

/**
 * A second copy of project [sourceId] as [name], every record keeping its uuid and only the
 * project's own uuid fresh — what a venue version will be (venue plan P2): the same records, in
 * another project. The importer refuses only a project-uuid collision, so this is export, remap
 * the one uuid, import. Returns the copy's project id.
 */
fun importWithKeptRecordUuids(state: State, sourceId: Int, name: String): Int {
    val staging = Files.createTempDirectory("kept-uuid-import-")
    try {
        ProjectExporter(state).export(sourceId, staging)
        val projectUuid = transaction(state.database) { DaoProject[sourceId].uuid }
        ExportUuidRemapper.applyMapping(staging, mapOf(projectUuid to java.util.UUID.randomUUID()))
        return ProjectImporter(state).import(staging, nameOverride = name).projectId
    } finally {
        staging.toFile().deleteRecursively()
    }
}
