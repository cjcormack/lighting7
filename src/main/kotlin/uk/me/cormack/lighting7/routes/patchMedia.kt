package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.fixture.media.FittedMedia

/**
 * The one rule every patch write path runs for **fitted media** (fixture optics plan session 3) —
 * `POST`, `PUT`, the bulk route and the MCP tools, as [resolvePatchFocus] is for the focus fields.
 *
 * [patchMedia] is the patch's `media` as the write sends it, checked only when [patchMediaSent]: an
 * absent key keeps what is stored and a sent null clears it, so a write that does not touch media
 * never trips on what an import stored. [placements] are the `extraPlacements` the write sends, or
 * null when it sends none; within an entry `media` is plain, as every placement field is, so a
 * placement absent from its entry fits nothing of its own.
 *
 * Every problem — the patch's and each placement's — is reported together, each by its path.
 * Answers null when the write is fine.
 */
internal fun patchMediaRefusal(
    typeKey: String,
    patchMedia: FittedMedia?,
    patchMediaSent: Boolean,
    placements: List<PlacementInput>?,
): String? {
    val problems = buildList {
        if (patchMediaSent) patchMedia?.let { addAll(it.typeProblems(typeKey)) }
        placements?.forEachIndexed { i, p -> p.media?.let { addAll(it.typeProblems(typeKey, "extraPlacements[$i].media")) } }
    }
    return problems.takeIf { it.isNotEmpty() }?.joinToString("; ")
}
