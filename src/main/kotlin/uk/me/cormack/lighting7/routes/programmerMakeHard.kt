package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.ProgrammerValue
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.maskGroupForProperty
import uk.me.cormack.lighting7.fx.parseMaskGroups
import uk.me.cormack.lighting7.state.State

private val logger = LoggerFactory.getLogger("programmerMakeHard")

@Resource("/make-hard")
internal class ProgrammerMakeHardResource

@Serializable
internal data class ProgrammerMakeHardRequest(
    /** Restrict to these programmer targets. Omitted or empty = every ref the programmer holds. */
    val targetKeys: List<String>? = null,
    /** I/P/C/B attribute mask. Omitted = every attribute. */
    val mask: List<String>? = null,
)

@Serializable
internal data class ProgrammerMakeHardResponse(
    /** Refs replaced by their current literal. */
    val converted: Int,
    /** Refs left alone because they fell outside [ProgrammerMakeHardRequest]'s scope or mask. */
    val skipped: Int,
)

/**
 * Replace [ProgrammerValue.Ref] slots with [ProgrammerValue.Hard] ones holding the value they
 * currently resolve to — the operator's explicit "stop tracking this palette".
 *
 * Nothing on stage moves: a hardened slot keeps its resolved value, which is the point. That also
 * means there is nothing to republish and no provenance change, yet
 * [uk.me.cormack.lighting7.fx.FxEngine.emitProvenanceUpdate] is still called — it is the documented
 * signal the client re-reads `programmer.state` on, and without it a second tab would keep showing
 * reference badges on entries that are no longer references.
 */
internal suspend fun RoutingContext.handleProgrammerMakeHard(state: State) {
    val request = try {
        call.receive<ProgrammerMakeHardRequest>()
    } catch (_: Exception) {
        ProgrammerMakeHardRequest()
    }

    val mask = try {
        parseMaskGroups(request.mask)
    } catch (e: IllegalArgumentException) {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad mask"))
    }
    val scope = request.targetKeys?.takeIf { it.isNotEmpty() }?.toSet()

    val outcome = hardenProgrammerRefs(state, scope, mask)
    logger.info("make-hard: converted {} ref(s), skipped {}", outcome.converted, outcome.skipped)
    call.respond(ProgrammerMakeHardResponse(outcome.converted, outcome.skipped))
}

internal data class MakeHardOutcome(val converted: Int, val skipped: Int)

/**
 * Harden every ref the programmer holds that passes [scope] and [mask]. Shared with the WS op so
 * both entry points behave identically.
 */
internal fun hardenProgrammerRefs(
    state: State,
    scope: Set<String>?,
    mask: Set<PropertyMaskGroup>?,
): MakeHardOutcome {
    val store = state.show.programmerStore
    var converted = 0
    var skipped = 0

    store.rewriteSlotValues { fixtureKey, propertyName, slot ->
        val ref = slot.value as? ProgrammerValue.Ref ?: return@rewriteSlotValues null
        if (scope != null && fixtureKey !in scope) {
            skipped++
            return@rewriteSlotValues null
        }
        if (mask != null && !maskAllows(mask, maskGroupFor(state, fixtureKey, propertyName))) {
            skipped++
            return@rewriteSlotValues null
        }
        converted++
        ProgrammerValue.Hard(ref.resolved)
    }

    // No stage change and no provenance change, but the client keys its re-read off provenance —
    // see the KDoc above.
    if (converted > 0) state.show.fxEngine.emitProvenanceUpdate()
    return MakeHardOutcome(converted, skipped)
}

/**
 * The mask group for a programmer key, or null when the fixture or property no longer resolves —
 * in which case a masked request leaves the slot alone rather than guessing.
 */
private fun maskGroupFor(state: State, fixtureKey: String, propertyName: String): PropertyMaskGroup? {
    val fixture = runCatching { state.show.fixtures.untypedGroupableFixture(fixtureKey) }.getOrNull()
        ?: return null
    return maskGroupForProperty(fixture, propertyName)
}
