package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.maskGroupForProperty
import uk.me.cormack.lighting7.fx.parseMaskGroups
import uk.me.cormack.lighting7.fx.parsePaletteRef
import uk.me.cormack.lighting7.fx.resolveAssignmentValueForFixture
import uk.me.cormack.lighting7.models.DaoCuePresetApplication
import uk.me.cormack.lighting7.models.DaoCuePresetApplications
import uk.me.cormack.lighting7.models.DaoFxPreset
import uk.me.cormack.lighting7.state.State
import java.util.UUID

private val logger = LoggerFactory.getLogger("presetMakeHard")

@Resource("/{presetId}/make-hard")
internal data class MakeFxPresetHardResource(
    val parent: ProjectFxPresetsResource,
    val presetId: Int,
)

@Serializable
internal data class PresetMakeHardRequest(
    /** Restrict to rows referencing these palette uuids. Omitted = every ref in the preset. */
    val paletteUuids: List<String>? = null,
    val mask: List<String>? = null,
)

@Serializable
internal data class PresetMakeHardResponse(
    val preset: FxPresetDetails,
    /** Rows rewritten from a reference to a literal. */
    val converted: Int,
    /**
     * Rows left as references because the palette gives this property more than one literal
     * across the fixtures it covers. A preset row is target-less, so there is no single literal
     * that can say what the reference said — reported rather than guessed.
     */
    val ambiguous: List<PresetRefAmbiguity>,
    /**
     * Rows left as references because nothing resolves: a deleted palette, a palette covering
     * no fixture of the preset's declared type, or an element-scoped ref (which can never
     * resolve — palettes are fixture-shaped).
     */
    val unresolved: Int,
    /** Live cues applying this preset that were rebuilt and republished. */
    val cuesRepublished: List<Int>,
)

@Serializable
internal data class PresetRefAmbiguity(
    val propertyName: String,
    val paletteUuid: String,
    val paletteName: String? = null,
    /** The distinct literals this palette holds for the property, most-covered first. */
    val variants: List<PresetRefVariant>,
)

@Serializable
internal data class PresetRefVariant(val literal: String, val fixtureKeys: List<String>)

/**
 * Replace an FX preset's palette references with the literals they resolve to — the preset-level
 * counterpart of [handleMakeCueHard] and `/programmer/make-hard`.
 *
 * A preset property assignment is **target-less**: a cue row names the fixture or group it
 * applies to, a preset row says "whatever this preset is applied to", which at hardening time is
 * a set the preset doesn't know. Rather than invent one — the union of today's
 * [uk.me.cormack.lighting7.models.DaoCuePresetApplication] targets is wrong the moment the preset
 * is applied somewhere new — this asks the *palette* whether the answer is target-independent:
 *
 * - Candidate fixtures are those whose [Fixture.typeKey] matches the preset's declared
 *   `fixtureType`, the same compatibility rule [compatibleIdsFor] applies.
 * - Every candidate resolves through [resolveAssignmentValueForFixture], grouped by the
 *   serialized literal, so the value stored is normalised exactly as the cue path stores it.
 * - **One** distinct literal hardens the row. **More than one** leaves it alone and reports the
 *   disagreement, because no single literal can stand in for the reference. **None** counts as
 *   unresolved.
 *
 * Ambiguity is a 200 with a populated [PresetMakeHardResponse.ambiguous], not an error: some rows
 * can harden while others can't, and the operator needs both halves of that report.
 */
internal suspend fun RoutingContext.handleMakeFxPresetHard(
    state: State,
    projectIdStr: String,
    presetId: Int,
) {
    val request = try {
        call.receive<PresetMakeHardRequest>()
    } catch (_: Exception) {
        PresetMakeHardRequest()
    }

    val mask = try {
        parseMaskGroups(request.mask)
    } catch (e: IllegalArgumentException) {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad mask"))
    }
    val paletteFilter = request.paletteUuids
        ?.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        ?.toSet()
        ?.takeIf { it.isNotEmpty() }

    withCurrentProject(state, projectIdStr, { p ->
        "Cannot modify presets in project '${p.name}' — only the current project can be modified"
    }) { project ->
        val outcome = transaction(state.database) {
            val preset = DaoFxPreset.findById(presetId)?.takeIf { it.project.id == project.id }
                ?: return@transaction null
            hardenPresetRows(state, preset, paletteFilter, mask)
        }
        if (outcome == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Preset not found in current project"))
            return@withCurrentProject
        }

        // Hardening is output-neutral for a fixture the palette covered — it already showed this
        // literal. But a fixture the palette *doesn't* cover had its row skipped at apply and now
        // takes the literal, so live cues applying this preset have to be rebuilt.
        val republished = if (outcome.converted > 0) {
            republishCuesApplyingPreset(state, presetId)
        } else {
            emptyList()
        }

        val details = transaction(state.database) {
            DaoFxPreset.findById(presetId)!!.toPresetDetails(isCurrentProject = true)
        }
        if (outcome.converted > 0) {
            state.show.fixtures.presetListChanged()
            // The palettes' reference counts moved, which gates their delete.
            state.show.fixtures.paletteListChanged()
        }
        logger.info(
            "make-hard preset {}: {} converted, {} ambiguous, {} unresolved, {} cue(s) republished",
            presetId, outcome.converted, outcome.ambiguous.size, outcome.unresolved, republished.size,
        )
        call.respond(
            PresetMakeHardResponse(
                preset = details,
                converted = outcome.converted,
                ambiguous = outcome.ambiguous,
                unresolved = outcome.unresolved,
                cuesRepublished = republished,
            ),
        )
    }
}

private data class PresetHardenOutcome(
    val converted: Int,
    val ambiguous: List<PresetRefAmbiguity>,
    val unresolved: Int,
)

/** Must be called inside a transaction. */
private fun hardenPresetRows(
    state: State,
    preset: DaoFxPreset,
    paletteFilter: Set<UUID>?,
    mask: Set<PropertyMaskGroup>?,
): PresetHardenOutcome {
    var converted = 0
    var unresolved = 0
    val ambiguous = ArrayList<PresetRefAmbiguity>()
    val registry = state.show.paletteRegistry

    // Every fixture this preset could ever be applied to. Not "every fixture it is applied to
    // today" — that set changes the next time an operator drops the preset on something else,
    // and a literal chosen from it would silently be wrong there.
    val candidates: List<Fixture> = state.show.fixtures.fixtures
        .filter { it.typeKey == preset.fixtureType }

    for (row in preset.propertyAssignments.sortedBy { it.sortOrder }.toList()) {
        val paletteUuid = parsePaletteRef(row.value) ?: continue
        if (paletteFilter != null && paletteUuid !in paletteFilter) continue

        // An element-scoped ref can never resolve: palettes are fixture-shaped by construction,
        // so buildCueAssignmentsForPreset rejects these outright. There is nothing to harden to.
        if (row.elementKey != null) {
            unresolved++
            continue
        }

        val canonical = canonicalPropertyName(row.propertyName)
        if (candidates.isEmpty()) {
            unresolved++
            continue
        }
        // Any candidate answers for all of them: they share a type key, so they share a property
        // catalogue. (The cue route has to be more careful — a group can be mixed-type.)
        if (!maskAllows(mask, maskGroupForProperty(candidates.first(), canonical))) continue

        // Group by the *serialized* literal, so "#FF8800" and "#ff8800" are one answer and the
        // value written matches what the cue route would write byte for byte.
        val byLiteral = LinkedHashMap<String, MutableList<String>>()
        for (fixture in candidates) {
            val category = fixtureCategoryFor(fixture, canonical)?.first ?: continue
            val resolution = resolveAssignmentValueForFixture(
                registry, fixture.key, canonical, category, row.value,
            )
            val literal = resolution.value?.serialize() ?: continue
            byLiteral.getOrPut(literal) { ArrayList() }.add(fixture.key)
        }

        when (byLiteral.size) {
            0 -> unresolved++
            1 -> {
                row.value = byLiteral.keys.first()
                converted++
            }
            else -> ambiguous += PresetRefAmbiguity(
                // The row's *stored* name, not the canonical one: this list is read next to the
                // preset editor's own rows, and `colour` there against `rgbColour` here reads as
                // a different row.
                propertyName = row.propertyName,
                paletteUuid = paletteUuid.toString(),
                paletteName = registry.snapshot(paletteUuid)?.name,
                variants = byLiteral.entries
                    .sortedWith(compareByDescending<Map.Entry<String, List<String>>> { it.value.size }
                        .thenBy { it.key })
                    .map { PresetRefVariant(it.key, it.value.sorted()) },
            )
        }
    }

    return PresetHardenOutcome(converted, ambiguous, unresolved)
}

/**
 * Rebuild and republish the live cues that apply [presetId], returning the cue ids touched.
 *
 * Deliberately *not* [republishForPaletteEdit]: that invalidates the palette and re-resolves the
 * programmer's ref slots, and no palette changed here — only a preset's stored values did.
 *
 * **Immediate** applications only. [rebuildCueLayerRows] composes the cue's own rows plus its
 * immediate presets and nothing else, and [uk.me.cormack.lighting7.fx.FxEngine.replaceCueAssignments]
 * swaps a cue's Layer 4 wholesale — so republishing a cue whose only application of this preset is
 * timed would *delete* the rows an already-fired delayed/recurring preset appended via
 * `appendCueAssignments`. A timed application needs no republish anyway: its rows are rebuilt from
 * the preset at each fire.
 */
private fun republishCuesApplyingPreset(state: State, presetId: Int): List<Int> {
    val engine = state.show.fxEngine
    val activeCueIds = engine.activeCueAssignmentIds()
    if (activeCueIds.isEmpty()) return emptyList()

    val referencing = transaction(state.database) {
        DaoCuePresetApplication.find {
            (DaoCuePresetApplications.cue inList activeCueIds.toList()) and
                (DaoCuePresetApplications.preset eq presetId) and
                DaoCuePresetApplications.delayMs.isNull() and
                DaoCuePresetApplications.intervalMs.isNull()
        }.map { it.cue.id.value }.distinct().sorted()
    }
    if (referencing.isEmpty()) return emptyList()

    val rebuilt = LinkedHashMap<Int, List<uk.me.cormack.lighting7.fx.CueAssignmentResolver.Assignment>>()
    for (cueId in referencing) {
        rebuilt[cueId] = rebuildCueLayerRows(state, cueId) ?: continue
    }
    if (rebuilt.isEmpty()) return emptyList()
    engine.replaceCueAssignments(rebuilt)
    return rebuilt.keys.toList()
}
