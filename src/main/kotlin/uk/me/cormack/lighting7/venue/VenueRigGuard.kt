package uk.me.cormack.lighting7.venue

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import uk.me.cormack.lighting7.models.RigOwner
import uk.me.cormack.lighting7.models.RigScope

/**
 * The rules a show on a venue writes its rig by (venue plan §3.3, D4, D5, D12), shared by the REST
 * routes and the MCP setup tools so the two refuse the same writes with the same codes.
 *
 * - **A venue's row is the venue's.** A write through a show that names one by id — a `PUT`, a
 *   `DELETE`, a placement, a membership — is refused with 409 [CODE_VENUE_OWNED], not a 404: the
 *   row is in the show's rig, it is just not the show's to change. A show makes two changes to a
 *   venue row: it hides one (`PUT …/hidden`), and it overrides a venue patch's facets — address,
 *   position, focus, media — through their own route (`PUT …/facets/{facet}`, `ShowOverrides`).
 * - **Keys are one namespace.** A show's own record may not take the key — or, for every kind the rig
 *   checks a clash for, the name or head number — of a venue record in its rig: 409
 *   [CODE_KEY_TAKEN_BY_VENUE]. A hidden venue record is not in the rig, so a show may reuse its key
 *   (to replace a house unit, hide it and add your own); unhiding it then is refused with
 *   [CODE_KEY_TAKEN_BY_SHOW] while the show's own record holds the key, or (a patch) sits on its channels.
 *
 * Every refusal is a [VenueException], which `plugins/ErrorHandling.kt` answers with its status and
 * code wherever it is thrown — so a throw inside a write's transaction rolls the write back whole.
 */
object VenueRigGuard {
    const val CODE_VENUE_OWNED = "VENUE_OWNED"
    const val CODE_KEY_TAKEN_BY_VENUE = "KEY_TAKEN_BY_VENUE"
    const val CODE_KEY_TAKEN_BY_SHOW = "KEY_TAKEN_BY_SHOW"
    const val CODE_NOT_A_VENUE_RECORD = "NOT_A_VENUE_RECORD"

    /** The refusal for a write through a show to [what], a row of its venue. */
    fun venueOwned(scope: RigScope, what: String): VenueException {
        val venue = scope.venue
        val whose = if (venue == null) "the venue's" else "the venue's (${venue.venueName} v${venue.version})"
        return VenueException.conflict(
            CODE_VENUE_OWNED,
            "$what is $whose, so this show cannot change it. A show can hide a venue record " +
                "(PUT …/hidden), and make a venue fixture's address, position, focus or media its own with a " +
                "facet override (PUT …/patches/{id}/facets/{ADDRESS|POSITION|FOCUS|MEDIA}); its name, key, type " +
                "and head are the venue's. To change it for every show, edit the venue and publish.",
        )
    }

    /**
     * [row] when this project owns it; a [VenueException] ([CODE_VENUE_OWNED]) when it is the pinned
     * venue's; null when it is not in this rig at all, which the caller answers as not found.
     * Inside a transaction.
     */
    fun <T : Any> ownForWrite(scope: RigScope, row: T?, rowProject: (T) -> EntityID<Int>, what: (T) -> String): T? {
        if (row == null) return null
        return when (scope.ownerOf(rowProject(row))) {
            RigOwner.SHOW -> row
            RigOwner.VENUE -> throw venueOwned(scope, what(row))
            null -> null
        }
    }

    /**
     * The refusal for a show's own record taking an identity held by [holderProject]'s row, or null
     * when the holder is the show's own (the caller's existing clash message stands).
     */
    fun takenByVenue(scope: RigScope, holderProject: EntityID<Int>, what: String): VenueException? {
        if (scope.ownerOf(holderProject) != RigOwner.VENUE) return null
        return VenueException.conflict(
            CODE_KEY_TAKEN_BY_VENUE,
            "$what is taken by the venue: keys and names are one namespace across a show and its venue. " +
                "To replace a venue record, hide it in this show and add your own.",
        )
    }

    /**
     * [venueOwned] as one problem line for a surface that lists every problem at once (MCP's setup
     * tools), led by its code so a model can key on it.
     */
    fun ownedProblem(scope: RigScope, what: String): String =
        "$CODE_VENUE_OWNED: ${venueOwned(scope, what).message}"

    /** The code a list of problems carries when any is a venue rule's, for the tool's `code` field. */
    fun codeOf(problems: List<String>): String? =
        listOf(CODE_VENUE_OWNED, CODE_KEY_TAKEN_BY_VENUE).firstOrNull { code -> problems.any { it.startsWith("$code:") } }

    /** Throw [takenByVenue]'s refusal when there is one. */
    fun refuseIfVenue(scope: RigScope, holderProject: EntityID<Int>, what: String) {
        takenByVenue(scope, holderProject, what)?.let { throw it }
    }
}
