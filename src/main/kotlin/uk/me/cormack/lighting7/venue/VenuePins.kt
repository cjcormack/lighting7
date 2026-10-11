package uk.me.cormack.lighting7.venue

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.models.DaoVenueVersion
import uk.me.cormack.lighting7.models.DaoVenueVersions
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.isVenueBound
import java.util.UUID

/**
 * A show's pin as this desk can honour it (venue plan §3.4): which snapshot a `(venueUuid, version)`
 * pair names here, why it names none, and where the venue syncs. Every helper runs inside the
 * caller's transaction.
 *
 * A pin that names no snapshot on the desk is **missing**. The importer refuses such a show (409
 * [CODE_VENUE_MISSING]), the sync engine refuses to pull one before it moves the working tree, the
 * project switch refuses to make one current, and the rig loader loads nothing for one that is
 * current already (a desk can only get there by hand, since a venue a show pins refuses delete) —
 * refusing to output rather than outputting the show's additions without the rig they sit on.
 */
object VenuePins {

    const val CODE_VENUE_MISSING = "VENUE_MISSING"

    /** The venue project [venueUuid] names on this desk, or null. */
    fun venueOf(venueUuid: UUID): DaoProject? =
        DaoProject.find { (DaoProjects.uuid eq venueUuid) and (DaoProjects.kind eq ProjectKind.VENUE) }.firstOrNull()

    /** The snapshot project holding version [number] of venue [venueUuid], or null when this desk lacks it. */
    fun snapshotOf(venueUuid: UUID, number: Int): DaoProject? {
        val venue = venueOf(venueUuid) ?: return null
        return DaoVenueVersion.find {
            (DaoVenueVersions.venueProject eq venue.id) and (DaoVenueVersions.number eq number)
        }.firstOrNull()?.snapshotProject
    }

    /** Whether [project] is a show on a venue whose pinned version this desk does not hold. */
    fun isMissing(project: DaoProject): Boolean {
        if (!project.isVenueBound) return false
        val number = project.venueVersion ?: return true
        return snapshotOf(project.venueUuid!!, number) == null
    }

    /**
     * The refusal for a show pinned to [venueUuid] v[number] that this desk lacks: it names the venue
     * (by name when the desk has an older version of it, else by uuid), the versions it has, and
     * where to fetch it — [hint], else the venue's own attached repo.
     */
    fun missing(venueUuid: UUID, number: Int, hint: String?, showName: String): VenueException {
        val venue = venueOf(venueUuid)
        val where = (hint ?: venue?.let { repoHintOf(it.id.value) })?.let { " (its repo is $it)" } ?: ""
        val message = if (venue == null) {
            "$showName is on a venue this desk does not have — venue $venueUuid, v$number. " +
                "Import the venue's repo$where first, then the show."
        } else {
            val have = VenueVersions.versionsOf(venue.id.value).map { it.number }.sorted()
            val held = if (have.isEmpty()) "none of its versions" else "v${have.joinToString(", v")}"
            "$showName is on ${venue.name} v$number, which this desk does not have (it has $held). " +
                "Sync the venue's repo$where to fetch v$number, then try again."
        }
        return VenueException.conflict(CODE_VENUE_MISSING, message)
    }

    /** [missing] for a project already on the desk. */
    fun missing(project: DaoProject): VenueException =
        missing(project.venueUuid!!, project.venueVersion ?: 0, project.venueRepo, project.name)

    /**
     * Where venue [venueId] syncs, as a show's `venueRepo` hint: `owner/repo` for a GitHub remote,
     * else the remote's URL, or null when no repo is attached here.
     */
    fun repoHintOf(venueId: Int): String? =
        DaoSyncConfig.find { DaoSyncConfigs.project eq venueId }.firstOrNull()?.repoUrl
            ?.takeIf { it.isNotBlank() }
            ?.let(::repoHint)

    /** `https://github.com/owner/repo(.git)` → `owner/repo`; anything else as it is. */
    fun repoHint(repoUrl: String): String {
        val match = GITHUB_URL.matchEntire(repoUrl.trim()) ?: return repoUrl.trim()
        return "${match.groupValues[1]}/${match.groupValues[2]}"
    }

    private val GITHUB_URL = Regex("""https://github\.com/([^/]+)/([^/]+?)(?:\.git)?/?""")
}
