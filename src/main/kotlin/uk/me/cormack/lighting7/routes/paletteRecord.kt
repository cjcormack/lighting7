package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.PropertyChannelWriter
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.PaletteIntent
import uk.me.cormack.lighting7.fx.PaletteProperty
import uk.me.cormack.lighting7.fx.PaletteResolver
import uk.me.cormack.lighting7.fx.WhitePolicy
import uk.me.cormack.lighting7.fx.parseMaskGroups
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoPaletteRow
import uk.me.cormack.lighting7.models.DaoPalettes
import uk.me.cormack.lighting7.models.PaletteRowDto
import uk.me.cormack.lighting7.state.State
import java.time.Duration
import uk.me.cormack.lighting7.models.asDuration

/**
 * **New from selection**: record what the operator has selected as a template.
 *
 * The chip at the end of the programmer's template strip, and the reason the library fills up without
 * anyone visiting it.
 *
 * Server-side rather than composed in the client, and the reasons are the same three that make the
 * apply route server-side: the server holds the true programmer state, it already owns the
 * mask-and-scope vocabulary Record uses, and converting a *literal* back to an **intent** is
 * per-head arithmetic that must agree with [uk.me.cormack.lighting7.fx.PaletteResolver] — a client
 * doing it would be a second opinion about what the rig is showing.
 *
 * ## Generic or per fixture, decided by the data
 *
 * One row per property when every selected head agrees on the value (a *generic* template), one row
 * per head when they do not (a *per fixture* one, which is what a focus position is). Decided here
 * rather than by a toggle in the sheet: the operator already expressed which they meant by putting
 * the heads where they are.
 *
 * ## Converting a literal back to an intent is a heuristic, and it is stated once
 *
 * A recorded colour is per-head channel bytes; a template holds a hex plus a policy. The inverse is
 * not unique — several (rgb, white) pairs mix to the same light — so this picks the reading that
 * round-trips through `EXTRACT`: fold the white/amber back into RGB and mark the policy `extract`
 * when either emitter was driven, `rgbonly` when neither was. It is the reading that reproduces what
 * the operator was looking at on the head they recorded from, which is the property that matters.
 */
@Resource("/{projectId}/palettes/from-programmer")
internal data class PaletteFromProgrammerResource(val projectId: String)

@Serializable
internal data class PaletteFromProgrammerRequest(
    val name: String,
    val notes: String? = null,
    val fadeDurationMs: Long? = null,
    /**
     * [PropertyMaskGroup] names to record — in practice exactly one, since a template holds one
     * family. A wider mask is refused rather than silently narrowed: the caller believed something
     * about what it was recording, and that belief is wrong.
     */
    val mask: List<String>? = null,
    /** The operator's selection. Groups are expanded server-side. */
    val targets: List<CueTargetDto> = emptyList(),
    /** [RecordSource] name. Defaults to TOUCHED — what the operator actually set. */
    val source: String = "TOUCHED",
)

@Serializable
internal data class PaletteFromProgrammerResponse(
    val palette: PaletteDto,
    /** True when every head agreed and the template came out generic. */
    val isGeneric: Boolean,
    /** Properties that could not be expressed as an intent, with the reason. */
    val skipped: List<PaletteSkipDto> = emptyList(),
)

internal fun Route.routeApiRestPaletteRecord(state: State) {
    post<PaletteFromProgrammerResource> { resource ->
        handlePaletteFromProgrammer(state, resource.projectId)
    }
}

private suspend fun RoutingContext.handlePaletteFromProgrammer(state: State, projectId: String) {
    if (refusedRecordInVenue(state)) return
    val request = call.receive<PaletteFromProgrammerRequest>()
    val name = request.name.trim()
    if (name.isEmpty()) {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Palette name must not be blank"))
    }
    val source = parseEnumOrNull<RecordSource>(request.source) ?: return call.respond(
        HttpStatusCode.BadRequest, ErrorResponse("Unknown record source '${request.source}'"),
    )
    val mask = try {
        parseMaskGroups(request.mask)
    } catch (e: IllegalArgumentException) {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad mask"))
    }
    if (mask == null || mask.size != 1) {
        return call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("A palette holds exactly one attribute family, so mask must name exactly one"),
        )
    }
    if (request.targets.isEmpty()) {
        return call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("Select the fixtures whose values you want to keep"),
        )
    }

    withCurrentProject(state, projectId, { p ->
        "Cannot record into project '${p.name}' — only the current project can be modified"
    }) { project ->
        val scope = expandTargetsToFixtureKeys(state, request.targets)
        if (scope.isEmpty()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("None of the requested targets resolve to a fixture"),
            )
            return@withCurrentProject
        }

        // The same collect step Record uses, so "what the operator set" means one thing on both
        // paths. Group collapsing is deliberately *not* applied: a template names no groups.
        val (entries, collectSkips) = collectProgrammerEntries(state, source, mask, targets = scope)
        if (entries.isEmpty()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Nothing to record — those fixtures hold no values in that family"),
            )
            return@withCurrentProject
        }

        val skipped = ArrayList<PaletteSkipDto>()
        collectSkips.forEach { skip ->
            skipped += PaletteSkipDto(skip.targetKey ?: "", skip.propertyName ?: "", skip.reason.name)
        }

        // property → (fixtureKey → intent). Grouped by property first because the generic/per-fixture
        // decision is per property: a selection may agree on colour and differ on position.
        val byProperty = LinkedHashMap<String, LinkedHashMap<String, PaletteIntent>>()

        // Which heads busked an emitter *as its own property* — a MIDI fader, or the programmer's
        // white slider — rather than only through a colour. Needed before the loop because it
        // changes how that head's colour reads back: the explicit row carries the neutral, so the
        // colour must not also fold it into the hex under an EXTRACT policy (the write boundary
        // refuses that combination, and it would double the emitter besides).
        val emittersRecordedSeparately: Map<String, Set<PaletteProperty>> = entries
            .mapNotNull { entry ->
                PaletteProperty.ofOrNull(entry.propertyName)
                    ?.takeIf { it.isEmitter }
                    ?.let { entry.fixtureKey to it }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, properties) -> properties.toSet() }

        // A UV component busked through a colour, per head. Held aside rather than written straight
        // into [byProperty]: an explicit `uv` entry for the same head is the better answer and may
        // arrive after this one, so these are merged in at the end and only where none exists.
        val derivedUv = LinkedHashMap<String, PaletteIntent>()

        for (entry in entries) {
            val vocabulary = PaletteProperty.ofOrNull(entry.propertyName)
            if (vocabulary == null) {
                // A slotted role, or a property no template can hold. Reported rather than dropped:
                // an operator who selected a gobo cell and pressed this needs to know why it is not
                // in the result.
                skipped += PaletteSkipDto(
                    entry.fixtureKey, entry.propertyName,
                    "palettes hold no ${entry.propertyName} — it lives in a recorded look",
                )
                continue
            }
            val fixture = runCatching {
                state.show.fixtures.untypedGroupableFixture(entry.fixtureKey)
            }.getOrNull()
            val recordedSeparately = emittersRecordedSeparately[entry.fixtureKey].orEmpty()
            val intent = intentFor(
                vocabulary,
                entry.value,
                fixture,
                whiteRecordedSeparately = PaletteProperty.WHITE in recordedSeparately,
                amberRecordedSeparately = PaletteProperty.AMBER in recordedSeparately,
            )
            if (intent == null) {
                skipped += PaletteSkipDto(
                    entry.fixtureKey, entry.propertyName,
                    "could not be expressed as a palette value",
                )
                continue
            }
            byProperty.getOrPut(vocabulary.propertyName) { LinkedHashMap() }[entry.fixtureKey] = intent

            // The colour inverse folds white and amber back into the hex, but **no policy has ever
            // driven UV** — so a UV busked through the colour picker had nowhere to go and was
            // silently lost. It gets a row of its own instead, which is also the one case where
            // recording concretely costs no portability: a `uv` row demands only a UV emitter, and a
            // head with UV busked on it certainly has one.
            if (vocabulary == PaletteProperty.COLOUR) {
                // **Every head with a colour entry, zeroes included.** Recording only the non-zero
                // ones let the generic/per-fixture collapse below read "the two heads that had UV
                // agree" as "all the recorded heads agree", and emit one deferred row that then
                // turned UV on for the head that never had any. The whole set is added only if some
                // head actually had UV — see the merge below.
                val uv = ((entry.value as? CueAssignmentResolver.PropertyValue.Colour)?.value?.uv ?: 0u).toInt()
                derivedUv[entry.fixtureKey] = PaletteIntent.Level(uv)
            }
        }

        // Only worth a row at all if some head was actually lit in UV. Every head at zero means the
        // operator never touched UV, and a template demanding a UV emitter to assert nothing would
        // narrow where it can be applied for no gain.
        if (derivedUv.values.any { (it as? PaletteIntent.Level)?.value?.let { v -> v > 0 } == true }) {
            val explicit = byProperty.getOrPut(PaletteProperty.UV.propertyName) { LinkedHashMap() }
            derivedUv.forEach { (fixtureKey, intent) -> explicit.putIfAbsent(fixtureKey, intent) }
        }

        if (byProperty.isEmpty()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("None of those values can be held by a palette"),
            )
            return@withCurrentProject
        }

        val rows = ArrayList<PaletteRowDto>()
        var allGeneric = true
        for ((propertyName, byFixture) in byProperty) {
            val distinct = byFixture.values.map { it.serialize() }.distinct()
            if (distinct.size == 1) {
                rows += PaletteRowDto(DEFERRED_TARGET_TYPE, "", propertyName, distinct.single(), rows.size)
            } else {
                allGeneric = false
                for ((fixtureKey, intent) in byFixture) {
                    rows += PaletteRowDto("fixture", fixtureKey, propertyName, intent.serialize(), rows.size)
                }
            }
        }

        // Through the same write boundary as a hand-authored template. It cannot legitimately fail
        // here — the vocabulary check above is the same one — but going round it would make this the
        // one path that can seed an invalid template.
        // Rows only: *New from selection* records what the heads are doing, which is values by
        // definition — an effect template's twin gesture is *Save as template…* on a running
        // effect, and it posts to the create route rather than here.
        validatePaletteContents(rows, effect = null, registry = state.show.fxRegistry)?.let { problem ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(problem))
            return@withCurrentProject
        }

        val created = transaction(state.database) {
            val clash = DaoPalette.find {
                (DaoPalettes.project eq project.id) and (DaoPalettes.name eq name)
            }.firstOrNull()
            if (clash != null) return@transaction null
            val template = DaoPalette.new {
                this.project = project
                this.name = name
                this.notes = request.notes?.trim()?.takeIf { it.isNotEmpty() }
                this.fadeDuration = request.fadeDurationMs.asDuration()
            }
            for (row in rows) {
                DaoPaletteRow.new {
                    this.palette = template
                    targetType = row.targetType
                    targetKey = row.targetKey
                    attribute = row.propertyName
                    value = row.value
                    sortOrder = row.sortOrder
                }
            }
            template.toDto(state.show.fxRegistry)
        }
        if (created == null) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("A palette named '$name' already exists in this project"),
            )
            return@withCurrentProject
        }
        state.show.fixtures.paletteListChanged()
        call.respond(PaletteFromProgrammerResponse(created, allGeneric, skipped))
    }
}

/**
 * A recorded literal, read back as the intent that would reproduce it.
 *
 * [fixture] is needed for everything except colour, because a percentage is only meaningful against
 * the head's own range and a centre-relative position only against its own travel — which is the
 * same asymmetry `PaletteResolver` has in the other direction. Null when the head has gone from the patch since the
 * value was set.
 */
private fun intentFor(
    property: PaletteProperty,
    value: CueAssignmentResolver.PropertyValue,
    fixture: GroupableFixture?,
    /**
     * True when this head has an explicit `white` entry in the same record, which takes that emitter
     * over from the policy — see the [PaletteProperty.COLOUR] arm.
     */
    whiteRecordedSeparately: Boolean = false,
    /** As [whiteRecordedSeparately], for amber. Tracked apart because `extra` folds only one byte. */
    amberRecordedSeparately: Boolean = false,
): PaletteIntent? = when (property) {
    PaletteProperty.COLOUR -> {
        val colour = (value as? CueAssignmentResolver.PropertyValue.Colour)?.value ?: return null
        // Fold the extra emitters back into RGB, and let the policy say they were driven. See the
        // file's class doc: the inverse is not unique, and this is the reading that round-trips.
        //
        // **Kept as the default deliberately, now that an explicit emitter row exists.** Recording
        // concrete white and amber bytes would be more faithful to this one rig and less use as a
        // template: the whole-template rule means such a template could then only ever be applied to
        // heads with both emitters, where the extract reading travels to an RGB-only PAR intact.
        // The exception is a head whose white or amber was busked *as its own property* — then the
        // explicit row already carries it, and folding it in again would double it and trip the
        // policy conflict at the write boundary.
        //
        // **Per emitter, not one flag for both.** `extra` is a single byte standing for whichever of
        // the two was driven, so zeroing it because *amber* had its own fader would also throw away
        // a white that only ever existed inside the colour — the operator would watch that component
        // vanish from a template recorded off a rig they could see it on.
        val extra = maxOf(
            if (whiteRecordedSeparately) 0 else colour.white.toInt(),
            if (amberRecordedSeparately) 0 else colour.amber.toInt(),
        )
        val hex = String.format(
            "#%02X%02X%02X",
            (colour.color.red + extra).coerceAtMost(255),
            (colour.color.green + extra).coerceAtMost(255),
            (colour.color.blue + extra).coerceAtMost(255),
        )
        PaletteIntent.Colour(hex, if (extra > 0) WhitePolicy.EXTRACT else WhitePolicy.RGB_ONLY)
    }

    // A byte straight back out — no range scaling, unlike the percent family below. The emitter's
    // slider is what the value came off and what it will be written to, and `PaletteResolver`
    // clamps it into that slider's own range on the way out.
    PaletteProperty.WHITE, PaletteProperty.AMBER, PaletteProperty.UV -> {
        val level = when (value) {
            is CueAssignmentResolver.PropertyValue.Slider -> value.value.toInt()
            is CueAssignmentResolver.PropertyValue.Setting -> value.channelValue.toInt()
            else -> return null
        }
        PaletteIntent.Level(level)
    }

    // Centre-relative, the unit every new position write takes (venue-visit plan P3): the exact
    // inverse of the resolver's own centre arm, so a recorded palette lands back on these bytes —
    // and, unlike the degrees it replaced, on a head whose travel carries no degree annotation.
    PaletteProperty.POSITION -> {
        val position = (value as? CueAssignmentResolver.PropertyValue.Position) ?: return null
        PaletteResolver.centreIntentOf(fixture ?: return null, position) ?: return null
    }

    PaletteProperty.PRISM -> {
        val level = when (value) {
            is CueAssignmentResolver.PropertyValue.Setting -> value.channelValue.toInt()
            is CueAssignmentResolver.PropertyValue.Slider -> value.value.toInt()
            else -> return null
        }
        PaletteIntent.Switch(level > 0)
    }

    PaletteProperty.DIMMER, PaletteProperty.STROBE, PaletteProperty.ZOOM,
    PaletteProperty.FOCUS, PaletteProperty.IRIS, PaletteProperty.FROST -> {
        val level = when (value) {
            is CueAssignmentResolver.PropertyValue.Slider -> value.value.toInt()
            is CueAssignmentResolver.PropertyValue.Setting -> value.channelValue.toInt()
            else -> return null
        }
        val resolved = fixture?.let {
            PropertyChannelWriter.resolveProperty(it, property.propertyName)?.value
        }
        val slider = resolved as? uk.me.cormack.lighting7.fixture.dmx.DmxSlider
        val min = slider?.min?.toInt() ?: 0
        val max = slider?.max?.toInt() ?: 255
        val span = (max - min).takeIf { it > 0 } ?: return null
        PaletteIntent.Percent((((level - min).toDouble() / span) * 100.0).coerceIn(0.0, 100.0))
    }
}
