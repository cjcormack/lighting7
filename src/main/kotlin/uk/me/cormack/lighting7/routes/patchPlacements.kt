package uk.me.cormack.lighting7.routes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import uk.me.cormack.lighting7.fixture.lantern.LanternFocus
import uk.me.cormack.lighting7.fixture.lantern.ShutterBlade
import uk.me.cormack.lighting7.fixture.lantern.focus
import uk.me.cormack.lighting7.fixture.media.FittedMedia
import uk.me.cormack.lighting7.fixture.media.fittedMedia
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatchPlacement
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.MAX_EXTRA_PLACEMENTS
import uk.me.cormack.lighting7.models.MAX_PLACEMENT_LABEL_LENGTH
import uk.me.cormack.lighting7.models.extraPlacementsOf
import java.util.UUID

/**
 * One of a patch's extra placements on the wire — the other places a paired fixture hangs.
 * Same geometry and rigging-relative rule as the patch's own `stageX`… fields; see
 * `DaoFixturePatchPlacements`.
 */
@Serializable
data class PatchPlacementDto(
    val uuid: String,
    val label: String? = null,
    val riggingUuid: String? = null,
    val stageX: Double? = null,
    val stageY: Double? = null,
    val stageZ: Double? = null,
    val baseYawDeg: Double? = null,
    val basePitchDeg: Double? = null,
    /** Body roll (about its local Z) — a ring's upright side is a placement rolled 90°. */
    val baseRollDeg: Double? = null,
    /** This segment's own length, for a variable-length type (a lightstrip laid in a ring); null
     *  takes the patch's `lengthM`. */
    val lengthM: Double? = null,
    /** This lantern's library id; null takes the patch's `lanternType`. The six fields after it are
     *  this lantern's own focus, never the patch's — see `FixturePatchDto.lanternType`. */
    val lanternType: String? = null,
    val zoomDeg: Double? = null,
    val lampRotationDeg: Double? = null,
    val shutters: List<ShutterBlade>? = null,
    val gateRotationDeg: Double? = null,
    val iris: Double? = null,
    val focusSoftness: Double? = null,
    /** This unit's own fitted media, layered over the patch's option by option; null fits nothing of
     *  its own. See `FixturePatchDto.media`. */
    val media: FittedMedia? = null,
)

/**
 * A validated `extraPlacements` entry, not yet resolved against the database.
 *
 * [uuid] is the placement the client is editing, or null for a new one. A uuid that names no
 * placement of *this* patch is treated as new and gets a fresh identity rather than the one
 * the client sent — a client can never mint, or steal, a record identity.
 */
internal data class PlacementInput(
    val uuid: UUID?,
    val label: String?,
    val riggingUuid: String?,
    val stageX: Double?,
    val stageY: Double?,
    val stageZ: Double?,
    val baseYawDeg: Double?,
    val basePitchDeg: Double?,
    val lengthM: Double? = null,
    val baseRollDeg: Double? = null,
    /** This lantern's focus data (stage-view plan D9, D14), range-checked; the type's and the
     *  library's rules need the patch and run where it is known ([placementFocusRefusal]). */
    val focus: LanternFocus = LanternFocus(),
    /** This unit's own fitted media, its shape checked; the type's rules need the patch and run
     *  where it is known ([patchMediaRefusal]). */
    val media: FittedMedia? = null,
)

/**
 * Parse and range-check an `extraPlacements` value from a PUT body: the **whole list**, which
 * replaces the patch's placements (entries matched to existing ones by uuid, the rest created,
 * the absent deleted). Within an entry every field is plain — absent and null both mean
 * null — because the entry is a whole placement, not a partial edit of one.
 *
 * Returns the inputs, or an error message for a 400. Runs outside the transaction, like
 * [validateStageMetadata], so a malformed body never opens one.
 */
internal fun parseExtraPlacements(value: JsonElement?): Result<List<PlacementInput>> {
    if (value == null || value is JsonNull) return Result.success(emptyList())
    val array = value as? JsonArray
        ?: return Result.failure(IllegalArgumentException("extraPlacements must be an array"))
    if (array.size > MAX_EXTRA_PLACEMENTS) {
        return Result.failure(IllegalArgumentException("At most $MAX_EXTRA_PLACEMENTS extra placements per fixture"))
    }
    val seen = mutableSetOf<UUID>()
    val out = mutableListOf<PlacementInput>()
    for ((index, element) in array.withIndex()) {
        val where = "extraPlacements[$index]"
        val entry = element as? JsonObject
            ?: return Result.failure(IllegalArgumentException("$where must be an object"))
        val input = try {
            val uuid = entry["uuid"].nullableString()?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
                    ?: return Result.failure(IllegalArgumentException("$where: uuid is not a UUID"))
            }
            val rawLabel = entry["label"].nullableString()?.trim()?.takeIf { it.isNotEmpty() }
            if (rawLabel != null && rawLabel.length > MAX_PLACEMENT_LABEL_LENGTH) {
                return Result.failure(
                    IllegalArgumentException("$where: label must be at most $MAX_PLACEMENT_LABEL_LENGTH characters"),
                )
            }
            PlacementInput(
                uuid = uuid,
                label = rawLabel,
                riggingUuid = entry["riggingUuid"].nullableString(),
                stageX = entry["stageX"].nullableDouble(),
                stageY = entry["stageY"].nullableDouble(),
                stageZ = entry["stageZ"].nullableDouble(),
                baseYawDeg = entry["baseYawDeg"].nullableDouble(),
                basePitchDeg = entry["basePitchDeg"].nullableDouble(),
                lengthM = entry["lengthM"].nullableDouble(),
                baseRollDeg = entry["baseRollDeg"].nullableDouble(),
                focus = LanternFocus.parse(entry).getOrElse {
                    return Result.failure(IllegalArgumentException("$where: ${it.message}"))
                },
                media = FittedMedia.parse(entry["media"], "$where.media").getOrElse {
                    return Result.failure(IllegalArgumentException(it.message))
                },
            )
        } catch (e: IllegalArgumentException) {
            // A wrong JSON type (a string where a number goes, an object where a string goes).
            return Result.failure(IllegalArgumentException("$where: ${e.message ?: "invalid value"}"))
        }
        validateStageMetadata(
            stageX = input.stageX,
            stageY = input.stageY,
            stageZ = input.stageZ,
            baseYawDeg = input.baseYawDeg,
            basePitchDeg = input.basePitchDeg,
            beamAngleDeg = null,
            lengthM = input.lengthM,
            baseRollDeg = input.baseRollDeg,
        )?.let { return Result.failure(IllegalArgumentException("$where: $it")) }
        if (input.uuid != null && !seen.add(input.uuid)) {
            return Result.failure(IllegalArgumentException("$where: uuid ${input.uuid} appears twice"))
        }
        out.add(input)
    }
    return Result.success(out)
}

/**
 * Resolve every rigging the inputs name against [project], **before** anything is written:
 * `return@transaction` is a normal return and Exposed commits on one, so an error found
 * mid-write would leave a half-applied list. Returns the riggings by uuid string, or an error.
 */
internal fun resolvePlacementRiggings(
    project: DaoProject,
    inputs: List<PlacementInput>,
): Result<Map<String, DaoRigging>> {
    val out = mutableMapOf<String, DaoRigging>()
    for (uuidStr in inputs.mapNotNull { it.riggingUuid }.distinct()) {
        out[uuidStr] = resolveRiggingForProject(project, uuidStr)
            ?: return Result.failure(IllegalArgumentException("Rigging $uuidStr not found"))
    }
    return Result.success(out)
}

/**
 * [resolvePlacementRiggings] against riggings a batch has already resolved, so the bulk route
 * looks each distinct rigging up once for the whole request rather than once per patch. A uuid
 * absent from [resolved], or resolved to null, is refused by the same message.
 */
internal fun placementRiggingsFrom(
    resolved: Map<String, DaoRigging?>,
    inputs: List<PlacementInput>,
): Result<Map<String, DaoRigging>> {
    val out = mutableMapOf<String, DaoRigging>()
    for (uuidStr in inputs.mapNotNull { it.riggingUuid }.distinct()) {
        out[uuidStr] = resolved[uuidStr]
            ?: return Result.failure(IllegalArgumentException("Rigging $uuidStr not found"))
    }
    return Result.success(out)
}

/**
 * Replace [patch]'s extra placements with [inputs], in order. Riggings must already be
 * resolved by [resolvePlacementRiggings]; nothing here can fail. Kept placements keep their
 * uuid, so a cloud sync of an edited pair diffs as an edit rather than a delete and an add.
 */
internal fun applyExtraPlacements(
    patch: DaoFixturePatch,
    inputs: List<PlacementInput>,
    riggings: Map<String, DaoRigging>,
) {
    val existing = extraPlacementsOf(patch).associateBy { it.uuid }
    val kept = mutableSetOf<UUID>()
    inputs.forEachIndexed { index, input ->
        val row = input.uuid?.let { existing[it] }?.also { kept.add(it.uuid) }
            ?: DaoFixturePatchPlacement.new { fixturePatch = patch }
        row.label = input.label
        row.rigging = input.riggingUuid?.let { riggings[it] }
        row.stageX = input.stageX
        row.stageY = input.stageY
        row.stageZ = input.stageZ
        row.baseYawDeg = input.baseYawDeg
        row.basePitchDeg = input.basePitchDeg
        row.baseRollDeg = input.baseRollDeg
        row.lengthM = input.lengthM
        row.focus = input.focus
        row.fittedMedia = input.media
        row.sortOrder = index
    }
    existing.values.filter { it.uuid !in kept }.forEach { it.delete() }
}

internal fun DaoFixturePatchPlacement.toDto(): PatchPlacementDto = focus.let { f -> PatchPlacementDto(
    uuid = uuid.toString(),
    label = label,
    riggingUuid = rigging?.uuid?.toString(),
    stageX = stageX,
    stageY = stageY,
    stageZ = stageZ,
    baseYawDeg = baseYawDeg,
    basePitchDeg = basePitchDeg,
    baseRollDeg = baseRollDeg,
    lengthM = lengthM,
    lanternType = f.lanternType,
    zoomDeg = f.zoomDeg,
    lampRotationDeg = f.lampRotationDeg,
    shutters = f.shutters,
    gateRotationDeg = f.gateRotationDeg,
    iris = f.iris,
    focusSoftness = f.focusSoftness,
    media = fittedMedia,
) }

/**
 * The placement's position is past the end of its rigging — the bulk route's non-fatal
 * warning, for extra placements. Null when there is nothing to say.
 */
internal fun DaoFixturePatchPlacement.offTheEndWarning(patchKey: String): String? {
    val rig = rigging ?: return null
    val localX = stageX ?: return null
    val half = (rig.lengthM ?: 0.0) / 2.0
    if (half <= 0.0 || kotlin.math.abs(localX) <= half + 1e-6) return null
    val name = label?.let { "$patchKey ($it)" } ?: patchKey
    return "$name: ${"%.2f".format(localX)} m is past the end of ${rig.name} (±${"%.2f".format(half)} m)"
}
