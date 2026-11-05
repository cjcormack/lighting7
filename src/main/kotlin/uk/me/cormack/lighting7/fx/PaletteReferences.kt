package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A **palette reference** as a value (palette-references plan D1–D3): `pal:{uuid}` held where a
 * literal can be — a programmer entry, a cue's own row, a Look row — naming a value palette of the
 * same project, and resolved per head through [PaletteResolver].
 *
 * Composition never sees one. Each reader resolves the reference on the head it lands on and hands
 * the cook or the store the literal; [CueAssignmentResolver.parseAssignmentValue] still answers null
 * for a reference, so nothing reads one as a colour.
 *
 * ## How one head resolves
 *
 * The palette's row **for that head** — a fixture row on it, a group row whose group holds it, or a
 * row on its parent that [PaletteFanOut] lands on it — else its generic row, matched to the
 * attribute by where the row resolves on this head (a colour row on the MAC 250 resolves to its
 * `colourWheel`) or by the row's own name. A colour row brings the palette's colour family with it:
 * a head that cannot serve those rows as a set resolves none of them
 * ([PaletteResolver.unmetColourRequirement]), as a press does.
 *
 * ## A reference that no longer resolves
 *
 * Keeps the value it last resolved to on this desk run, marked with the reason (D3). There is no
 * stored copy of it: a desk started with a palette that already lacks the head's row puts nothing
 * there, which is what a palette layer did.
 */
class PaletteReferences(
    private val fixtures: () -> Fixtures,
    private val palettes: (UUID) -> PaletteSnapshot?,
) {
    /** One head's answer. [value] is null only when it is unresolved and nothing was remembered. */
    data class Resolved(
        val value: CueAssignmentResolver.AttributeValue?,
        /** The head's property the value is for: the row's, or the one the palette's row resolved to. */
        val attribute: String,
        /** Why the palette does not resolve here now, or null when it does. */
        val unresolved: String? = null,
    )

    private data class LastKey(val palette: UUID, val head: String, val attribute: String)

    private val last = ConcurrentHashMap<LastKey, Pair<String, CueAssignmentResolver.AttributeValue>>()

    /** Resolve [paletteUuid] for [attribute] on [head], remembering the answer for D3. */
    fun resolve(head: GroupableFixture, attribute: String, paletteUuid: UUID): Resolved {
        val canonical = canonicalAttributeName(attribute)
        val key = LastKey(paletteUuid, head.targetKey, canonical)
        val fresh = resolveOn(fixtures(), head, canonical, palettes(paletteUuid))
        if (fresh.value != null && fresh.unresolved == null) {
            last[key] = fresh.attribute to fresh.value
            return fresh
        }
        val kept = last[key] ?: return fresh
        return Resolved(kept.second, kept.first, fresh.unresolved)
    }

    /**
     * [head] and what the reference resolves to there — or, where [head] is a multi-head fixture that
     * takes nothing of the palette itself, each of its cells that does, as a palette's rows fan onto
     * a parent's cells (`PaletteFanOut`). A head nothing reaches answers itself, unresolved.
     */
    fun resolveFanned(head: GroupableFixture, attribute: String, paletteUuid: UUID): List<Pair<GroupableFixture, Resolved>> {
        val own = resolve(head, attribute, paletteUuid)
        if (own.value != null || head !is MultiElementFixture<*>) return listOf(head to own)
        val cells = head.elements.map { cell -> cell to resolve(cell, attribute, paletteUuid) }.filter { it.second.value != null }
        return cells.ifEmpty { listOf(head to own) }
    }

    /**
     * Whether [value], a reference on [target]'s [attribute], resolves to something on any head the
     * target reaches — a row that lands on stage under another property than the one it is stored
     * under (a colour wheel) is not dead.
     */
    fun landsOn(fixtures: Fixtures, target: TargetRef, attribute: String, value: String): Boolean {
        val paletteUuid = uuidOf(value) ?: return false
        val heads: List<GroupableFixture> = when (target) {
            is TargetRef.Fixture -> listOfNotNull(runCatching { fixtures.untypedGroupableFixture(target.key) }.getOrNull())
            is TargetRef.Group -> runCatching { fixtures.untypedGroup(target.key).fixtures }.getOrNull().orEmpty()
        }
        return heads.any { head -> resolveFanned(head, attribute, paletteUuid).any { it.second.value != null } }
    }

    /** The palette [paletteUuid] names, for a caller that reports its name. */
    fun palette(paletteUuid: UUID): PaletteSnapshot? = palettes(paletteUuid)

    companion object {
        /** The uuid [value] references, or null when it is a literal (or a malformed reference). */
        fun uuidOf(value: String): UUID? = parsePaletteColourRefUuid(value)

        /** Whether [value] is spelled as a reference at all, well formed or not. */
        fun isReference(value: String): Boolean = isPaletteColourRef(value)

        /** The stored spelling of a reference to [paletteUuid]. */
        fun valueOf(paletteUuid: UUID): String = serializePaletteColourRef(paletteUuid)

        /**
         * One head's resolution against [snapshot], with nothing remembered. Pure, so a write
         * boundary and a test can ask it without a show.
         */
        fun resolveOn(
            fixtures: Fixtures,
            head: GroupableFixture,
            attribute: String,
            snapshot: PaletteSnapshot?,
        ): Resolved {
            val canonical = canonicalAttributeName(attribute)
            fun unresolved(reason: String) = Resolved(null, canonical, reason)
            if (snapshot == null) return unresolved("names no palette")
            if (snapshot.effect != null) return unresolved("'${snapshot.name}' is an FX palette")
            val media = PaletteFanOut.mediaOf(head, fixtures::fittedMediaFor)

            data class Candidate(val row: PaletteRowEntry, val intent: PaletteIntent, val resolution: PaletteResolver.Resolution)

            fun candidates(rows: List<PaletteRowEntry>): List<Candidate> = rows.mapNotNull { row ->
                val intent = parsePaletteIntent(row.value) ?: return@mapNotNull null
                Candidate(row, intent, PaletteResolver.resolve(head, row.attribute, intent, media))
            }

            fun matching(found: List<Candidate>): Candidate? =
                found.firstOrNull { it.resolution.isSupported && canonicalAttributeName(it.resolution.attribute) == canonical }
                    ?: found.firstOrNull { canonicalAttributeName(it.row.attribute) == canonical }

            val bound = snapshot.rows.filter { it.target != null && rowReaches(fixtures, it, head) }
            val generic = snapshot.rows.filter { it.target == null }
            val boundMatch = matching(candidates(bound))
            val chosen = boundMatch ?: matching(candidates(generic))
                ?: return unresolved("'${snapshot.name}' has no row for ${head.targetKey}")

            if (PaletteAttribute.ofOrNull(chosen.row.attribute)?.family == AttributeFamily.COLOUR) {
                val set = if (boundMatch != null) bound else generic
                val colourRows = set.mapNotNull { row ->
                    if (PaletteAttribute.ofOrNull(row.attribute)?.family != AttributeFamily.COLOUR) return@mapNotNull null
                    parsePaletteIntent(row.value)?.let { row.attribute to it }
                }
                when (val requirement = PaletteResolver.unmetColourRequirement(head, colourRows, media)) {
                    is PaletteResolver.ColourRequirement.NotACandidate ->
                        return unresolved("${head.targetKey} cannot take '${snapshot.name}' (${requirement.reason})")
                    is PaletteResolver.ColourRequirement.Unmet ->
                        return unresolved("${head.targetKey} cannot take '${snapshot.name}' (${requirement.reason})")
                    PaletteResolver.ColourRequirement.Met -> {}
                }
            }
            val value = chosen.resolution.value
                ?: return unresolved("${head.targetKey} cannot take '${snapshot.name}' (${chosen.resolution.note.reason()})")
            return Resolved(value, canonicalAttributeName(chosen.resolution.attribute))
        }

        /** Whether a per-fixture row lands on [head]: its own, its group's, or its parent's fanned onto it. */
        private fun rowReaches(fixtures: Fixtures, row: PaletteRowEntry, head: GroupableFixture): Boolean =
            when (val target = row.target) {
                null -> false
                is TargetRef.Fixture -> target.key == head.targetKey || run {
                    val parent = (head as? FixtureElement<*>)?.parentFixture ?: return@run false
                    parent.key == target.key &&
                        PaletteFanOut.headsFor(parent, row.attribute, fixtures::fittedMediaFor).any { it.targetKey == head.targetKey }
                }
                is TargetRef.Group -> runCatching { fixtures.untypedGroup(target.key).fixtures }.getOrNull()
                    .orEmpty().any { it.targetKey == head.targetKey }
            }

        private fun PaletteResolver.Note.reason(): String = when (this) {
            is PaletteResolver.Note.Unsupported -> reason
            else -> toString()
        }
    }
}

/**
 * A stored or programmer value naming something a reference cannot (palette-references plan D1): an
 * unknown or malformed palette, one of another project, an FX palette, a palette of another family
 * than the row, or a reference in a palette's own rows. An [IllegalArgumentException], so a route
 * that answers one with a 400 keeps doing so; `plugins/ErrorHandling.kt` adds the code.
 */
class PaletteReferenceInvalidException(message: String) : IllegalArgumentException(message) {
    companion object {
        const val CODE = "PALETTE_REFERENCE_INVALID"
    }
}
