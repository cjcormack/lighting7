package uk.me.cormack.lighting7.routes

import kotlinx.serialization.json.JsonObject
import uk.me.cormack.lighting7.fixture.FixtureKind
import uk.me.cormack.lighting7.fixture.lantern.LanternFocus
import uk.me.cormack.lighting7.fixture.lantern.LanternLibrary
import uk.me.cormack.lighting7.fixture.lantern.effectiveKind
import uk.me.cormack.lighting7.fixture.lantern.focus
import uk.me.cormack.lighting7.models.DaoFixturePatchPlacement

/**
 * A patch's focus and kind as one write leaves them (stage-view plan session 7). [focus] is what
 * the patch's seven focus columns hold afterwards and [kindOverride] its `kind_override`.
 */
internal data class PatchFocusWrite(val focus: LanternFocus, val kindOverride: String?)

/**
 * The one rule every patch write path runs — `POST`, `PUT`, the bulk route and the MCP tools — for
 * the focus fields and the kind:
 *
 * - [stored] is overlaid with the keys the write carries ([sent] lists them; [parsed] holds their
 *   values, range-checked already), so an absent key is unchanged and a null clears.
 * - **`kind_override` is derived from the lantern.** With a lantern named, the patch's kind is that
 *   lantern's family's, whatever it was; a write naming a *different* kind beside a lantern is
 *   refused rather than silently lost. With none named the kind is the patch's own, as it was —
 *   except that a write **clearing** the lantern without naming a kind clears the kind too, since
 *   the stored one was the lantern's, derived.
 *   Deriving it at the write keeps every reader that asks only for a kind — `describe_rig`'s mover
 *   test, the 2D views, the filters — agreeing with the body the view draws.
 * - **A stored zoom the lantern cannot take is cleared** by any focus or kind write that does not
 *   send a zoom — typically one that changed the lantern (or the kind its default follows), since
 *   the knob belonged to the old lantern, but equally an import's zoom this desk's library cannot
 *   place, which would otherwise refuse every later edit. A valid stored zoom is kept. A zoom the
 *   write *sends* is checked, never adjusted. The patch's inheriting placements get the same rule
 *   ([clearStaleInheritedZooms]) when the write does not send them.
 * - Then the type's and the library's rules ([LanternFocus.typeProblems]), only when the write
 *   touches a focus key — so an edit of a patch's name never trips on what an import stored.
 *
 * Answers the write, or the refusal's message.
 */
internal fun resolvePatchFocus(
    typeKey: String,
    stored: LanternFocus,
    storedKindOverride: String?,
    sent: Set<String>,
    parsed: LanternFocus,
    kindOverrideSent: Boolean,
    kindOverride: String?,
): Result<PatchFocusWrite> {
    val touchesFocus = sent.any { it in LanternFocus.KEYS }
    if (!touchesFocus && !kindOverrideSent) return Result.success(PatchFocusWrite(stored, storedKindOverride))

    var focus = if (touchesFocus) stored.overlay(sent, parsed) else stored
    val lantern = LanternLibrary.byId(focus.lanternType)
    val kind = if (lantern != null) {
        if (kindOverrideSent && kindOverride != null && kindOverride != lantern.kind.name) {
            return Result.failure(
                IllegalArgumentException(
                    "kindOverride is derived from the lantern: ${lantern.name} ('${lantern.id}') is ${lantern.kind.name}; " +
                        "clear lanternType to set another kind",
                ),
            )
        }
        lantern.kind.name
    } else if (kindOverrideSent) {
        kindOverride
    } else if ("lanternType" in sent && stored.lanternType != null) {
        // The lantern was cleared and the write names no kind: the stored kind was the old
        // lantern's, derived, so it goes with it and the type's own kind shows through again.
        null
    } else {
        storedKindOverride
    }

    val drawnKind = effectiveKind(typeKey, kind, focus.lanternType) ?: FixtureKind.GENERIC
    if ("zoomDeg" !in sent && focus.zoomProblem(drawnKind) != null) focus = focus.copy(zoomDeg = null)
    if (touchesFocus) {
        val problems = focus.typeProblems(typeKey, drawnKind)
        if (problems.isNotEmpty()) return Result.failure(IllegalArgumentException(problems.joinToString("; ")))
    }
    return Result.success(PatchFocusWrite(focus, kind))
}

/** [resolvePatchFocus] for a raw JSON body — `PUT /patches/{id}` and a bulk entry. */
internal fun resolvePatchFocus(
    typeKey: String,
    stored: LanternFocus,
    storedKindOverride: String?,
    body: JsonObject,
    parsed: LanternFocus,
    normalisedKindOverride: String?,
): Result<PatchFocusWrite> = resolvePatchFocus(
    typeKey, stored, storedKindOverride, body.keys, parsed, "kindOverride" in body, normalisedKindOverride,
)

/**
 * What a patch's placements refuse, given the patch's type, its kind and its lantern **as the write
 * leaves them** — a placement that names no lantern of its own takes the patch's. Null when every
 * one is fine.
 */
internal fun placementFocusRefusal(
    typeKey: String,
    patchKindOverride: String?,
    patchLanternType: String?,
    placements: List<PlacementInput>,
): String? {
    val kind = effectiveKind(typeKey, patchKindOverride, patchLanternType) ?: FixtureKind.GENERIC
    placements.forEachIndexed { i, p ->
        if (p.focus.isEmpty) return@forEachIndexed
        val problems = p.focus.typeProblems(typeKey, kind, inheritedLantern = patchLanternType)
        if (problems.isNotEmpty()) return "extraPlacements[$i]: ${problems.joinToString("; ")}"
    }
    return null
}

/**
 * The stored placements that name no lantern of their own inherit the patch's — so when a write
 * changes the patch's lantern (or its kind) without sending `extraPlacements`, a placement's zoom
 * the patch's lantern *as the write leaves it* cannot take is cleared, by [resolvePatchFocus]'s own
 * rule. Otherwise the next write that re-sends the placements — the patch editor's Save, the Focus
 * tab's — would be refused for a zoom nobody sent. Call inside the write's transaction.
 */
internal fun clearStaleInheritedZooms(
    typeKey: String,
    patchKindOverride: String?,
    patchLanternType: String?,
    placements: List<DaoFixturePatchPlacement>,
) {
    val kind = effectiveKind(typeKey, patchKindOverride, patchLanternType) ?: FixtureKind.GENERIC
    for (placement in placements) {
        if (placement.lanternType != null || placement.zoomDeg == null) continue
        if (placement.focus.zoomProblem(kind, inheritedLantern = patchLanternType) != null) placement.zoomDeg = null
    }
}
