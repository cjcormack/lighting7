package uk.me.cormack.lighting7.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import uk.me.cormack.lighting7.fixture.lantern.LanternFocus
import uk.me.cormack.lighting7.fixture.lantern.LanternLibrary
import uk.me.cormack.lighting7.fixture.lantern.ShutterBlade
import uk.me.cormack.lighting7.fixture.lantern.focus
import uk.me.cormack.lighting7.fixture.media.FittedMedia
import uk.me.cormack.lighting7.fixture.media.fittedMedia
import java.util.UUID

/**
 * A venue patch's four **facets** (venue plan §3.2, D4) as values: what a show's override stores and
 * what the venue's patch holds, in one shape so the two compare field for field. Each facet is
 * **whole** — every field it covers, on the primary placement and on each extra placement — because
 * shutters without the position they were cut against mean nothing.
 *
 * | Facet | Fields |
 * |---|---|
 * | Address | `universeConfigUuid`, `startChannel` |
 * | Position | `riggingUuid`, `stageX/Y/Z`, `baseYaw/Pitch/RollDeg`, `lengthM`, and each placement's |
 * | Focus | `lanternType`, `beamAngleDeg`, `lampWatts`, `lampKelvin`, `zoomDeg`, `lampRotationDeg`, `shutters`, `gateRotationDeg`, `iris`, `focusSoftness`, and each placement's lantern fields |
 * | Media | `gelCode`, `media`, and each placement's |
 *
 * **Placements are carried by the venue placement's uuid** (`placements: [{placementUuid, …}]`), each
 * entry the placement's own fields of that facet. A placement is the venue's — its count, order and
 * label are identity, never a facet — so an override cannot add or remove one: a placement the value
 * names that the pinned version lacks contributes nothing, and a placement the value does not name
 * keeps the venue's fields (which is also how a placement a later version adds arrives). Universes and
 * riggings are named by uuid, as every link is, so a value is portable and a clone remaps a show-owned
 * one with the rest of its archive (`ExportUuidRemapper`); the references are `…Uuid`, never `uuid`,
 * so they never mint an identity of their own.
 *
 * Stored as compact canonical JSON ([encodeFacet]): keys sorted, nulls and defaults omitted. Two values
 * are compared decoded, never as text.
 */
sealed interface FacetValue {
    val facet: PatchFacet
}

@Serializable
data class AddressFacet(
    val universeConfigUuid: String,
    val startChannel: Int,
) : FacetValue {
    override val facet: PatchFacet get() = PatchFacet.ADDRESS
}

@Serializable
data class PositionFacet(
    val riggingUuid: String? = null,
    val stageX: Double? = null,
    val stageY: Double? = null,
    val stageZ: Double? = null,
    val baseYawDeg: Double? = null,
    val basePitchDeg: Double? = null,
    val baseRollDeg: Double? = null,
    val lengthM: Double? = null,
    val placements: List<PlacementPosition> = emptyList(),
) : FacetValue {
    override val facet: PatchFacet get() = PatchFacet.POSITION
}

@Serializable
data class PlacementPosition(
    val placementUuid: String,
    val riggingUuid: String? = null,
    val stageX: Double? = null,
    val stageY: Double? = null,
    val stageZ: Double? = null,
    val baseYawDeg: Double? = null,
    val basePitchDeg: Double? = null,
    val baseRollDeg: Double? = null,
    val lengthM: Double? = null,
)

@Serializable
data class FocusFacet(
    val lanternType: String? = null,
    val beamAngleDeg: Int? = null,
    /** The lamp — the patch's alone, one for every lantern it drives, so no placement carries one. */
    val lampWatts: Int? = null,
    val lampKelvin: Int? = null,
    val zoomDeg: Double? = null,
    val lampRotationDeg: Double? = null,
    val shutters: List<ShutterBlade>? = null,
    val gateRotationDeg: Double? = null,
    val iris: Double? = null,
    val focusSoftness: Double? = null,
    val placements: List<PlacementFocus> = emptyList(),
) : FacetValue {
    override val facet: PatchFacet get() = PatchFacet.FOCUS

    /** The seven lantern fields, as the patch columns hold them. */
    val lantern: LanternFocus
        get() = LanternFocus(lanternType, zoomDeg, lampRotationDeg, shutters, gateRotationDeg, iris, focusSoftness)
}

@Serializable
data class PlacementFocus(
    val placementUuid: String,
    val lanternType: String? = null,
    val zoomDeg: Double? = null,
    val lampRotationDeg: Double? = null,
    val shutters: List<ShutterBlade>? = null,
    val gateRotationDeg: Double? = null,
    val iris: Double? = null,
    val focusSoftness: Double? = null,
) {
    val lantern: LanternFocus
        get() = LanternFocus(lanternType, zoomDeg, lampRotationDeg, shutters, gateRotationDeg, iris, focusSoftness)
}

@Serializable
data class MediaFacet(
    val gelCode: String? = null,
    val media: FittedMedia? = null,
    val placements: List<PlacementMedia> = emptyList(),
) : FacetValue {
    override val facet: PatchFacet get() = PatchFacet.MEDIA
}

@Serializable
data class PlacementMedia(
    val placementUuid: String,
    val media: FittedMedia? = null,
)

/** Compact canonical JSON for facet values: keys sorted, nulls and defaults left out. */
private val facetJson = Json {
    explicitNulls = false
    encodeDefaults = false
    ignoreUnknownKeys = true
}

@Suppress("UNCHECKED_CAST")
private fun serializerOf(facet: PatchFacet): KSerializer<FacetValue> = when (facet) {
    PatchFacet.ADDRESS -> AddressFacet.serializer()
    PatchFacet.POSITION -> PositionFacet.serializer()
    PatchFacet.FOCUS -> FocusFacet.serializer()
    PatchFacet.MEDIA -> MediaFacet.serializer()
} as KSerializer<FacetValue>

/** [value] as a JSON element with its keys sorted: what the wire and the sync archive carry. */
fun facetElement(value: FacetValue): JsonElement = sortedKeys(facetJson.encodeToJsonElement(serializerOf(value.facet), value))

/** [value] as the compact canonical text the `show_overrides` columns hold. */
fun encodeFacet(value: FacetValue): String = facetElement(value).toString()

/** A stored or archived facet value, or null when it does not parse as [facet]'s shape. */
fun decodeFacet(facet: PatchFacet, text: String): FacetValue? =
    runCatching { facetJson.decodeFromString(serializerOf(facet), text) }.getOrNull()

/** As [decodeFacet], from an element. */
fun decodeFacet(facet: PatchFacet, element: JsonElement): FacetValue? =
    runCatching { facetJson.decodeFromJsonElement(serializerOf(facet), element) }.getOrNull()

private fun sortedKeys(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { (k, v) -> k to sortedKeys(v) })
    is JsonArray -> JsonArray(element.map(::sortedKeys))
    else -> element
}

/**
 * The venue's value of [facet] on [patch], whose extra placements are [placements] in stored order —
 * what an override's `base` records and what drift is judged against. Inside a transaction.
 */
fun venueFacetOf(patch: DaoFixturePatch, placements: List<DaoFixturePatchPlacement>, facet: PatchFacet): FacetValue =
    when (facet) {
        PatchFacet.ADDRESS -> AddressFacet(patch.universeConfig.uuid.toString(), patch.startChannel)
        PatchFacet.POSITION -> PositionFacet(
            riggingUuid = patch.rigging?.uuid?.toString(),
            stageX = patch.stageX, stageY = patch.stageY, stageZ = patch.stageZ,
            baseYawDeg = patch.baseYawDeg, basePitchDeg = patch.basePitchDeg, baseRollDeg = patch.baseRollDeg,
            lengthM = patch.lengthM,
            placements = placements.map { p ->
                PlacementPosition(
                    placementUuid = p.uuid.toString(),
                    riggingUuid = p.rigging?.uuid?.toString(),
                    stageX = p.stageX, stageY = p.stageY, stageZ = p.stageZ,
                    baseYawDeg = p.baseYawDeg, basePitchDeg = p.basePitchDeg, baseRollDeg = p.baseRollDeg,
                    lengthM = p.lengthM,
                )
            },
        )
        PatchFacet.FOCUS -> patch.focus.let { f ->
            FocusFacet(
                lanternType = f.lanternType, beamAngleDeg = patch.beamAngleDeg,
                lampWatts = patch.lampWatts, lampKelvin = patch.lampKelvin,
                zoomDeg = f.zoomDeg, lampRotationDeg = f.lampRotationDeg, shutters = f.shutters,
                gateRotationDeg = f.gateRotationDeg, iris = f.iris, focusSoftness = f.focusSoftness,
                placements = placements.map { p ->
                    val pf = p.focus
                    PlacementFocus(
                        placementUuid = p.uuid.toString(),
                        lanternType = pf.lanternType, zoomDeg = pf.zoomDeg, lampRotationDeg = pf.lampRotationDeg,
                        shutters = pf.shutters, gateRotationDeg = pf.gateRotationDeg, iris = pf.iris,
                        focusSoftness = pf.focusSoftness,
                    )
                },
            )
        }
        PatchFacet.MEDIA -> MediaFacet(
            gelCode = patch.gelCode,
            media = patch.fittedMedia,
            placements = placements.map { p -> PlacementMedia(p.uuid.toString(), p.fittedMedia) },
        )
    }

/**
 * A patch as its rig sees it (venue plan §3.3): the stored row with each facet the show overrides
 * laid over it. For a show's own patch, a venue's, a version's or a standalone show's, every field is
 * the row's own. Read every facet field through this — the loader, the Patch list, the Stage view's
 * data, `describe_rig` and every check of where a unit is — and the row's identity through [row].
 * Built by [RigScope.effective]; inside a transaction.
 */
class EffectivePatch internal constructor(
    val row: DaoFixturePatch,
    /** The facets an override replaced here; empty for every patch but an overridden venue one. */
    val overridden: Set<PatchFacet>,
    val universeConfig: DaoUniverseConfig,
    val startChannel: Int,
    val rigging: DaoRigging?,
    val stageX: Double?,
    val stageY: Double?,
    val stageZ: Double?,
    val baseYawDeg: Double?,
    val basePitchDeg: Double?,
    val baseRollDeg: Double?,
    val lengthM: Double?,
    val focus: LanternFocus,
    val beamAngleDeg: Int?,
    val lampWatts: Int?,
    val lampKelvin: Int?,
    /** Derived from the lantern as a write derives it (`resolvePatchFocus`): an overridden lantern's kind. */
    val kindOverride: String?,
    val gelCode: String?,
    val fittedMedia: FittedMedia?,
) {
    val id: EntityID<Int> get() = row.id
    val uuid: UUID get() = row.uuid
    val project: DaoProject get() = row.project
    val key: String get() = row.key
    val displayName: String get() = row.displayName
    val headNumber: Int? get() = row.headNumber
    val fixtureTypeKey: String get() = row.fixtureTypeKey
    val sortOrder: Int get() = row.sortOrder
    val stageHidden: Boolean get() = row.stageHidden
    val infrastructure: Boolean get() = row.infrastructure
    val lanternType: String? get() = focus.lanternType
}

/** One extra placement as its rig sees it: [EffectivePatch]'s rule for a placement's facet fields. */
class EffectivePlacement internal constructor(
    val row: DaoFixturePatchPlacement,
    val rigging: DaoRigging?,
    val stageX: Double?,
    val stageY: Double?,
    val stageZ: Double?,
    val baseYawDeg: Double?,
    val basePitchDeg: Double?,
    val baseRollDeg: Double?,
    val lengthM: Double?,
    val focus: LanternFocus,
    val fittedMedia: FittedMedia?,
) {
    val uuid: UUID get() = row.uuid
    val label: String? get() = row.label
    val lanternType: String? get() = focus.lanternType
}

/**
 * The kind an overridden lantern leaves a patch with, by `resolvePatchFocus`'s rule: a lantern named
 * gives its family's kind; with none named, a venue kind that the venue's own lantern derived goes with
 * it (the type's own kind shows through), and a kind the venue set by hand stays.
 */
internal fun kindUnderLantern(lanternType: String?, venueLanternType: String?, venueKindOverride: String?): String? =
    LanternLibrary.byId(lanternType)?.kind?.name
        ?: if (venueLanternType != null) null else venueKindOverride
