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
import uk.me.cormack.lighting7.fx.TemplateIntent
import uk.me.cormack.lighting7.fx.TemplateProperty
import uk.me.cormack.lighting7.fx.WhitePolicy
import uk.me.cormack.lighting7.fx.parseMaskGroups
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.DaoTemplateRow
import uk.me.cormack.lighting7.models.DaoTemplates
import uk.me.cormack.lighting7.models.TemplateRowDto
import uk.me.cormack.lighting7.state.State
import kotlin.math.roundToInt

/**
 * **New from selection**: record what the operator has selected as a template.
 *
 * The chip at the end of the programmer's template strip, and the reason the library fills up without
 * anyone visiting it.
 *
 * Server-side rather than composed in the client, and the reasons are the same three that make the
 * apply route server-side: the server holds the true programmer state, it already owns the
 * mask-and-scope vocabulary Record uses, and converting a *literal* back to an **intent** is
 * per-head arithmetic that must agree with [uk.me.cormack.lighting7.fx.TemplateResolver] — a client
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
@Resource("/{projectId}/templates/from-programmer")
internal data class TemplateFromProgrammerResource(val projectId: String)

@Serializable
internal data class TemplateFromProgrammerRequest(
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
internal data class TemplateFromProgrammerResponse(
    val template: TemplateDto,
    /** True when every head agreed and the template came out generic. */
    val isGeneric: Boolean,
    /** Properties that could not be expressed as an intent, with the reason. */
    val skipped: List<TemplateSkipDto> = emptyList(),
)

internal fun Route.routeApiRestTemplateRecord(state: State) {
    post<TemplateFromProgrammerResource> { resource ->
        handleTemplateFromProgrammer(state, resource.projectId)
    }
}

private suspend fun RoutingContext.handleTemplateFromProgrammer(state: State, projectId: String) {
    val request = call.receive<TemplateFromProgrammerRequest>()
    val name = request.name.trim()
    if (name.isEmpty()) {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Template name must not be blank"))
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
            ErrorResponse("A template holds exactly one attribute family, so mask must name exactly one"),
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

        val skipped = ArrayList<TemplateSkipDto>()
        collectSkips.forEach { skip ->
            skipped += TemplateSkipDto(skip.targetKey ?: "", skip.propertyName ?: "", skip.reason.name)
        }

        // property → (fixtureKey → intent). Grouped by property first because the generic/per-fixture
        // decision is per property: a selection may agree on colour and differ on position.
        val byProperty = LinkedHashMap<String, LinkedHashMap<String, TemplateIntent>>()
        for (entry in entries) {
            val vocabulary = TemplateProperty.ofOrNull(entry.propertyName)
            if (vocabulary == null) {
                // A slotted role, or a property no template can hold. Reported rather than dropped:
                // an operator who selected a gobo cell and pressed this needs to know why it is not
                // in the result.
                skipped += TemplateSkipDto(
                    entry.fixtureKey, entry.propertyName,
                    "templates hold no ${entry.propertyName} — it lives in a recorded look",
                )
                continue
            }
            val fixture = runCatching {
                state.show.fixtures.untypedGroupableFixture(entry.fixtureKey)
            }.getOrNull()
            val intent = intentFor(vocabulary, entry.value, fixture)
            if (intent == null) {
                skipped += TemplateSkipDto(
                    entry.fixtureKey, entry.propertyName,
                    "could not be expressed as a template value",
                )
                continue
            }
            byProperty.getOrPut(vocabulary.propertyName) { LinkedHashMap() }[entry.fixtureKey] = intent
        }

        if (byProperty.isEmpty()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("None of those values can be held by a template"),
            )
            return@withCurrentProject
        }

        val rows = ArrayList<TemplateRowDto>()
        var allGeneric = true
        for ((propertyName, byFixture) in byProperty) {
            val distinct = byFixture.values.map { it.serialize() }.distinct()
            if (distinct.size == 1) {
                rows += TemplateRowDto(DEFERRED_TARGET_TYPE, "", propertyName, distinct.single(), rows.size)
            } else {
                allGeneric = false
                for ((fixtureKey, intent) in byFixture) {
                    rows += TemplateRowDto("fixture", fixtureKey, propertyName, intent.serialize(), rows.size)
                }
            }
        }

        // Through the same write boundary as a hand-authored template. It cannot legitimately fail
        // here — the vocabulary check above is the same one — but going round it would make this the
        // one path that can seed an invalid template.
        validateTemplateRows(rows)?.let { problem ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(problem))
            return@withCurrentProject
        }

        val created = transaction(state.database) {
            val clash = DaoTemplate.find {
                (DaoTemplates.project eq project.id) and (DaoTemplates.name eq name)
            }.firstOrNull()
            if (clash != null) return@transaction null
            val template = DaoTemplate.new {
                this.project = project
                this.name = name
                this.notes = request.notes?.trim()?.takeIf { it.isNotEmpty() }
                this.sortOrder = (
                    DaoTemplate.find { DaoTemplates.project eq project.id }.maxOfOrNull { it.sortOrder } ?: -1
                    ) + 1
                this.fadeDurationMs = request.fadeDurationMs
            }
            for (row in rows) {
                DaoTemplateRow.new {
                    this.template = template
                    targetType = row.targetType
                    targetKey = row.targetKey
                    propertyName = row.propertyName
                    value = row.value
                    sortOrder = row.sortOrder
                }
            }
            template.toDto()
        }
        if (created == null) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("A template named '$name' already exists in this project"),
            )
            return@withCurrentProject
        }
        state.show.fixtures.templateListChanged()
        call.respond(TemplateFromProgrammerResponse(created, allGeneric, skipped))
    }
}

/**
 * A recorded literal, read back as the intent that would reproduce it.
 *
 * [fixture] is needed for everything except colour, because a percentage is only meaningful against
 * the head's own range and degrees only against its annotated one — which is the same asymmetry
 * `TemplateResolver` has in the other direction. Null when the head has gone from the patch since the
 * value was set.
 */
private fun intentFor(
    property: TemplateProperty,
    value: CueAssignmentResolver.PropertyValue,
    fixture: GroupableFixture?,
): TemplateIntent? = when (property) {
    TemplateProperty.COLOUR -> {
        val colour = (value as? CueAssignmentResolver.PropertyValue.Colour)?.value ?: return null
        // Fold the extra emitters back into RGB, and let the policy say they were driven. See the
        // file's class doc: the inverse is not unique, and this is the reading that round-trips.
        val extra = maxOf(colour.white.toInt(), colour.amber.toInt())
        val hex = String.format(
            "#%02X%02X%02X",
            (colour.color.red + extra).coerceAtMost(255),
            (colour.color.green + extra).coerceAtMost(255),
            (colour.color.blue + extra).coerceAtMost(255),
        )
        TemplateIntent.Colour(hex, if (extra > 0) WhitePolicy.EXTRACT else WhitePolicy.RGB_ONLY)
    }

    TemplateProperty.POSITION -> {
        val position = (value as? CueAssignmentResolver.PropertyValue.Position) ?: return null
        val catalogue = (fixture as? uk.me.cormack.lighting7.fixture.Fixture)?.fixtureProperties
            ?: return null
        val pan = catalogue.firstOrNull { it.category == uk.me.cormack.lighting7.fixture.PropertyCategory.PAN }
        val tilt = catalogue.firstOrNull { it.category == uk.me.cormack.lighting7.fixture.PropertyCategory.TILT }
        val panDeg = dmxToDegrees(position.pan.toInt(), pan?.degMin, pan?.degMax, pan?.inverted == true)
            ?: return null
        val tiltDeg = dmxToDegrees(position.tilt.toInt(), tilt?.degMin, tilt?.degMax, tilt?.inverted == true)
            ?: return null
        TemplateIntent.Position(panDeg, tiltDeg)
    }

    TemplateProperty.PRISM -> {
        val level = when (value) {
            is CueAssignmentResolver.PropertyValue.Setting -> value.channelValue.toInt()
            is CueAssignmentResolver.PropertyValue.Slider -> value.value.toInt()
            else -> return null
        }
        TemplateIntent.Switch(level > 0)
    }

    TemplateProperty.DIMMER, TemplateProperty.STROBE, TemplateProperty.ZOOM,
    TemplateProperty.FOCUS, TemplateProperty.IRIS, TemplateProperty.FROST -> {
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
        TemplateIntent.Percent((((level - min).toDouble() / span) * 100.0).coerceIn(0.0, 100.0))
    }
}

/** The inverse of [uk.me.cormack.lighting7.fx.TemplateResolver]'s degree mapping. */
private fun dmxToDegrees(dmx: Int, degMin: Double?, degMax: Double?, inverted: Boolean): Double? {
    if (degMin == null || degMax == null || degMin == degMax) return null
    val fraction = (dmx / 255.0).coerceIn(0.0, 1.0)
    val effective = if (inverted) 1.0 - fraction else fraction
    return ((degMin + effective * (degMax - degMin)) * 10.0).roundToInt() / 10.0
}
