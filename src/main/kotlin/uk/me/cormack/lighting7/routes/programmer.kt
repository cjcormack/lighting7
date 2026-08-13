package uk.me.cormack.lighting7.routes

import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.state.State

/**
 * The programmer's REST surface.
 *
 * - `clear-all` is the escape hatch: release every programmer entry (all owners, property
 *   entries and the channel sideband) back to the layers below. The WS `programmer.clearAll`
 *   op is the same operation.
 * - `record` / `include` / `update` are the authoring loop.
 * - `make-hard` replaces the programmer's palette references with the literals they currently
 *   resolve to — the explicit escape hatch from reference-preserving Update.
 *
 * Why these three are REST rather than `programmer.*` WS ops, unlike every other programmer
 * operation: they all need a *structured reply* — the created cue, the fixture keys to select,
 * the Mode B checklist — and the programmer WS channel is fire-and-forget with no
 * request/response correlation. They also mutate cues, which is REST's job everywhere else in
 * this codebase, and going through RTK Query gets the client cue-tag invalidation and error
 * surfacing for free. The WS channel stays what it is: live stage state.
 */
internal fun Route.routeApiRestProgrammer(state: State) {
    route("/programmer") {
        post<ProgrammerClearAllResource> {
            val request = try {
                call.receive<ProgrammerClearAllRequest>()
            } catch (_: Exception) {
                ProgrammerClearAllRequest()
            }
            val cleared = clearProgrammerCompletely(state, request.fadeMs ?: 0)
            call.respond(ProgrammerClearAllResponse(cleared.entryCount, cleared.effectsCleared))
        }

        post<ProgrammerMakeHardResource> { handleProgrammerMakeHard(state) }
        post<ProgrammerRecordResource> { handleProgrammerRecord(state) }
        post<ProgrammerIncludeResource> { handleProgrammerInclude(state) }
        post<ProgrammerUpdateResource> { handleProgrammerUpdate(state) }
    }
}

/** What a full programmer clear swept: stored entries, and programmer-band FX instances. */
internal data class ProgrammerClearOutcome(val entryCount: Int, val effectsCleared: Int)

/**
 * The full programmer clear: reset the toggle bookkeeping that tracks programmer entries
 * (locate targets, preset toggles and previews) *first*, then remove the programmer-band FX,
 * then sweep and republish the store in one pass. Order matters twice over —
 *
 * - bookkeeping first: the store sweep releases every owner's entries, so running the
 *   subsystems' own release paths afterwards would double-release, and leaving their
 *   bookkeeping would desync the toggles;
 * - band FX before the store sweep: removing them restores the cascade under their targets
 *   while the programmer still holds its values, so the single [FxEngine.clearProgrammerAll]
 *   republish that follows lands every affected key once instead of twice.
 *
 * Clear is specified as "programmer values **and** programmer FX" — busking effects created
 * with `programmerOwned` therefore go with the values they were modulating.
 */
internal fun clearProgrammerCompletely(state: State, fadeMs: Long = 0): ProgrammerClearOutcome {
    state.show.locateManager.reset()
    resetPresetProgrammerBookkeeping()
    val effectsCleared = state.show.fxEngine.removeProgrammerBandEffects()
    val entryCount = state.show.fxEngine.clearProgrammerAll(fadeMs)
    return ProgrammerClearOutcome(entryCount, effectsCleared)
}

@Resource("/clear-all")
private class ProgrammerClearAllResource

@Serializable
internal data class ProgrammerClearAllRequest(val fadeMs: Long? = null)

@Serializable
internal data class ProgrammerClearAllResponse(val cleared: Int, val effectsCleared: Int = 0)
