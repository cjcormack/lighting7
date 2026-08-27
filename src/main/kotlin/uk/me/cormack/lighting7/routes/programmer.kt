package uk.me.cormack.lighting7.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import uk.me.cormack.lighting7.state.State

/**
 * The programmer's REST surface.
 *
 * - `record` / `include` / `update` are the authoring loop.
 * - `record-look` records the programmer into a Look — the gesture that creates a *bound* Look.
 *   Masked by an explicit `mask`, because a Look has no type to imply one, and optionally moving
 *   running programmer-band effects in by id.
 *
 * A `make-hard` route stood here too, replacing the programmer's palette references with the
 * literals they resolved to. It went with the `ref:` grammar itself: there are no value-level
 * references left to harden, and "promote a layer's values into local rows" is
 * `POST /cues/{id}/flatten`.
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
        post<ProgrammerRecordResource> { handleProgrammerRecord(state) }
        post<ProgrammerRecordLookResource> { handleProgrammerRecordLook(state) }
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
 *   bookkeeping would desync the toggles — and, for the Look-layer stack, would let the next
 *   recook put the whole look back on stage after the operator cleared it;
 * - band FX before the store sweep: removing them restores the cascade under their targets
 *   while the programmer still holds its values, so the single [ProgrammerWriter.clearAll]
 *   republish that follows lands every affected key once instead of twice.
 *
 * Clear is specified as "programmer values **and** programmer FX" — busking effects created
 * with `programmerOwned` therefore go with the values they were modulating.
 */
internal fun clearProgrammerCompletely(state: State, fadeMs: Long = 0): ProgrammerClearOutcome {
    state.show.locateManager.reset()
    state.show.programmerLayerStack.reset()
    val effectsCleared = state.show.fxEngine.removeProgrammerBandEffects()
    val entryCount = state.show.fxEngine.programmer.clearAll(fadeMs)
    return ProgrammerClearOutcome(entryCount, effectsCleared)
}
