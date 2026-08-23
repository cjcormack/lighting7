package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import uk.me.cormack.lighting7.models.CueAdHocEffectDto
import uk.me.cormack.lighting7.models.CueLayerDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.LookEffectDto
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto

/**
 * A minimal ad-hoc effect for a test cue. Wraps [CueAdHocEffectDto]'s wide constructor so the
 * Include/Update tests can say what they mean in one line.
 */
data class CueAdHocEffectSpec(
    val targetType: String,
    val targetKey: String,
    val effectType: String,
    val propertyName: String,
    val category: String = "DIMMER",
    val beatDivision: Double = 1.0,
)

/** Cue and stack construction shared by the Record / Include / Update route tests. */
object ProgrammerRouteTestSupport {

    /** Create a stack via the real route, returning its id. */
    suspend fun createStack(client: HttpClient, projectId: Int, name: String): Int =
        client.post("/api/rest/project/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }.body<CueStackDetails>().id

    /**
     * Create a cue in a freshly-made stack via the real route, returning its id. Every cue
     * belongs to a stack, so tests that only care about cue contents still need one.
     */
    suspend fun createCue(
        client: HttpClient,
        projectId: Int,
        name: String,
        rows: List<CuePropertyAssignmentDto> = emptyList(),
        adHoc: List<CueAdHocEffectSpec> = emptyList(),
        stackId: Int? = null,
        layers: List<CueLayerDto> = emptyList(),
    ): Int {
        val stack = stackId ?: createStack(client, projectId, "stack-for-$name")
        return client.post("/api/rest/project/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(
                NewCue(
                    name = name,
                    cueStackId = stack,
                    propertyAssignments = rows,
                    adHocEffects = adHoc.map { it.toDto() },
                    layers = layers,
                )
            )
        }.body<CueDetails>().id
    }

    /**
     * Create a Look whose rows are **bound** to [targetKey], and return it, for building layers with.
     *
     * This was `createLookBoundTo`, and the rename is the session-3 split showing up in the tests: a
     * Look row can no longer be deferred at all — a value you point at a selection is a *template*
     * now — so a layer's targets **filter** these rows rather than supplying them. Every caller seeds
     * `hex-1`, which is why that is the default.
     */
    internal suspend fun createLookBoundTo(
        client: HttpClient,
        projectId: Int,
        name: String,
        rows: Map<String, String>,
        effects: List<LookEffectDto> = emptyList(),
        targetKey: String = "hex-1",
        /** `fixture` or `group` — a *Look* row may name either; only a template may not. */
        targetType: String = "fixture",
    ): LookDetails = client.post("/api/rest/project/$projectId/looks") {
        contentType(ContentType.Application.Json)
        setBody(
            CreateLookRequest(
                name = name,
                rows = rows.entries.mapIndexed { index, (property, value) ->
                    LookRowDto(targetType, targetKey, property, value, sortOrder = index)
                },
                effects = effects,
            )
        )
    }.body()

    private fun CueAdHocEffectSpec.toDto() = CueAdHocEffectDto(
        targetType = targetType,
        targetKey = targetKey,
        effectType = effectType,
        category = category,
        propertyName = propertyName,
        beatDivision = beatDivision,
        blendMode = "OVERRIDE",
        distribution = "LINEAR",
    )
}
