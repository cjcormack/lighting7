package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.auth.authenticatedUserOrNull
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.venue.VenueException
import uk.me.cormack.lighting7.sync.SyncException

/**
 * A venue's versions (venue plan §3.5, session 2), all **admin only** — mounted inside `adminOnly`
 * in `routes/router.kt`, because a venue is an admin's to edit and publish (D3), and its history and
 * draft are part of editing it. An operator sees the venue itself, read-only, through the ordinary
 * project routes.
 *
 * - `GET /venues/{id}/versions` — History: every version, newest first, and the draft's change count.
 * - `GET /venues/{id}/versions/{a}/diff/{b}` — what changed from `a` to `b`, classified output /
 *   drawing (`venue/VenueDiff.kt`). Each is a version number, `0` for the empty venue before v1, or
 *   `draft`.
 * - `POST /venues/{id}/publish` `{note}` — publish the draft as the next version (P2). With a repo
 *   attached it publishes through it (P7, `sync/VenueRepoSync.kt`): 409 `VENUE_VERSION_TAKEN` when the
 *   remote already has the number, `VENUE_PUBLISH_PULL_ONLY` on a desk with `sync.push = false`.
 * - `DELETE /venues/{id}/draft` — discard the draft back to the installed version.
 *
 * REST only: publishing is not a socket operation, so `FU-AUTH-WS-PER-MESSAGE` does not fire.
 * Publish and discard announce themselves with the existing `projectDetailsChanged` frame, which
 * every window's History, draft count and Patch list markers refetch on.
 */
internal fun Route.routeApiRestVenues(state: State) {
    get<VenueVersionsResource> { resource ->
        venueCall { withContext(Dispatchers.IO) { state.venueVersions.history(resource.id) } }
    }

    get<VenueDiffResource> { resource ->
        val from = versionRef(resource.from)
        val to = versionRef(resource.to)
        if (from == INVALID || to == INVALID) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("A version is a number, 0 for the empty venue, or 'draft'", "VENUE_VERSION_INVALID"),
            )
            return@get
        }
        venueCall { withContext(Dispatchers.IO) { state.venueVersions.diff(resource.parent.id, from, to) } }
    }

    post<VenuePublishResource> { resource ->
        val request = call.receive<PublishVenueRequest>()
        val by = call.authenticatedUserOrNull?.displayName
        venueCall(HttpStatusCode.Created) {
            state.venueVersions.publish(resource.id, request.note, by)
                .also {
                    state.show.fixtures.projectDetailsChanged(resource.id)
                    // A show on this venue that is live is now behind it: judge its hold again (P5).
                    withContext(Dispatchers.IO) { state.venueHold.refreshCurrent() }
                }
        }
    }

    delete<VenueDraftResource> { resource ->
        venueCall {
            // All of it off the call dispatcher: the reload of a live venue and the History it answers
            // with are blocking reads, as the export before them is.
            withContext(Dispatchers.IO) { state.venueVersions.discard(resource.id) }
                .also { state.show.fixtures.projectDetailsChanged(resource.id) }
        }
    }
}

/** Run [block] and answer its result, or a [VenueException]'s status and code. */
private suspend inline fun <reified T : Any> RoutingContext.venueCall(
    status: HttpStatusCode = HttpStatusCode.OK,
    block: () -> T,
) {
    val result = try {
        block()
    } catch (e: VenueException) {
        call.respond(e.status, ErrorResponse(e.message ?: "Refused", e.code))
        return
    } catch (e: SyncException) {
        // A publish through the venue's repo (venue plan P7): `VENUE_VERSION_TAKEN`, a pull-only
        // desk's `VENUE_PUBLISH_PULL_ONLY`, or the repo's own failure (credentials, network).
        respondSyncError(e)
        return
    }
    call.respond(status, result)
}

private const val INVALID = -1

/** `draft` → null, a version number (0 the empty venue) → itself, anything else → [INVALID]. */
private fun versionRef(raw: String): Int? = when {
    raw.equals("draft", ignoreCase = true) -> null
    else -> raw.toIntOrNull()?.takeIf { it >= 0 } ?: INVALID
}

@Serializable
data class PublishVenueRequest(val note: String = "")

@Resource("/venues/{id}/versions")
class VenueVersionsResource(val id: Int)

@Resource("/{from}/diff/{to}")
class VenueDiffResource(val from: String, val to: String, val parent: VenueVersionsResource)

@Resource("/venues/{id}/publish")
class VenuePublishResource(val id: Int)

@Resource("/venues/{id}/draft")
class VenueDraftResource(val id: Int)
