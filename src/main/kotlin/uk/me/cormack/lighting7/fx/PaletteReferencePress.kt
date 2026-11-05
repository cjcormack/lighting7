package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID

/**
 * A **value** palette's press in the programmer (palette-references plan D10–D12): it writes and
 * releases references, never a layer. [ProgrammerLayerStack.toggle] hands every value palette here,
 * so every door that presses one writes references.
 *
 * - **Where it lands.** Each pressed head (a group's members, under the group's name) takes a
 *   reference on each attribute the palette's rows name there, keyed by the property it lands on
 *   ([PaletteReferences.resolve]). A per-fixture palette lands only on the heads it has rows for. A
 *   multi-head fixture that cannot take an attribute itself takes it on each cell that can.
 * - **Off** when every key it lands on already shows a reference to it: every slot on the pressed
 *   heads referencing it is released.
 * - **On** writes the references as touched `WEB` values at the press's fade (the palette's own when
 *   the press carries none), over whatever the
 *   keys held. It then releases, on the heads it landed on, the references to the other value
 *   palettes of its family on the attributes it does not name (apply-model D2), and, on every
 *   pressed head, a solo bank's palette siblings' references on any attribute.
 *
 * A palette covers a head ([coveredHeads], the pads' rings) when every key it lands on there shows
 * a reference to it.
 */
internal class PaletteReferencePress(
    private val fixtures: () -> Fixtures,
    private val paletteRegistry: () -> PaletteRegistry,
    private val references: () -> PaletteReferences,
    private val store: ProgrammerStore,
    private val writer: () -> ProgrammerWriter,
    private val rules: LayerPressRules,
) {
    /** One key a press writes. */
    data class Landing(
        val head: GroupableFixture,
        val attribute: String,
        val value: CueAssignmentResolver.AttributeValue,
        val sourceGroup: String?,
    )

    /**
     * What a press of one palette would do: the keys it lands on, the heads it was pressed on (cells
     * included), and D14's two lists — the heads a per-fixture palette holds that the press left out
     * ([omitted]) and the pressed heads it lands nothing on ([notCovered]).
     */
    data class Plan(
        val landings: List<Landing>,
        val pressedHeads: Set<String>,
        val omitted: List<String>,
        val notCovered: List<String>,
    ) {
        val landedHeads: Set<String> get() = landings.mapTo(LinkedHashSet()) { it.head.targetKey }
    }

    /** A value palette's snapshot, or null for an FX palette, a missing one, or one not loaded. */
    fun valuePalette(uuid: UUID): PaletteSnapshot? = paletteRegistry().snapshot(uuid)?.takeIf { it.effect == null }

    fun plan(snapshot: PaletteSnapshot, targets: List<CueTargetDto>, mask: Set<AttributeFamily>?): Plan {
        val uuid = snapshot.paletteUuid
        val pressTargets = rules.pressTargets(uuid, targets)
        val rig = fixtures()
        val attributes = snapshot.rows
            .filter { row -> mask == null || PaletteAttribute.ofOrNull(row.attribute)?.family in mask }
            .map { canonicalAttributeName(it.attribute) }
            .distinct()

        val heads = LinkedHashMap<String, Pair<GroupableFixture, String?>>()
        val missing = ArrayList<String>()
        for (target in pressTargets) {
            when (val ref = TargetRef.ofOrNull(target.type, target.key)) {
                is TargetRef.Group -> {
                    val members = runCatching { rig.untypedGroup(ref.key).fixtures }.getOrNull().orEmpty()
                    if (members.isEmpty()) missing += ref.key
                    for (member in members) heads.putIfAbsent(member.targetKey, member to ref.key)
                }
                is TargetRef.Fixture -> {
                    val head = runCatching { rig.untypedGroupableFixture(ref.key) }.getOrNull()
                    if (head == null) missing += ref.key else heads.putIfAbsent(head.targetKey, head to null)
                }
                null -> missing += target.key
            }
        }

        val landings = ArrayList<Landing>()
        val seen = HashSet<Pair<String, String>>()
        val pressed = LinkedHashSet<String>()
        val notCovered = ArrayList<String>(missing)
        for ((head, sourceGroup) in heads.values) {
            pressed += head.targetKey
            var landedHere = false
            for (attribute in attributes) {
                val own = landingOn(head, attribute, uuid, sourceGroup)
                val found = if (own != null) listOf(own) else cellLandings(head, attribute, uuid)
                for (landing in found) {
                    pressed += landing.head.targetKey
                    if (seen.add(landing.head.targetKey to landing.attribute)) landings += landing
                    landedHere = true
                }
            }
            if (!landedHere) notCovered += head.targetKey
        }

        val omitted = if (targets.isEmpty()) emptyList() else rules.omittedHeads(uuid, pressTargets)
        return Plan(landings, pressed, omitted, notCovered)
    }

    private fun landingOn(head: GroupableFixture, attribute: String, uuid: UUID, sourceGroup: String?): Landing? {
        val resolved = references().resolve(head, attribute, uuid)
        val value = resolved.value?.takeIf { resolved.unresolved == null } ?: return null
        return Landing(head, resolved.attribute, value, sourceGroup)
    }

    /** [head]'s cells' landings for [attribute], as [PaletteReferences.resolveFanned] fans a parent. */
    private fun cellLandings(head: GroupableFixture, attribute: String, uuid: UUID): List<Landing> =
        references().resolveFanned(head, attribute, uuid).mapNotNull { (cell, resolved) ->
            val value = resolved.value?.takeIf { cell !== head && resolved.unresolved == null } ?: return@mapNotNull null
            Landing(cell, resolved.attribute, value, null)
        }

    /** Whether [plan]'s palette already shows on every key it lands on. */
    fun isOn(uuid: UUID, plan: Plan): Boolean =
        plan.landings.isNotEmpty() && plan.landings.all { topReference(it.head.targetKey, it.attribute) == uuid }

    private fun topReference(fixtureKey: String, attribute: String): UUID? =
        (store.get(fixtureKey, attribute)?.value as? ProgrammerValue.Reference)?.paletteUuid

    /** What [press] did; [released] counts the other palettes whose references it took off. */
    data class Outcome(val applied: Boolean, val released: Int, val plan: Plan)

    fun press(
        snapshot: PaletteSnapshot,
        targets: List<CueTargetDto>,
        mask: Set<AttributeFamily>?,
        siblings: Set<UUID>,
        fadeMs: Long,
        /** The press is on its off arm whatever the references say: a layer of the palette covers the pressed heads. */
        alreadyOn: Boolean = false,
    ): Outcome {
        val uuid = snapshot.paletteUuid
        val plan = plan(snapshot, targets, mask)
        if (alreadyOn || isOn(uuid, plan)) {
            releaseWhere(plan.pressedHeads + plan.landedHeads, fadeMs) { _, _, ref -> ref == uuid }
            return Outcome(applied = false, released = 0, plan = plan)
        }

        // A palette whose reference the press writes over is taken off those keys too.
        val overwritten = plan.landings.mapNotNullTo(HashSet()) { topReference(it.head.targetKey, it.attribute)?.takeIf { ref -> ref != uuid } }
        if (plan.landings.isNotEmpty()) {
            writer().writeAttributes(
                ProgrammerOwner.WEB,
                plan.landings.map { ProgrammerWriter.AttributeWrite(it.head, it.attribute, it.value, it.sourceGroup, reference = uuid) },
                // A press with no programmer fade lands at the palette's own, as its layer did.
                fadeMs = fadeMs.takeIf { it > 0 } ?: snapshot.fadeDurationMs ?: 0,
            )
        }

        val family = snapshot.familyOf()
        val named = plan.landings.groupBy({ it.head.targetKey }, { it.attribute })
        val siblingPalettes = siblings.filterTo(HashSet()) { it != uuid }
        val released = HashSet(overwritten)
        released += releaseWhere(plan.pressedHeads + plan.landedHeads, fadeMs) { fixtureKey, attribute, ref ->
            when {
                ref == uuid -> false
                ref in siblingPalettes -> true
                fixtureKey !in named || attribute in named.getValue(fixtureKey) -> false
                else -> family != null && valuePalette(ref)?.familyOf() == family
            }
        }
        return Outcome(applied = true, released = released.size, plan = plan)
    }

    /**
     * Every slot referencing one of [paletteUuids] released, on every head when [onHeads] is null, at
     * [fadeMs]. Answers the palettes it took off. A Look press's and a cue press's solo siblings.
     */
    fun releaseAll(paletteUuids: Set<UUID>, onHeads: Set<String>? = null, fadeMs: Long = 0): Set<UUID> {
        if (paletteUuids.isEmpty()) return emptySet()
        val heads = onHeads ?: store.entries().mapTo(HashSet()) { it.fixtureKey }
        return releaseWhere(heads, fadeMs) { _, _, ref -> ref in paletteUuids }
    }

    /** Clear every slot on [heads] whose value references a palette [release] picks. Answers the palettes released. */
    private fun releaseWhere(
        heads: Set<String>,
        fadeMs: Long,
        release: (fixtureKey: String, attribute: String, paletteUuid: UUID) -> Boolean,
    ): Set<UUID> {
        val byOwner = LinkedHashMap<ProgrammerOwner, MutableList<Pair<GroupableFixture, String>>>()
        val released = HashSet<UUID>()
        val rig = fixtures()
        for (entry in store.entries()) {
            if (entry.fixtureKey !in heads) continue
            val head by lazy { runCatching { rig.untypedGroupableFixture(entry.fixtureKey) }.getOrNull() }
            for (slot in entry.slots) {
                val ref = (slot.value as? ProgrammerValue.Reference)?.paletteUuid ?: continue
                if (!release(entry.fixtureKey, entry.attribute, ref)) continue
                val fixture = head
                if (fixture == null) {
                    store.clear(slot.owner, entry.fixtureKey, entry.attribute)
                } else {
                    byOwner.getOrPut(slot.owner) { ArrayList() } += fixture to entry.attribute
                }
                released += ref
            }
        }
        for ((owner, clears) in byOwner) writer().clearAttributes(owner, clears, fadeMs)
        return released
    }

    /**
     * Each value palette some key's winning slot references, mapped to the heads it covers (D12): a
     * head, or a multi-head fixture whose cells it lands on, every key of which shows a reference to
     * it. A head it reaches only in part is left out, and the rings read it as partial.
     */
    fun coveredHeads(): Map<PaletteSnapshot, Set<CueTargetDto>> {
        val referenced = LinkedHashMap<UUID, MutableSet<String>>()
        for (entry in store.entries()) {
            val ref = (entry.slots.firstOrNull()?.value as? ProgrammerValue.Reference)?.paletteUuid ?: continue
            referenced.getOrPut(ref) { LinkedHashSet() } += entry.fixtureKey
        }
        if (referenced.isEmpty()) return emptyMap()
        val rig = fixtures()
        val out = LinkedHashMap<PaletteSnapshot, Set<CueTargetDto>>()
        for ((uuid, keys) in referenced) {
            val snapshot = valuePalette(uuid) ?: continue
            val attributes = snapshot.rows.map { canonicalAttributeName(it.attribute) }.distinct()
            val candidates = LinkedHashSet<GroupableFixture>()
            for (key in keys) {
                val head = runCatching { rig.untypedGroupableFixture(key) }.getOrNull() ?: continue
                candidates += head
                (head as? FixtureElement<*>)?.parentFixture?.let { candidates += it }
            }
            val covered = candidates.filter { head ->
                val landings = attributes.flatMap { attribute ->
                    landingOn(head, attribute, uuid, null)?.let(::listOf) ?: cellLandings(head, attribute, uuid)
                }
                landings.isNotEmpty() && landings.all { topReference(it.head.targetKey, it.attribute) == uuid }
            }.mapTo(LinkedHashSet()) { CueTargetDto(TargetRef.Fixture.TYPE, it.targetKey) }
            if (covered.isNotEmpty()) out[snapshot] = covered
        }
        return out
    }
}
