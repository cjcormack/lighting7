package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.auth.authenticatedUserOrNull
import uk.me.cormack.lighting7.auth.routingNormalisedPath
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.state.State

/** A write to a venue version, which is frozen (venue plan P2). */
internal const val CODE_VENUE_VERSION_READ_ONLY = "VENUE_VERSION_READ_ONLY"

/** A stored write to a venue by an operator: venues are admins' to edit (venue plan D3). */
internal const val CODE_VENUE_ADMIN_ONLY = "VENUE_ADMIN_ONLY"

/**
 * The write guard over the whole `/projects/{id}/…` subtree (venue plan P2, D3). Every request that
 * is not a read — anything but `GET`, `HEAD` and `OPTIONS` — has its project resolved from the path
 * (an id or `current`) and is refused when:
 *
 * - the project is a **venue version**: 409 [CODE_VENUE_VERSION_READ_ONLY], for every caller and
 *   every write, `set-current`, clone and delete included. A version is what was published.
 * - the project is a **venue** and the caller is an operator: 403 [CODE_VENUE_ADMIN_ONLY] for a
 *   stored write. Opening the venue (`set-current`) and the runtime gestures — the programmer's
 *   spread, aim and focus and its bare effect press, arming and firing, fixture commands (one or a bulk run), *Make rig safe*
 *   and its *Undo*, the grand master and a stack's effect size — are not stored
 *   and pass, so an operator can look at a venue and the programmer still works there (D11).
 *
 * It sits at the head of the subtree rather than inside `withProject`, which the plan names: every
 * one of `withProject`'s routes passes through it, and so do the handful that resolve their project
 * another way (a project's `PUT` and `DELETE`, `set-current`, clone, export, the script copy). A
 * path that names no project, or one that does not exist, passes to the route, which answers it.
 */
// Route.intercept is deprecated in favour of route-scoped plugins, but a plugin's `onCall` cannot
// halt the pipeline before the matched handler runs — the same trade-off as `adminOnly`.
@Suppress("DEPRECATION")
internal fun Route.installProjectWriteGuard(state: State) {
    intercept(ApplicationCallPipeline.Plugins) {
        val method = call.request.httpMethod
        if (method == HttpMethod.Get || method == HttpMethod.Head || method == HttpMethod.Options) return@intercept
        val segments = routingNormalisedPath(call.request.path()).split('/').filter { it.isNotEmpty() }
        val at = segments.indexOf("projects")
        val idSegment = segments.getOrNull(at + 1) ?: return@intercept
        val kind = transaction(state.database) { state.resolveProject(idSegment)?.kind } ?: return@intercept
        val refusal = projectWriteRefusal(
            kind = kind,
            isAdmin = call.authenticatedUserOrNull?.let { it.role == UserRole.ADMIN } ?: true,
            rest = segments.drop(at + 2),
        ) ?: return@intercept
        call.respond(refusal.first, refusal.second)
        finish()
    }
}

/**
 * Why a write to a project of [kind] at the path [rest] (the segments after its id) is refused, or
 * null. [isAdmin] is true on a desk with no accounts, where the API is open (`requireAdmin`'s rule).
 */
internal fun projectWriteRefusal(kind: ProjectKind, isAdmin: Boolean, rest: List<String>): Pair<HttpStatusCode, ErrorResponse>? =
    when (kind) {
        ProjectKind.SHOW -> null
        ProjectKind.VENUE_VERSION -> HttpStatusCode.Conflict to ErrorResponse(
            "A venue version is published and cannot change; edit the venue's draft instead",
            CODE_VENUE_VERSION_READ_ONLY,
        )
        ProjectKind.VENUE -> if (isAdmin || isRuntimeGesture(rest)) null else HttpStatusCode.Forbidden to ErrorResponse(
            "Only an admin can change a venue",
            CODE_VENUE_ADMIN_ONLY,
        )
    }

/**
 * A write under a project that stores nothing: opening it, and the live gestures that act on the
 * rig as it stands. An operator may make these on a venue.
 */
private fun isRuntimeGesture(rest: List<String>): Boolean = when (rest.firstOrNull()) {
    "set-current", "programmer", "effects", "grand-master", "commands", "rig-safe" -> true
    "patches" -> rest.size >= 3 && rest[2] in setOf("fire", "reload", "commands")
    "cue-stacks" -> rest.getOrNull(2) == "effect-size"
    // *Run against* (venue plan session 6) lifts a show's hold for the session and stores nothing; on a
    // venue it is answered by the route (nothing to run against), not refused as a stored write.
    "venue" -> rest.getOrNull(1) == "run-against"
    else -> false
}

/** A record into a venue: the Programmer works there, but Record is off (venue plan D11). */
internal const val CODE_VENUE_RECORD_OFF = "VENUE_RECORD_OFF"

/**
 * Refuse a record while the live project is a venue, with 409 [CODE_VENUE_RECORD_OFF] (venue plan
 * D11): a venue holds a rig, not cues, Looks or templates, and its programmer is for testing and
 * focus. Every record route records into the live project (`withCurrentProject`), so the live one
 * is the one to ask. Returns true when it answered.
 */
internal suspend fun RoutingContext.refusedRecordInVenue(state: State): Boolean {
    // A kind is fixed at creation, so the entity loaded at the last switch answers it.
    val kind = transaction(state.database) { state.projectManager.currentProject.kind }
    if (kind != ProjectKind.VENUE) return false
    call.respond(
        HttpStatusCode.Conflict,
        ErrorResponse("Record is off in a venue: the programmer here is for testing and focus", CODE_VENUE_RECORD_OFF),
    )
    return true
}

/**
 * Wrap the per-project cloud-sync subtree so a **venue version** is refused with 409
 * [CODE_VENUE_VERSION_READ_ONLY] on every route in it, reads included: a version travels as a tag of
 * its venue's repo (venue plan §3.4) and has no repo of its own, and `GET …/sync/config` would create
 * the config row it answers with. A venue and every show sync — a venue its published versions
 * (`sync/VenueRepoSync.kt`), a show on one its own rows and its pin.
 */
@Suppress("DEPRECATION")
internal fun Route.refuseVersionSync(state: State, build: Route.() -> Unit): Route = transparentChild("versionSync") {
    intercept(ApplicationCallPipeline.Plugins) {
        val segments = routingNormalisedPath(call.request.path()).split('/').filter { it.isNotEmpty() }
        val idSegment = segments.getOrNull(segments.indexOf("projects") + 1) ?: return@intercept
        val kind = transaction(state.database) { state.resolveProject(idSegment)?.kind } ?: return@intercept
        if (kind != ProjectKind.VENUE_VERSION) return@intercept
        call.respond(
            HttpStatusCode.Conflict,
            ErrorResponse("A venue version syncs as a tag of its venue's repo, never on its own", CODE_VENUE_VERSION_READ_ONLY),
        )
        finish()
    }
    build()
}

/**
 * The write guard for the one write it cannot see: a copy into **another** project, named in the
 * body (`POST /projects/{source}/{looks|templates|scripts|cues}/{id}/copy {targetProjectId}`). The
 * guard judged the path's project, the source; the target gets the same rule here — a venue version
 * takes no copy, and an operator copies nothing into a venue. Returns true when it answered. A target
 * that does not exist passes, for the route's own 404.
 */
internal suspend fun RoutingContext.refusedCopyTarget(state: State, targetProjectId: Int): Boolean {
    val kind = transaction(state.database) { DaoProject.findById(targetProjectId)?.kind } ?: return false
    val refusal = projectWriteRefusal(
        kind = kind,
        isAdmin = call.authenticatedUserOrNull?.let { it.role == UserRole.ADMIN } ?: true,
        rest = listOf("copy"),
    ) ?: return false
    call.respond(refusal.first, refusal.second)
    return true
}

/**
 * The write guard for the stored writes that name no project in their path and land in the live one
 * — the FX definitions (`/fx/definitions`): an operator's is refused while a venue is live (venue plan
 * D3). A version is never live, so it needs no rule here. Returns true when it answered.
 */
internal suspend fun RoutingContext.refusedLiveVenueWrite(state: State): Boolean {
    // A kind is fixed at creation, so the entity loaded at the last switch answers it.
    val kind = transaction(state.database) { state.projectManager.currentProject.kind }
    val refusal = projectWriteRefusal(
        kind = kind,
        isAdmin = call.authenticatedUserOrNull?.let { it.role == UserRole.ADMIN } ?: true,
        rest = emptyList(),
    ) ?: return false
    call.respond(refusal.first, refusal.second)
    return true
}
