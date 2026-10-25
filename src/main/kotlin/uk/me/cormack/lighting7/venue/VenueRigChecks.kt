package uk.me.cormack.lighting7.venue

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.ai.RigKeyReferences
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.models.AddressFacet
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoRiggings
import uk.me.cormack.lighting7.models.DaoShowOverride
import uk.me.cormack.lighting7.models.DaoShowOverrides
import uk.me.cormack.lighting7.models.DaoStageElements
import uk.me.cormack.lighting7.models.DaoStageRegions
import uk.me.cormack.lighting7.models.DaoStageViewpoints
import uk.me.cormack.lighting7.models.EffectivePatch
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.models.RigOwner
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.decodeFacet
import uk.me.cormack.lighting7.models.encodeFacet
import uk.me.cormack.lighting7.models.entriesOfCue
import uk.me.cormack.lighting7.routes.deleteOwnPatch
import java.util.UUID

/**
 * The venue's guards on a show's rig, run after a change that puts it on a version — Update's
 * (`VenueUpdate`) and Attach's (`VenueAttach`): a key, head number or name a show record shares with a
 * venue record ([clashesOf], a blocker), an address overlap between something the show owns and a
 * venue patch ([spansOf], [overlapPairs], [decisionOf], [applyDecisions]: P8's decision), and the keys
 * the change takes out of the rig that the show's rows still name ([deadReferences]). Inside the
 * caller's transaction.
 */
internal object VenueRigChecks {

    // ── Clashes (blockers) ──────────────────────────────────────────────────────

    /** A show record holding an identity a venue record also holds: its kind, the show's key or name, and both records. */
    data class Clash(
        val kind: AttachKind,
        val name: String,
        val detail: String,
        val mine: AttachRecordDto,
        val venue: AttachRecordDto,
        /** A head number, not a key or name. */
        val head: Boolean = false,
    )

    /**
     * The show's own records holding an identity a venue record of [snapshot] (version [version], as
     * `v8`) also holds in [rig] — a patch's key or head number, any other kind's name: keys and names
     * are one namespace (§3.3), and a clash cannot be kept (P8).
     */
    fun clashesOf(rig: RigScope, snapshot: EntityID<Int>, version: String): List<Clash> {
        val out = mutableListOf<Clash>()
        val venuePatches = rig.patches(DaoFixturePatches.project eq snapshot).toList()
        val own = rig.own
        fun record(p: DaoFixturePatch) = AttachRecordDto(p.uuid.toString(), p.displayName, p.key)
        for (mine in own.patches().toList()) {
            venuePatches.firstOrNull { it.key == mine.key }?.let {
                out += Clash(
                    AttachKind.PATCH, mine.key,
                    "your ${mine.displayName} has the key '${mine.key}', which $version's ${it.displayName} takes — rename yours first",
                    record(mine), record(it),
                )
            }
            val n = mine.headNumber ?: continue
            venuePatches.firstOrNull { it.headNumber == n }?.let {
                out += Clash(
                    AttachKind.PATCH, mine.key,
                    "your ${mine.displayName} has head number $n, which $version's ${it.displayName} takes — renumber yours first",
                    record(mine), record(it), head = true,
                )
            }
        }
        fun names(kind: AttachKind, venue: List<Pair<UUID, String>>, mine: List<Pair<UUID, String>>) {
            val taken = venue.associate { (uuid, name) -> name to uuid }
            for ((uuid, name) in mine) {
                val theirs = taken[name] ?: continue
                out += Clash(
                    kind, name, "your ${kind.label} '$name' has a name $version's ${kind.label} takes — rename yours first",
                    AttachRecordDto(uuid.toString(), name), AttachRecordDto(theirs.toString(), name),
                )
            }
        }
        names(AttachKind.GROUP, rig.groups(DaoFixtureGroups.project eq snapshot).map { it.uuid to it.name }, own.groups().map { it.uuid to it.name })
        names(AttachKind.RIGGING, rig.riggings(DaoRiggings.project eq snapshot).map { it.uuid to it.name }, own.riggings().map { it.uuid to it.name })
        names(AttachKind.ELEMENT, rig.stageElements(DaoStageElements.project eq snapshot).map { it.uuid to it.name }, own.stageElements().map { it.uuid to it.name })
        names(AttachKind.REGION, rig.stageRegions(DaoStageRegions.project eq snapshot).map { it.uuid to it.name }, own.stageRegions().map { it.uuid to it.name })
        names(AttachKind.VIEWPOINT, rig.stageViewpoints(DaoStageViewpoints.project eq snapshot).map { it.uuid to it.name }, own.stageViewpoints().map { it.uuid to it.name })
        return out
    }

    // ── Overlaps (decisions) ────────────────────────────────────────────────────

    /** One unit's channels as a rig sees them; [mine] when the show owns it or re-addresses it. */
    data class Span(
        val uuid: UUID,
        val patchId: Int,
        val key: String,
        val name: String,
        val subnet: Int,
        val universe: Int,
        val first: Int,
        val last: Int,
        val mine: MineKind?,
    ) {
        val address: String get() = "$universe-${first.toString().padStart(3, '0')}"
        fun overlaps(other: Span) = subnet == other.subnet && universe == other.universe && first <= other.last && last >= other.first
    }

    enum class MineKind { OWN, OVERRIDE }

    fun spansOf(rig: RigScope): List<Span> = rig.effective(rig.patches().toList()).map { p: EffectivePatch ->
        val count = channelCountOf(p.fixtureTypeKey)
        val mine = when {
            rig.ownerOf(p.row.readValues[DaoFixturePatches.project]) == RigOwner.SHOW -> MineKind.OWN
            PatchFacet.ADDRESS in p.overridden -> MineKind.OVERRIDE
            else -> null
        }
        Span(p.uuid, p.id.value, p.key, p.displayName, p.universeConfig.subnet, p.universeConfig.universe, p.startChannel, p.startChannel + count - 1, mine)
    }

    /** Every overlapping pair with something of the show's on one side, keyed by the two uuids (mine first). */
    fun overlapPairs(spans: List<Span>): Map<Pair<UUID, UUID>, Pair<Span, Span>> {
        val out = LinkedHashMap<Pair<UUID, UUID>, Pair<Span, Span>>()
        for (a in spans) {
            if (a.mine == null) continue
            for (b in spans) {
                if (a.uuid == b.uuid || !a.overlaps(b)) continue
                // Two of the show's own: not the change's doing. An override against an own patch: the
                // own side is mine.
                if (b.mine == MineKind.OWN) continue
                if (b.mine == MineKind.OVERRIDE && a.mine == MineKind.OVERRIDE && a.uuid > b.uuid) continue
                out[a.uuid to b.uuid] = a to b
            }
        }
        return out
    }

    fun decisionOf(
        project: DaoProject,
        mine: Span,
        other: Span,
        spans: List<Span>,
        suggestedSoFar: MutableMap<UUID, Span>,
    ): UpdateDecisionDto? {
        val kind = mine.mine ?: return null
        val suggested = suggestedSoFar[mine.uuid]?.first ?: freeStart(mine, spans + suggestedSoFar.values)?.also { start ->
            suggestedSoFar[mine.uuid] = mine.copy(first = start, last = start + (mine.last - mine.first))
        }
        // Hiding a venue fixture the show still links to (a tile, a cue event, scenery) is refused, so
        // it is not offered — the POST would fail after a review that looked clean.
        val hideable = ShowHides.hideRefusal(project, HideKind.PATCH, other.uuid) == null
        val answers = buildList {
            add(UpdateAnswer.KEEP_BOTH)
            if (suggested != null) add(UpdateAnswer.READDRESS_MINE)
            if (hideable) add(UpdateAnswer.HIDE_VENUES)
            add(UpdateAnswer.REMOVE_MINE)
        }
        val whose = if (kind == MineKind.OWN) "Your ${mine.name}" else "Your address for ${mine.name}"
        return UpdateDecisionDto(
            id = "overlap:${mine.uuid}:${other.uuid}",
            kind = "ADDRESS_OVERLAP",
            message = "$whose at ${mine.address} clashes with ${other.name} (${other.key}) at ${other.address}: two fixtures can't share an address.",
            mine = UpdateUnitDto(mine.uuid.toString(), mine.patchId, mine.key, mine.name, mine.address, kind.name),
            other = UpdateUnitDto(other.uuid.toString(), other.patchId, other.key, other.name, other.address, "VENUE"),
            answers = answers,
            suggestedStartChannel = suggested,
            suggestedAddress = suggested?.let { "${mine.universe}-${it.toString().padStart(3, '0')}" },
        )
    }

    /** The free start channel on [mine]'s universe nearest its own, where nothing else in [spans] sits; null when none. */
    private fun freeStart(mine: Span, spans: List<Span>): Int? {
        val count = mine.last - mine.first + 1
        val others = spans.filter { it.uuid != mine.uuid && it.subnet == mine.subnet && it.universe == mine.universe }
        return (1..(512 - count + 1))
            .filter { start -> others.none { start <= it.last && start + count - 1 >= it.first } }
            .minByOrNull { kotlin.math.abs(it - mine.first) }
    }

    /**
     * Carry out each decided answer. Answers notes by decision id. Units are re-addressed in turn against
     * where the ones before them went ([working]), so two moves in one change never land on each other.
     * A re-address that cannot land is refused 400 [invalidCode].
     */
    fun applyDecisions(project: DaoProject, decisions: List<UpdateDecisionDto>, spans: List<Span>, invalidCode: String): Map<String, String> {
        val notes = HashMap<String, String>()
        val working = spans.toMutableList()
        val byMine = decisions.filter { it.decided }.groupBy { it.mine.uuid }
        for ((mineUuid, ds) in byMine) {
            val mine = working.first { it.uuid.toString() == mineUuid }
            // Hiding the venue's is per pair; removing or re-addressing mine is once for all its pairs.
            for (d in ds.filter { it.answer == UpdateAnswer.HIDE_VENUES }) {
                ShowHides.setHiddenByUuid(project, HideKind.PATCH, UUID.fromString(d.other.uuid), hidden = true)
            }
            when {
                ds.any { it.answer == UpdateAnswer.REMOVE_MINE } -> when (mine.mine) {
                    MineKind.OWN -> deleteOwnPatch(DaoFixturePatch[mine.patchId])
                    else -> DaoShowOverride.find {
                        (DaoShowOverrides.project eq project.id) and (DaoShowOverrides.recordUuid eq mine.uuid) and
                            (DaoShowOverrides.facet eq PatchFacet.ADDRESS)
                    }.forEach { it.delete() }
                }
                ds.any { it.answer == UpdateAnswer.READDRESS_MINE } -> {
                    val decision = ds.first { it.answer == UpdateAnswer.READDRESS_MINE }
                    val count = mine.last - mine.first + 1
                    val start = decision.startChannel ?: freeStart(mine, working)
                        ?: throw VenueException.badRequest(invalidCode, "There is no free address for ${mine.name} on universe ${mine.universe}")
                    if (start < 1 || start + count - 1 > 512) {
                        throw VenueException.badRequest(invalidCode, "${mine.name} takes $count channels: it cannot start at $start")
                    }
                    readdress(project, mine, start)
                    val moved = mine.copy(first = start, last = start + count - 1)
                    working[working.indexOf(mine)] = moved
                    val clash = working.firstOrNull { it.uuid != mine.uuid && moved.overlaps(it) }
                    if (clash != null) notes[decision.id] = "(At ${mine.universe}-$start it still overlaps ${clash.name}.)"
                }
            }
        }
        return notes
    }

    private fun readdress(project: DaoProject, mine: Span, start: Int) {
        when (mine.mine) {
            MineKind.OWN -> DaoFixturePatch[mine.patchId].startChannel = start
            else -> {
                val row = DaoShowOverride.find {
                    (DaoShowOverrides.project eq project.id) and (DaoShowOverrides.recordUuid eq mine.uuid) and
                        (DaoShowOverrides.facet eq PatchFacet.ADDRESS)
                }.first()
                val value = decodeFacet(PatchFacet.ADDRESS, row.value) as AddressFacet
                row.value = encodeFacet(value.copy(startChannel = start))
            }
        }
    }

    // ── Dead references ─────────────────────────────────────────────────────────

    data class Keys(val fixtures: Map<String, String>, val groups: Set<String>)

    fun keysOf(rig: RigScope) = Keys(
        fixtures = rig.patches().associate { it.key to it.displayName },
        groups = rig.groups().map { it.name }.toSet(),
    )

    /**
     * Every fixture key and group name the change takes out of the rig that the show still names —
     * through `RigKeyReferences`, so every place a key is stored counts: cue rows, layer targets and
     * ad-hoc effects (a selection's items included), Look rows (a cell's element key too) and effects,
     * palette rows, surface bindings and scripts — each with who names it. The rows stay, marked
     * *Missing fixture* as a deleted fixture's are. [gone] finishes the sentence about one (`is gone in v8`).
     */
    fun deadReferences(project: DaoProject, before: Keys, after: Keys, gone: String): List<DeadReferenceDto> {
        val goneFixtures = before.fixtures.keys - after.fixtures.keys
        val goneGroups = before.groups - after.groups
        if (goneFixtures.isEmpty() && goneGroups.isEmpty()) return emptyList()
        val users = RigKeyReferences.users(project, goneFixtures, goneGroups, before.fixtures.keys + after.fixtures.keys)
        return users.map { (name, by) ->
            val fixture = name.startsWith("fixture:")
            val key = name.substringAfter(':')
            val named = by.map(::usedBy)
            DeadReferenceDto(
                kind = if (fixture) "FIXTURE" else "GROUP",
                key = key,
                name = if (fixture) before.fixtures[key] else key,
                usedBy = named,
                detail = "${if (fixture) "'$key'" else "Group '$key'"} $gone and is named by " +
                    named.joinToString { it.label() } + ". The rows stay, marked missing, as a deleted fixture's are; a script is never rewritten.",
            )
        }
    }

    /** A [RigKeyReferences] user as a review names one. */
    fun usedBy(user: RigKeyReferences.User) = UsedByDto(user.kind, user.id, user.name)

    // A cue's numbers are its entries': every one it has (a shared cue's places), else its name.
    fun cueLabel(cue: DaoCue): String =
        entriesOfCue(cue).mapNotNull { e -> e.cueNumber?.takeIf { it.isNotBlank() }?.let { "Q$it" } }.distinct()
            .joinToString(" · ").ifEmpty { cue.name }

    fun UsedByDto.label() = when (kind) {
        "CUE" -> name
        "LOOK" -> "Look $name"
        "BINDING" -> "binding $name"
        "SCRIPT" -> "script $name"
        else -> "palette $name"
    }

    private fun channelCountOf(typeKey: String) = FixtureTypeRegistry.typeInfoForKey(typeKey)?.channelCount ?: 1
}
