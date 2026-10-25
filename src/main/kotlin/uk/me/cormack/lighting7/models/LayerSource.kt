package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Which library entity a layer applies.
 *
 * Session 3 makes a layer's referent polymorphic. Before it, "layer" and "Look layer" were the same
 * phrase and the fields were `lookId` / `lookUuid` / `lookName`; a layer can now track a **template**
 * instead, and the two are genuinely different things rather than one thing with a flag — see
 * [DaoPalettes].
 */
enum class LayerSourceKind {
    LOOK,
    PALETTE,
}

/**
 * What a layer points at: a kind, and the entity's identity in all three forms the desk needs.
 *
 * **One nested value rather than three flat fields**, and that is deliberate. The rename from
 * `lookId`/`lookUuid`/`lookName` could have been done by adding a `sourceKind` beside them, which
 * is a smaller diff — but it leaves a field called `lookName` holding a template's name, and the
 * compiler cannot then find the call sites that need to care. Collapsing the three into one object
 * makes every reader visit exactly once and makes a half-finished migration impossible to compile.
 *
 * All three fields earn their place: [id] addresses REST paths and the FK, [uuid] is the portable
 * identity (int PKs are re-minted on import, so a stored reference is always a uuid) and [name] is
 * what provenance shows an operator asking "why is this fixture this colour?".
 */
data class LayerSource(
    val kind: LayerSourceKind,
    val id: Int,
    val uuid: UUID,
    val name: String,
) {
    val isPalette: Boolean get() = kind == LayerSourceKind.PALETTE

    companion object {
        fun look(id: Int, uuid: UUID, name: String) = LayerSource(LayerSourceKind.LOOK, id, uuid, name)
        fun palette(id: Int, uuid: UUID, name: String) =
            LayerSource(LayerSourceKind.PALETTE, id, uuid, name)
    }

    /** The wire shape: a uuid travels as a String, matching every other DTO in this codebase. */
    fun toDto() = LayerSourceDto(kind.name, id, uuid.toString(), name)
}

/**
 * [LayerSource] on the wire.
 *
 * A separate type rather than `@Serializable` on the internal one, following `IncludedTarget` /
 * `IncludedTargetDto`: a uuid is a `UUID` in memory and a `String` in JSON throughout this codebase,
 * and int PKs are re-minted on import so the uuid is the field a stored reference must use.
 */
@Serializable
data class LayerSourceDto(
    /** `LOOK` or `PALETTE`. */
    val kind: String,
    val id: Int,
    val uuid: String,
    val name: String,
)

/**
 * The targets a layer of [source] holds: a Look's none (absolute-looks plan D1 — a Look lands whole),
 * a palette's as given. Every boundary that takes a layer from outside refuses a Look's targets
 * ([LookLayerTargetedException]); this keeps the programmer's own stack to the same rule.
 */
internal fun layerTargets(source: LayerSource, targets: List<CueTargetDto>): List<CueTargetDto> =
    if (source.kind == LayerSourceKind.LOOK) emptyList() else targets

/**
 * A stored write that would give a Look's layer targets, refused by name (absolute-looks plan P2): a
 * Look lands whole, and a selection is a palette's. An [IllegalArgumentException], so every route and
 * tool that already answers one with a 400 keeps doing so; `plugins/ErrorHandling.kt` adds the code.
 */
class LookLayerTargetedException(message: String) : IllegalArgumentException(message) {
    companion object {
        const val CODE = "LOOK_LAYER_TARGETED"
    }
}

/** Why a Look's layer at [where] may not name targets — the message [LookLayerTargetedException] carries. */
internal fun lookLayerTargetedMessage(where: String): String =
    "$where: a Look lands whole, so its layer names no targets (${LookLayerTargetedException.CODE}) — to apply to a selection, use a palette"

/** Every Look layer in [layers] that names targets, each as [lookLayerTargetedMessage]. */
internal fun lookLayerTargetedProblems(layers: List<CueLayerDto>, where: (Int) -> String = { "layers[$it]" }): List<String> =
    layers.withIndex()
        .filter { (_, layer) -> layer.lookId != null && layer.targets.isNotEmpty() }
        .map { (index, _) -> lookLayerTargetedMessage(where(index)) }

/** Throws [LookLayerTargetedException] naming every Look layer in [layers] that names targets, or returns. */
internal fun checkLookLayersUntargeted(layers: List<CueLayerDto>) {
    val problems = lookLayerTargetedProblems(layers)
    if (problems.isNotEmpty()) throw LookLayerTargetedException(problems.joinToString("; "))
}
