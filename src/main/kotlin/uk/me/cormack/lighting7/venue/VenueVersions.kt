package uk.me.cormack.lighting7.venue

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoVenueVersion
import uk.me.cormack.lighting7.models.DaoVenueVersions
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.VENUE_VERSION_NOTE_MAX
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.models.toIsoUtc
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.ExportUuidRemapper
import uk.me.cormack.lighting7.sync.KeptUuidCopy
import uk.me.cormack.lighting7.sync.ProjectExporter
import uk.me.cormack.lighting7.sync.ProjectImporter
import uk.me.cormack.lighting7.sync.PromptScriptRepoSync
import uk.me.cormack.lighting7.sync.SceneImageRepoSync
import java.nio.file.Files
import java.util.UUID

/**
 * A venue's versions (venue plan D3, P2): publishing the draft as a frozen snapshot, the history,
 * the diff between any two states, and discarding the draft back to the installed version.
 *
 * **A version is a frozen project.** Publishing copies the venue — the draft, which is the venue
 * project's own rows — through [KeptUuidCopy] into a new project of kind `VENUE_VERSION`: a fresh
 * project uuid, every record uuid kept, its scene images and prompt-book PDFs hydrated into its own
 * store. Its `venue_versions` row commits in the same transaction as its rows. It is never listed,
 * never current and refuses every write (`routes/projects.kt`'s guard), so it stays what was
 * published. See `docs/fixtures-engineering.md` §"Venue versions".
 */
class VenueVersions(private val state: State) {

    private val logger = LoggerFactory.getLogger(VenueVersions::class.java)

    /** One publish or discard at a time, desk-wide: both read the latest version and then act on it. */
    private val lock = Mutex()

    /** A snapshot's records never change, so its export is read once (a bounded cache, by project id). */
    private val snapshotRecords = object : LinkedHashMap<Int, VenueDiff.Records>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, VenueDiff.Records>) = size > 16
    }

    // ── Reads ───────────────────────────────────────────────────────────────────

    /** Every version, newest first, with the draft's change count against the installed one. */
    fun history(venueId: Int): VenueHistoryDto {
        val (name, versions) = transaction(state.database) {
            val venue = requireVenue(venueId)
            venue.name to versionsOf(venueId).map { it.toDto() }
        }
        val draft = draftDiff(venueId, versions.firstOrNull())
        return VenueHistoryDto(
            venueId = venueId,
            name = name,
            installed = versions.firstOrNull()?.number,
            draft = VenueDraftDto(changes = draft.size, outputChanges = draft.output.size),
            versions = versions,
        )
    }

    /**
     * What changed from [from] to [to]: each a version number, 0 for the empty venue before v1, or
     * null for the draft.
     */
    fun diff(venueId: Int, from: Int?, to: Int?): VenueDiffDto {
        val (fromProject, toProject) = transaction(state.database) {
            requireVenue(venueId)
            snapshotOf(venueId, from) to snapshotOf(venueId, to)
        }
        val diff = VenueDiff.compare(records(venueId, from, fromProject), records(venueId, to, toProject))
        return VenueDiffDto(from = from, to = to, output = diff.output, drawing = diff.drawing)
    }

    // ── Writes ──────────────────────────────────────────────────────────────────

    /**
     * Publish the draft as the next version, with [note]; [publishedBy] is the account's name, null
     * on a desk with no accounts. Refused when the draft is the installed version already.
     */
    suspend fun publish(venueId: Int, note: String, publishedBy: String?): VenueVersionDto = lock.withLock {
        val trimmed = note.trim()
        if (trimmed.isEmpty()) throw VenueException.badRequest(CODE_NOTE_REQUIRED, "A version needs a note saying what changed")
        if (trimmed.length > VENUE_VERSION_NOTE_MAX) {
            throw VenueException.badRequest(CODE_NOTE_TOO_LONG, "A note may be at most $VENUE_VERSION_NOTE_MAX characters")
        }
        withContext(Dispatchers.IO) {
            val installed = transaction(state.database) {
                requireVenue(venueId)
                versionsOf(venueId).firstOrNull()?.toDto()
            }
            if (draftDiff(venueId, installed).size == 0) {
                throw VenueException.conflict(
                    CODE_DRAFT_EMPTY,
                    if (installed == null) "The venue is empty: there is nothing to publish"
                    else "Nothing to publish: the draft is v${installed.number}",
                )
            }
            val number = (installed?.number ?: 0) + 1
            val snapshotUuid = UUID.randomUUID()
            var published: VenueVersionDto? = null
            KeptUuidCopy.copy(
                state, venueId,
                name = snapshotName(snapshotUuid),
                kind = ProjectKind.VENUE_VERSION,
                projectUuid = snapshotUuid,
            ) { snapshot ->
                val version = DaoVenueVersion.new {
                    venueProject = DaoProject[venueId]
                    this.number = number
                    this.note = trimmed
                    publishedAt = nowUtc()
                    this.publishedBy = publishedBy
                    snapshotProject = snapshot
                }
                published = version.toDto()
            }
            logger.info("Published venue {} v{} ({})", venueId, number, snapshotUuid)
            published ?: error("Version row was not written")
        }
    }

    /**
     * Throw the draft away: the venue's rows become the installed version's again, uuids and all,
     * so every machine-local override keyed by a record uuid (a universe's IP) still applies. The
     * venue's name and description are its own, not versioned, and stay. A live venue reloads.
     */
    suspend fun discard(venueId: Int): VenueHistoryDto {
        lock.withLock {
            withContext(Dispatchers.IO) {
                val installed = transaction(state.database) {
                    val venue = requireVenue(venueId)
                    val version = versionsOf(venueId).firstOrNull()
                        ?: throw VenueException.conflict(CODE_NOTHING_PUBLISHED, "Nothing has been published to go back to")
                    Installed(version.number, version.snapshotProject.id.value, version.snapshotProject.uuid, venue.uuid)
                }
                val venueUuid = installed.venueUuid
                val staging = Files.createTempDirectory("venue-discard-")
                try {
                    ProjectExporter(state).export(installed.snapshotId, staging)
                    ExportUuidRemapper.applyMapping(staging, mapOf(installed.snapshotUuid to venueUuid))
                    transaction(state.database) {
                        val venue = DaoProject[venueId]
                        val name = venue.name
                        val description = venue.description
                        ProjectImporter(state).replaceFromWorkingTree(venueId, staging)
                        venue.name = name
                        venue.description = description
                    }
                    // The store may have pruned an image the draft stopped naming; the version still names it.
                    runCatching { SceneImageRepoSync.hydrateStore(state, venueUuid, staging) }
                        .onFailure { logger.warn("Scene image hydrate after discard failed for venue {}: {}", venueId, it.message) }
                    runCatching { PromptScriptRepoSync.hydrateStore(state, venueUuid, staging) }
                        .onFailure { logger.warn("Prompt PDF hydrate after discard failed for venue {}: {}", venueId, it.message) }
                } finally {
                    staging.toFile().deleteRecursively()
                }
                logger.info("Discarded the draft of venue {} back to v{}", venueId, installed.number)
            }
        }
        if (state.projectManager.currentProject.id.value == venueId) {
            state.projectManager.switchProject(venueId)
        }
        return history(venueId)
    }

    // ── Internals ───────────────────────────────────────────────────────────────

    private data class Installed(val number: Int, val snapshotId: Int, val snapshotUuid: UUID, val venueUuid: UUID)

    private fun draftDiff(venueId: Int, installed: VenueVersionDto?): VenueDiff.Diff =
        VenueDiff.compare(
            installed?.let { records(venueId, it.number, it.snapshotProjectId) } ?: VenueDiff.Records.EMPTY,
            VenueDiff.recordsOf(state, venueId),
        )

    /** [version]'s records: the draft's for null, nothing for 0, else the snapshot's (cached). */
    private fun records(venueId: Int, version: Int?, snapshotId: Int?): VenueDiff.Records = when {
        version == null -> VenueDiff.recordsOf(state, venueId)
        snapshotId == null -> VenueDiff.Records.EMPTY
        else -> synchronized(snapshotRecords) { snapshotRecords[snapshotId] }
            ?: VenueDiff.recordsOf(state, snapshotId).also { synchronized(snapshotRecords) { snapshotRecords[snapshotId] = it } }
    }

    /** The snapshot project holding [number]; null for the draft (null) and the empty venue (0). */
    private fun snapshotOf(venueId: Int, number: Int?): Int? {
        if (number == null || number == 0) return null
        return DaoVenueVersion.find {
            (DaoVenueVersions.venueProject eq venueId) and (DaoVenueVersions.number eq number)
        }.firstOrNull()?.snapshotProject?.id?.value
            ?: throw VenueException(HttpStatusCode.NotFound, CODE_VERSION_UNKNOWN, "No version $number of this venue")
    }

    companion object {
        const val CODE_NOT_A_VENUE = "NOT_A_VENUE"
        const val CODE_NOTE_REQUIRED = "VENUE_NOTE_REQUIRED"
        const val CODE_NOTE_TOO_LONG = "VENUE_NOTE_TOO_LONG"
        const val CODE_DRAFT_EMPTY = "VENUE_DRAFT_EMPTY"
        const val CODE_NOTHING_PUBLISHED = "VENUE_NOTHING_PUBLISHED"
        const val CODE_VERSION_UNKNOWN = "VENUE_VERSION_UNKNOWN"

        /**
         * A snapshot's project name. `projects.name` is unique, and a snapshot is never shown by its
         * own name — History names it by its venue and number — so it takes one no person would
         * type: the prefix and its own uuid, exactly the column's fifty characters.
         */
        fun snapshotName(snapshotUuid: UUID): String = "$SNAPSHOT_NAME_PREFIX$snapshotUuid"
        const val SNAPSHOT_NAME_PREFIX = "venue-version:"

        /** [venueId]'s versions, newest first. Inside a transaction. */
        fun versionsOf(venueId: Int): List<DaoVenueVersion> =
            DaoVenueVersion.find { DaoVenueVersions.venueProject eq venueId }
                .orderBy(DaoVenueVersions.number to SortOrder.DESC)
                .toList()

        /** The venue [venueId], or a 404 (no project) or 409 (not a venue). Inside a transaction. */
        fun requireVenue(venueId: Int): DaoProject {
            val project = DaoProject.findById(venueId)
                ?: throw VenueException(HttpStatusCode.NotFound, CODE_NOT_A_VENUE, "Venue not found")
            if (project.kind != ProjectKind.VENUE) {
                throw VenueException.conflict(CODE_NOT_A_VENUE, "Project ${project.id.value} is not a venue")
            }
            return project
        }

        private fun DaoVenueVersion.toDto() = VenueVersionDto(
            number = number,
            note = note,
            publishedAt = publishedAt.toIsoUtc(),
            publishedBy = publishedBy,
            snapshotProjectId = snapshotProject.id.value,
        )
    }
}

/** A venue operation's refusal, carrying its HTTP status and machine-readable code. */
class VenueException(val status: HttpStatusCode, val code: String, message: String) : RuntimeException(message) {
    companion object {
        fun conflict(code: String, message: String) = VenueException(HttpStatusCode.Conflict, code, message)
        fun badRequest(code: String, message: String) = VenueException(HttpStatusCode.BadRequest, code, message)
    }
}

/**
 * One published version. [snapshotProjectId] is the frozen project a client reads it through —
 * every read route answers for it (the Patch list draws a removed row from the installed version's
 * patches), and every write route refuses it.
 */
@Serializable
data class VenueVersionDto(
    val number: Int,
    val note: String,
    val publishedAt: String,
    val publishedBy: String? = null,
    val snapshotProjectId: Int,
)

/** The draft against the installed version (or against nothing, before v1). */
@Serializable
data class VenueDraftDto(val changes: Int, val outputChanges: Int)

@Serializable
data class VenueHistoryDto(
    val venueId: Int,
    val name: String,
    /** The installed version — the latest published — or null before v1. */
    val installed: Int? = null,
    val draft: VenueDraftDto,
    /** Newest first. */
    val versions: List<VenueVersionDto>,
)

/** [from] → [to]: version numbers, 0 the empty venue, null the draft. */
@Serializable
data class VenueDiffDto(
    val from: Int?,
    val to: Int?,
    val output: List<VenueChangeDto>,
    val drawing: List<VenueChangeDto>,
)
