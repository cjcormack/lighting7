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
import uk.me.cormack.lighting7.telemetry.Tracing
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoProjects
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
import uk.me.cormack.lighting7.sync.VenueRepoSync
import uk.me.cormack.lighting7.sync.canonicalDecode
import uk.me.cormack.lighting7.sync.canonicalEncode
import uk.me.cormack.lighting7.sync.dto.ProjectJson
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
        val (name, versions, shows) = transaction(state.database) {
            val venue = requireVenue(venueId)
            Triple(venue.name, versionsOf(venueId).map { it.toDto() }, showsOf(venue))
        }
        val draft = draftDiff(venueId, versions.firstOrNull())
        return VenueHistoryDto(
            venueId = venueId,
            name = name,
            installed = versions.firstOrNull()?.number,
            draft = VenueDraftDto(changes = draft.size, outputChanges = draft.output.size),
            versions = versions,
            shows = shows,
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
    suspend fun publish(venueId: Int, note: String, publishedBy: String?): VenueVersionDto =
        Tracing.transactionSuspending("venue.publish", "venue.publish") { publishTraced(venueId, note, publishedBy) }

    private suspend fun publishTraced(venueId: Int, note: String, publishedBy: String?): VenueVersionDto = lock.withLock {
        val trimmed = note.trim()
        if (trimmed.isEmpty()) throw VenueException.badRequest(CODE_NOTE_REQUIRED, "A version needs a note saying what changed")
        if (trimmed.length > VENUE_VERSION_NOTE_MAX) {
            throw VenueException.badRequest(CODE_NOTE_TOO_LONG, "A note may be at most $VENUE_VERSION_NOTE_MAX characters")
        }
        val local = withContext(Dispatchers.IO) {
            val installed = transaction(state.database) {
                requireVenue(venueId)
                versionsOf(venueId).firstOrNull()?.toDto()
            }
            if (Tracing.span("venue.diff", "draft") { draftDiff(venueId, installed) }.size == 0) {
                throw VenueException.conflict(
                    CODE_DRAFT_EMPTY,
                    if (installed == null) "The venue is empty: there is nothing to publish"
                    else "Nothing to publish: the draft is v${installed.number}",
                )
            }
            // A venue with a repo publishes through it (venue plan P7): pull first, refuse a number the
            // remote already has, push the tagged commit, and only then make the version here.
            if (VenueRepoSync.attachedRepo(state, venueId) != null) return@withContext null
            val number = (installed?.number ?: 0) + 1
            val snapshotUuid = UUID.randomUUID()
            var published: VenueVersionDto? = null
            Tracing.span("venue.snapshot") { KeptUuidCopy.copy(
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
            } }
            logger.info("Published venue {} v{} ({})", venueId, number, snapshotUuid)
            published ?: error("Version row was not written")
        }
        local ?: state.remoteSyncEngine.publishVenue(venueId, trimmed, publishedBy)
    }

    /**
     * Run [block] holding the lock publish and discard hold — what a venue's sync run takes before its
     * sync lock (`RemoteSyncEngine.runSync`), so the three never interleave. Not reentrant.
     */
    suspend fun <T> locked(block: suspend () -> T): T = lock.withLock { block() }

    /** How many changes the draft of [venueId] holds against its installed version. Blocking. */
    fun draftChanges(venueId: Int): Int {
        val installed = transaction(state.database) { versionsOf(venueId).firstOrNull()?.toDto() }
        return draftDiff(venueId, installed).size
    }

    /**
     * Throw the draft away: the venue's rows become the installed version's again, uuids and all,
     * so every machine-local override keyed by a record uuid (a universe's IP) still applies. The
     * venue's name and description are its own, not versioned, and stay. A live venue reloads.
     */
    suspend fun discard(venueId: Int): VenueHistoryDto {
        lock.withLock {
            withContext(Dispatchers.IO) {
                transaction(state.database) {
                    requireVenue(venueId)
                    if (versionsOf(venueId).none()) {
                        throw VenueException.conflict(CODE_NOTHING_PUBLISHED, "Nothing has been published to go back to")
                    }
                }
                replaceDraftWithInstalled(venueId)
            }
        }
        if (state.projectManager.currentProject.id.value == venueId) {
            state.projectManager.switchProject(venueId, reload = true)
        }
        return history(venueId)
    }

    /**
     * Make the draft of [venueId] its installed version again — [discard]'s work, and what a sync
     * pull does to a clean draft when a newer version arrives (`sync/VenueRepoSync.kt`). Blocking; the
     * caller holds [lock] and reloads a live venue. False when nothing is published.
     *
     * [ifDraftIs], when given, is the draft as the caller last read it: the replace goes ahead only if
     * the draft still holds exactly those records, checked inside the replace's own transaction (one
     * pooled connection, so no write lands between the check and the replace). Draft edits take no
     * venue lock, so this is what keeps a sync pull from overwriting an edit made while it fetched.
     */
    fun replaceDraftWithInstalled(venueId: Int, ifDraftIs: VenueDiff.Records? = null): Boolean {
        val installed = transaction(state.database) {
            val venue = requireVenue(venueId)
            val version = versionsOf(venueId).firstOrNull() ?: return@transaction null
            Installed(version.number, version.snapshotProject.id.value, version.snapshotProject.uuid, venue.uuid)
        } ?: return false
        val venueUuid = installed.venueUuid
        val staging = Files.createTempDirectory("venue-discard-")
        try {
            ProjectExporter(state).export(installed.snapshotId, staging)
            ExportUuidRemapper.applyMapping(staging, mapOf(installed.snapshotUuid to venueUuid))
            // The archive is the snapshot's, which says it is a version; the rows go back into the venue.
            val projectPath = staging.resolve("project.json")
            val project = canonicalDecode(ProjectJson.serializer(), Files.readString(projectPath))
            Files.writeString(projectPath, canonicalEncode(ProjectJson.serializer(), project.copy(kind = ProjectKind.VENUE)))
            val replaced = transaction(state.database) {
                if (ifDraftIs != null && VenueDiff.recordsOf(state, venueId).byKey != ifDraftIs.byKey) {
                    return@transaction false
                }
                val venue = DaoProject[venueId]
                val name = venue.name
                val description = venue.description
                ProjectImporter(state).replaceFromWorkingTree(venueId, staging)
                venue.name = name
                venue.description = description
                true
            }
            if (!replaced) {
                logger.info("Venue {}'s draft changed since it was read; leaving it as it is", venueId)
                return false
            }
            // The store may have pruned an image the draft stopped naming; the version still names it.
            runCatching { SceneImageRepoSync.hydrateStore(state, venueUuid, staging) }
                .onFailure { logger.warn("Scene image hydrate after discard failed for venue {}: {}", venueId, it.message) }
            runCatching { PromptScriptRepoSync.hydrateStore(state, venueUuid, staging) }
                .onFailure { logger.warn("Prompt PDF hydrate after discard failed for venue {}: {}", venueId, it.message) }
        } finally {
            staging.toFile().deleteRecursively()
        }
        logger.info("Replaced the draft of venue {} with v{}", venueId, installed.number)
        return true
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

        /** The shows pinned to [venue], by name, each with its version. Inside a transaction. */
        fun showsOf(venue: DaoProject): List<VenueShowDto> =
            DaoProject.find { (DaoProjects.venueUuid eq venue.uuid) and (DaoProjects.kind eq ProjectKind.SHOW) }
                .map { VenueShowDto(id = it.id.value, name = it.name, version = it.venueVersion ?: 0) }
                .sortedBy { it.name }

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
    /**
     * The shows on this venue (venue plan session 3), by name, each with the version it is pinned
     * to: what History and the Publish sheet name as the shows a publish reaches.
     */
    val shows: List<VenueShowDto> = emptyList(),
)

/** A show on a venue, and the version it is pinned to. */
@Serializable
data class VenueShowDto(val id: Int, val name: String, val version: Int)

/** [from] → [to]: version numbers, 0 the empty venue, null the draft. */
@Serializable
data class VenueDiffDto(
    val from: Int?,
    val to: Int?,
    val output: List<VenueChangeDto>,
    val drawing: List<VenueChangeDto>,
)
