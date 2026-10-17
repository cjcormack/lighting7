package uk.me.cormack.lighting7.venue

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import uk.me.cormack.lighting7.models.AddressFacet
import uk.me.cormack.lighting7.models.FacetValue
import uk.me.cormack.lighting7.models.FocusFacet
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.models.MediaFacet
import uk.me.cormack.lighting7.models.ParkAddress
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.models.PositionFacet
import uk.me.cormack.lighting7.models.StageBounds
import uk.me.cormack.lighting7.models.facetElement
import uk.me.cormack.lighting7.sync.Overrides
import java.util.UUID

/**
 * The pairing behind *Attach to venue…* (venue-attach plan §3, D1–D3, P2–P6): which of a standalone
 * show's records are a venue version's records, and what each pair becomes. A pure function over three
 * row sets — the show's own rows, the version it was detached from (its **origin**) when this desk holds
 * it, and the **target** version — answering an [AttachPlan]. It reads no database and writes nothing:
 * `VenueAttachRows` reads the rows, and `VenueAttach` writes the plan in one transaction.
 *
 * **Pairs** (§3.2). Each show record pairs with at most one target record of its kind: by uuid first
 * (a version keeps its records' uuids, so a detached show's rows still share them), then by key — a
 * patch's `key`, a universe's `subnet:universe`, any other kind's name. A uuid held twice in the target
 * matches nothing. A scene element pairs only on the VENUE layer and not as Preset seating (a show's
 * Preset copy, or a standalone show's seating with no block, is always the show's own).
 *
 * **What a pair becomes** (§3.3). The show's value (S) is compared with the target's (T), and with the
 * origin's (O) where there is one (P3): equal is *inherit* (the show's row goes, its links re-pointed
 * onto the target's); S = O ≠ T is the venue's change, inherited; a change of the show's alone
 * (S ≠ O = T, or no origin) is the show's; and both changing it is flagged. A patch's facets become
 * overrides with that base (T, or O as drift); a differing identity (key, type, head number, placement
 * count) keeps the show's by default (D2) and *Take venue's* renames its references; hall structure
 * takes the venue's by default and a group keeps the show's (D3). *Keep mine* hides the target record
 * and keeps the show's row under a fresh uuid (P5). Universes always come in (P6).
 *
 * **What has no pair** (§3.4): a show record is its own addition; a venue record the origin held is
 * one the show removed, and is hidden, and one it did not hold — or any, without an origin — comes in.
 * A hide refused by the in-use rule (`VENUE_HIDE_IN_USE`: a replaced record, a rigging a visible unit
 * hangs on, a seating a visible seat view sits in) comes in instead, saying why. Parks and machine
 * overrides follow §3.5, parks three ways too: a show park still at the origin's value takes the
 * venue's re-park or unpark since, listed as an output change. A park names its universe by number, so
 * a show park on a universe paired with a venue universe of another number is read, and kept, at the
 * venue's number (`AttachWrites.parksMoved`).
 *
 * **Blockers** (§3.6): a show record kept with a key, head number or name a visible venue record
 * holds, where that record cannot be hidden, a show universe whose number a venue universe holds
 * (universes are never hidden), and a *Take venue's* rename onto a key or group name the show keeps
 * for another record.
 *
 * Decisions are answered by id ([UpdateDecisionAnswer], the shape Update takes); an answered decision
 * is applied, so the plan for a request is the plan its POST writes. An id the pairing does not make
 * is left to the caller (P8's address overlaps are judged on the written rig, by `VenueAttach`).
 */
object VenueAttachPairing {

    const val CODE_ATTACH_INVALID = "ATTACH_INVALID"

    // ── Inputs ──────────────────────────────────────────────────────────────────

    data class UniverseRow(val id: Int, val uuid: UUID, val subnet: Int, val universe: Int, val controllerType: String) {
        val number: String get() = "$subnet:$universe"
    }

    data class PlacementRow(val id: Int, val uuid: UUID, val label: String?)

    /**
     * One patch: its identity, its four facets as `venueFacetOf` reads the row (references by uuid), and
     * the rest of what a venue patch holds that is not a facet ([attributes]: name, stage-hidden,
     * infrastructure, a hand-set kind, placement labels), which a pair takes from the venue.
     */
    data class PatchRow(
        val id: Int,
        val uuid: UUID,
        val key: String,
        val displayName: String,
        val fixtureTypeKey: String,
        val headNumber: Int?,
        val placements: List<PlacementRow>,
        val facets: Map<PatchFacet, FacetValue>,
        val attributes: Map<String, String?> = emptyMap(),
    )

    /**
     * A rigging, region, scene element or viewpoint: its comparable [fields] (every column but its id,
     * project, uuid, name and sort order) and its [refs], the uuids it names another record by — a
     * platform's `regionUuid`, a seat view's `seatElementUuid` — compared through the pairing.
     * [pairable] is false for a scene element that is always the show's own (SET layer, Preset seating).
     */
    data class HallRow(
        val id: Int,
        val uuid: UUID,
        val name: String,
        val fields: Map<String, String?> = emptyMap(),
        val refs: Map<String, UUID?> = emptyMap(),
        val pairable: Boolean = true,
    )

    data class GroupMemberRow(val patchUuid: UUID, val panOffset: Double, val tiltOffset: Double)

    /** A group and its members in order. */
    data class GroupRow(val id: Int, val uuid: UUID, val name: String, val members: List<GroupMemberRow>)

    data class MachineOverrideRow(val id: Int, val table: String, val recordUuid: UUID, val field: String, val valueJson: String)

    /** One project's rig rows, as `VenueAttachRows` reads them. A version's machine overrides are its venue's. */
    data class Rows(
        val universes: List<UniverseRow> = emptyList(),
        val patches: List<PatchRow> = emptyList(),
        val riggings: List<HallRow> = emptyList(),
        val regions: List<HallRow> = emptyList(),
        val elements: List<HallRow> = emptyList(),
        val viewpoints: List<HallRow> = emptyList(),
        val groups: List<GroupRow> = emptyList(),
        val parks: Map<ParkAddress, UByte> = emptyMap(),
        val machineOverrides: List<MachineOverrideRow> = emptyList(),
        val stage: StageBounds = StageBounds(null, null, null),
    ) {
        fun hall(kind: AttachKind): List<HallRow> = when (kind) {
            AttachKind.RIGGING -> riggings
            AttachKind.REGION -> regions
            AttachKind.ELEMENT -> elements
            AttachKind.VIEWPOINT -> viewpoints
            else -> error("$kind is not hall structure")
        }
    }

    /** A venue version: the venue, its number, and its rows — null for an origin this desk does not hold. */
    data class Version(val venueUuid: UUID, val venueName: String, val number: Int, val rows: Rows?)

    /**
     * Pair [show] with [target], three ways against [origin] when it names a version of the same venue
     * that this desk holds (§3.1). [answers] are the review's decisions, by id; [freshUuid] mints the
     * uuid a kept row (or a kept patch's placement) of the given uuid takes (P5).
     */
    fun pair(
        show: Rows,
        origin: Version?,
        target: Version,
        answers: List<UpdateDecisionAnswer> = emptyList(),
        freshUuid: (UUID) -> UUID = { UUID.randomUUID() },
    ): AttachPlan {
        val rows = requireNotNull(target.rows) { "The target version's rows" }
        return Pairing(show, origin, target, rows, answers.associate { it.id to it.answer }, freshUuid).run()
    }

    // ── The pairing ─────────────────────────────────────────────────────────────

    private enum class Who { SAME, VENUE, SHOW, BOTH }

    /** S against T, and O where the origin holds the record (P3). */
    private fun <V> judge(s: V, t: V, o: V?): Who = when {
        s == t -> Who.SAME
        o != null && s == o -> Who.VENUE
        o == null || o == t -> Who.SHOW
        else -> Who.BOTH
    }

    private enum class HideReason { KEPT, REMOVED_SINCE_ORIGIN, KEY_CLASH, ANSWERED }

    private class PlannedHide(val kind: AttachKind, val record: AttachRecordDto, val reason: HideReason, val detail: String, val clashWith: AttachRecordDto? = null)

    private class Pairing(
        val show: Rows,
        originVersion: Version?,
        val target: Version,
        val t: Rows,
        val answers: Map<String, UpdateAnswer>,
        val freshUuid: (UUID) -> UUID,
    ) {
        val originStatus: AttachOriginStatus? = when {
            originVersion == null -> null
            originVersion.venueUuid != target.venueUuid -> AttachOriginStatus.OTHER_VENUE
            originVersion.rows == null -> AttachOriginStatus.MISSING
            else -> AttachOriginStatus.USED
        }
        val o: Rows? = originVersion?.rows?.takeIf { originStatus == AttachOriginStatus.USED }
        val originDto: AttachOriginDto? = originVersion?.let { v ->
            val status = originStatus!!
            AttachOriginDto(
                venueUuid = v.venueUuid.toString(),
                version = v.number,
                status = status,
                detail = when (status) {
                    AttachOriginStatus.USED -> "Matched three ways against v${v.number}, the version this show was detached from"
                    AttachOriginStatus.MISSING -> "This show was detached from ${v.venueName} v${v.number}, which this desk doesn't have: " +
                        "matching is two-way, and every venue record the show lacks comes in"
                    AttachOriginStatus.OTHER_VENUE -> "This show was detached from another venue (${v.venueName}): " +
                        "matching is two-way, and every venue record the show lacks comes in"
                },
            )
        }

        /** Every show record's uuid after the attach: the target's for a replaced row, a fresh one for a kept row. */
        val after = HashMap<UUID, UUID>()
        val replaced = LinkedHashMap<AttachKind, LinkedHashMap<Int, Int>>()
        /** Show record uuid → the target record it was replaced by. */
        val replacedUuids = HashMap<UUID, UUID>()
        val replacedTargets = HashMap<AttachKind, MutableSet<UUID>>()
        val fresh = LinkedHashMap<AttachKind, LinkedHashMap<Int, UUID>>()
        val pairedTargets = HashMap<AttachKind, MutableSet<UUID>>()

        val output = mutableListOf<AttachItemDto>()
        val drawing = mutableListOf<AttachItemDto>()
        val overrides = mutableListOf<AttachOverrideDto>()
        val plannedOverrides = mutableListOf<PlannedOverride>()
        val kept = mutableListOf<AttachItemDto>()
        val additions = mutableListOf<AttachItemDto>()
        val incoming = mutableListOf<AttachItemDto>()
        val renames = mutableListOf<AttachRenameDto>()
        val decisions = mutableListOf<AttachDecisionDto>()
        /** Every decision id asked, including those [build] drops because a clash decided them. */
        val asked = LinkedHashSet<String>()
        val blockers = mutableListOf<AttachItemDto>()
        val hidesWanted = LinkedHashMap<Pair<AttachKind, UUID>, PlannedHide>()
        val hideRefusals = HashMap<Pair<AttachKind, UUID>, String>()

        /** The show's records that stay its own (additions and kept rows), by kind, as they are after the attach. */
        val keptPatches = mutableListOf<PatchRow>()
        val keptHall = HashMap<AttachKind, MutableList<HallRow>>()
        val keptGroups = mutableListOf<GroupRow>()
        val keptUniverses = mutableListOf<UniverseRow>()

        /** The venue patches' Position after the attach — their own, or an override's — by target uuid. */
        val venuePositions = HashMap<UUID, PositionFacet?>()

        /**
         * The origin's records (and placements) in the target's terms: origin uuid → target uuid, paired as
         * the show's are, so a record the venue re-made under a new uuid still reads as the same one (P3).
         */
        val originToTarget = HashMap<UUID, UUID>()
        /** The origin record each target record was, by target uuid. */
        val targetToOrigin = HashMap<UUID, UUID>()

        /**
         * A park names its universe by number, so a park on a universe that pairs with a venue universe
         * of another number is read at the venue's number: show (or origin) universe number → target's.
         */
        val showUniverseNumbers = HashMap<Int, Int>()
        val originUniverseNumbers = HashMap<Int, Int>()

        fun run(): AttachPlan {
            pairOrigin()
            universes()
            for (kind in listOf(AttachKind.REGION, AttachKind.RIGGING, AttachKind.ELEMENT, AttachKind.VIEWPOINT)) hall(kind)
            patches()
            unpaired(AttachKind.PATCH, t.patches.map { it.uuid to patchRecord(it) })
            for (kind in listOf(AttachKind.REGION, AttachKind.RIGGING, AttachKind.ELEMENT, AttachKind.VIEWPOINT)) {
                unpaired(kind, t.hall(kind).map { it.uuid to hallRecord(it) })
            }
            resolveHides(rigKinds)
            renameBlockers(AttachKind.PATCH)
            groups()
            unpaired(AttachKind.GROUP, t.groups.map { it.uuid to groupRecord(it) })
            resolveHides(setOf(AttachKind.GROUP))
            renameBlockers(AttachKind.GROUP)
            dormancy()
            parks()
            machineOverrides()
            stage()
            return build()
        }

        // ── The origin in the target's terms ────────────────────────────────────

        fun pairOrigin() {
            val origin = o ?: return
            fun <R> link(pairs: List<Pair<R, R>>, uuid: (R) -> UUID) = pairs.forEach { (a, b) ->
                originToTarget[uuid(a)] = uuid(b)
                targetToOrigin[uuid(b)] = uuid(a)
            }
            val universes = match(origin.universes, t.universes, { it.uuid }, { it.uuid }, { it.number }, { it.number })
            link(universes) { it.uuid }
            universes.forEach { (ou, tu) -> originUniverseNumbers[ou.universe] = tu.universe }
            for (kind in listOf(AttachKind.REGION, AttachKind.RIGGING, AttachKind.ELEMENT, AttachKind.VIEWPOINT)) {
                link(match(origin.hall(kind), t.hall(kind), { it.uuid }, { it.uuid }, { it.name }, { it.name })) { it.uuid }
            }
            val patches = match(origin.patches, t.patches, { it.uuid }, { it.uuid }, { it.key }, { it.key })
            link(patches) { it.uuid }
            for ((op, tp) in patches) originToTarget.putAll(pairPlacements(op.placements, tp.placements))
            link(match(origin.groups, t.groups, { it.uuid }, { it.uuid }, { it.name }, { it.name })) { it.uuid }
        }

        /** The origin's record of [kind] that target record [uuid] was, or null when it is new since. */
        fun <R> originRecord(rows: List<R>, uuid: UUID, uuidOf: (R) -> UUID): R? =
            targetToOrigin[uuid]?.let { o -> rows.firstOrNull { uuidOf(it) == o } }

        // ── Universes (P6) ──────────────────────────────────────────────────────

        fun universes() {
            val pairs = match(show.universes, t.universes, { it.uuid }, { it.uuid }, { it.number }, { it.number })
            for ((s, tu) in pairs) {
                replace(AttachKind.UNIVERSE, s.id, s.uuid, tu.id, tu.uuid)
                showUniverseNumbers[s.universe] = tu.universe
                val what = universeRecord(tu)
                if (s.number != tu.number) {
                    output += AttachItemDto(AttachKind.UNIVERSE, universeRecord(s), what, "Your universe ${s.number} is the venue's ${tu.number}: its fixtures output there", "CHANGE")
                }
                if (s.controllerType != tu.controllerType) {
                    output += AttachItemDto(AttachKind.UNIVERSE, universeRecord(s), what, "Universe ${tu.number} sends through the venue's ${tu.controllerType} controller, not ${s.controllerType}", "CHANGE")
                }
            }
            val paired = pairs.map { it.first.uuid }.toSet()
            for (s in show.universes.filter { it.uuid !in paired }) {
                keptUniverses += s
                after[s.uuid] = s.uuid
                val taken = t.universes.firstOrNull { it.number == s.number }
                if (taken != null) {
                    // A universe can't be hidden (P6), so a number held twice is never resolved by a hide.
                    blockers += AttachItemDto(
                        AttachKind.UNIVERSE, universeRecord(s), universeRecord(taken),
                        "Your universe ${s.number} has the number the venue's universe ${taken.number} holds, which can't be hidden — renumber yours first",
                        "UNIVERSE_CLASH",
                    )
                    continue
                }
                additions += AttachItemDto(AttachKind.UNIVERSE, universeRecord(s), null, "Your universe ${s.number}: the venue has none", "ADDITION")
            }
            for (tu in t.universes.filter { it.uuid !in pairedTargets[AttachKind.UNIVERSE].orEmpty() }) {
                val detail = "The venue's universe ${tu.number} comes in: a venue universe always does"
                incoming += AttachItemDto(AttachKind.UNIVERSE, null, universeRecord(tu), detail, if (o == null) "NO_ORIGIN" else "UNIVERSE")
            }
        }

        // ── Hall structure (D3) ─────────────────────────────────────────────────

        fun hall(kind: AttachKind) {
            val mine = show.hall(kind)
            val (pairable, own) = mine.partition { it.pairable }
            val pairs = match(pairable, t.hall(kind), { it.uuid }, { it.uuid }, { it.name }, { it.name })
            val origin = o?.hall(kind).orEmpty()
            for ((s, tr) in pairs) {
                val who = judge(compared(s), venueValue(tr), originRecord(origin, tr.uuid) { it.uuid }?.let(::originValue))
                val showRec = hallRecord(s)
                val venueRec = hallRecord(tr)
                val keepMine = when (who) {
                    Who.SAME -> false
                    Who.VENUE -> false.also { drawing += change(kind, showRec, venueRec, "The venue changed its ${kind.label} ${tr.name}: yours takes it") }
                    Who.SHOW, Who.BOTH -> {
                        val answer = decide(
                            id = "hall:${kind.name}:${s.uuid}",
                            kind = "HALL",
                            recordKind = kind,
                            show = showRec,
                            venue = venueRec,
                            message = "Your ${kind.label} ${s.name} differs from the venue's ${tr.name}" +
                                if (who == Who.BOTH) ": you and the venue both changed it" else "",
                            default = UpdateAnswer.TAKE_VENUES,
                            bothChanged = who == Who.BOTH,
                        )
                        if (answer == UpdateAnswer.TAKE_VENUES) {
                            drawing += change(kind, showRec, venueRec, "Your ${kind.label} ${s.name} becomes the venue's ${tr.name}")
                        }
                        answer == UpdateAnswer.KEEP_MINE
                    }
                }
                if (keepMine) keep(kind, s.id, s.uuid, tr.uuid, showRec, venueRec, s) else replace(kind, s.id, s.uuid, tr.id, tr.uuid)
            }
            val paired = pairs.map { it.first.uuid }.toSet()
            for (s in pairable.filter { it.uuid !in paired } + own) {
                after[s.uuid] = s.uuid
                keptHall.getOrPut(kind) { mutableListOf() } += s
                additions += AttachItemDto(kind, hallRecord(s), null, "Your ${kind.label} ${s.name}", "ADDITION")
            }
        }

        /** A show hall row's value as the pair compares it: its name, fields, and references through the pairing. */
        fun compared(row: HallRow): Map<String, String?> = hallValue(row) { after[it] ?: it }

        /** A target hall row's value: its name, fields and references as they are. */
        fun venueValue(row: HallRow): Map<String, String?> = hallValue(row) { it }

        /** An origin hall row's value in the target's terms. */
        fun originValue(row: HallRow): Map<String, String?> = hallValue(row) { originToTarget[it] ?: it }

        fun hallValue(row: HallRow, ref: (UUID) -> UUID): Map<String, String?> =
            row.fields + row.refs.mapValues { (_, r) -> r?.let(ref)?.toString() } + (NAME to row.name)

        // ── Patches (D2, §3.3) ──────────────────────────────────────────────────

        fun patches() {
            val pairs = match(show.patches, t.patches, { it.uuid }, { it.uuid }, { it.key }, { it.key })
            val origin = o?.patches.orEmpty()
            for ((s, tp) in pairs) patchPair(s, tp, originRecord(origin, tp.uuid) { it.uuid })
            val paired = pairs.map { it.first.uuid }.toSet()
            for (s in show.patches.filter { it.uuid !in paired }) {
                after[s.uuid] = s.uuid
                s.placements.forEach { after[it.uuid] = it.uuid }
                keptPatches += s
                additions += AttachItemDto(AttachKind.PATCH, patchRecord(s), null, "Your ${s.displayName} (${s.key})", "ADDITION")
            }
            for (tp in t.patches) if (tp.uuid !in venuePositions) venuePositions[tp.uuid] = tp.facets[PatchFacet.POSITION] as PositionFacet?
        }

        fun patchPair(s: PatchRow, tp: PatchRow, op: PatchRow?) {
            val showRec = patchRecord(s)
            val venueRec = patchRecord(tp)
            val who = judge(identityOf(s), identityOf(tp), op?.let(::identityOf))
            val keyRename = if (s.key != tp.key) AttachRenameDto(AttachKind.PATCH, s.key, tp.key, showRec, venueRec) else null
            val takeVenues = when (who) {
                Who.SAME -> true
                Who.VENUE -> true.also { identityChange(s, tp, showRec, venueRec, "The venue changed") }
                Who.SHOW, Who.BOTH -> {
                    val answer = decide(
                        id = "identity:${s.uuid}",
                        kind = "IDENTITY",
                        recordKind = AttachKind.PATCH,
                        show = showRec,
                        venue = venueRec,
                        message = "Your ${s.displayName} (${s.key}) is not the venue's ${tp.displayName} (${tp.key}): " +
                            identityDifferences(s, tp).joinToString() + if (who == Who.BOTH) " — you and the venue both changed it" else "",
                        default = UpdateAnswer.KEEP_MINE,
                        bothChanged = who == Who.BOTH,
                        rename = keyRename,
                    )
                    (answer == UpdateAnswer.TAKE_VENUES).also { if (it) identityChange(s, tp, showRec, venueRec, "Taking the venue's") }
                }
            }
            if (!takeVenues) {
                keep(AttachKind.PATCH, s.id, s.uuid, tp.uuid, showRec, venueRec, null)
                s.placements.forEach { after[it.uuid] = freshUuid(it.uuid) }
                keptPatches += s
                return
            }
            replace(AttachKind.PATCH, s.id, s.uuid, tp.id, tp.uuid)
            val placements = pairPlacements(s.placements, tp.placements)
            placements.forEach { (sp, tpl) -> after[sp] = tpl }
            if (keyRename != null) renames += keyRename

            for (facet in PatchFacet.entries) {
                val sv = s.facets[facet]?.let { mapFacet(it, placements) { u -> after[u] ?: u } }
                val tv = tp.facets[facet]
                val ov = op?.facets?.get(facet)?.let { mapFacet(it, originToTarget) { u -> originToTarget[u] ?: u } }
                if (sv == null || tv == null) continue
                when (judge(sv, tv, ov)) {
                    Who.SAME -> Unit
                    Who.VENUE -> facetChange(facet, showRec, venueRec, "The venue changed ${tp.displayName}'s ${facet.label()}: yours takes it")
                    Who.SHOW -> override(tp, venueRec, facet, sv, tv, tv, drift = false)
                    Who.BOTH -> {
                        val id = VenueUpdate.driftId(tp.uuid, facet)
                        val answer = decide(
                            id = id,
                            kind = "DRIFT",
                            recordKind = AttachKind.PATCH,
                            show = showRec,
                            venue = venueRec,
                            message = "You and the venue both changed ${tp.displayName}'s ${facet.label()} since v${originDto?.version}",
                            default = UpdateAnswer.KEEP_MINE,
                            bothChanged = true,
                        )
                        if (answer == UpdateAnswer.KEEP_MINE) override(tp, venueRec, facet, sv, ov!!, tv, drift = true, decisionId = id)
                        else facetChange(facet, showRec, venueRec, "Taking the venue's ${facet.label()} for ${tp.displayName}")
                    }
                }
            }
            val attributes = attributeNames(s, tp)
            if (attributes.isNotEmpty()) {
                drawing += change(AttachKind.PATCH, showRec, venueRec, "${tp.displayName} takes the venue's ${attributes.joinToString()}")
            }
        }

        fun identityChange(s: PatchRow, tp: PatchRow, showRec: AttachRecordDto, venueRec: AttachRecordDto, why: String) {
            val differences = identityDifferences(s, tp)
            val outputs = s.fixtureTypeKey != tp.fixtureTypeKey || s.placements.size != tp.placements.size
            val item = change(AttachKind.PATCH, showRec, venueRec, "$why ${s.displayName}'s ${differences.joinToString()}")
            if (outputs) output += item else drawing += item
        }

        fun facetChange(facet: PatchFacet, showRec: AttachRecordDto, venueRec: AttachRecordDto, detail: String) {
            val item = change(AttachKind.PATCH, showRec, venueRec, detail)
            if (facet == PatchFacet.ADDRESS) output += item else drawing += item
        }

        fun override(
            tp: PatchRow,
            venueRec: AttachRecordDto,
            facet: PatchFacet,
            value: FacetValue,
            base: FacetValue,
            venueValue: FacetValue,
            drift: Boolean,
            decisionId: String? = null,
        ) {
            plannedOverrides += PlannedOverride(tp.uuid, facet, value, base)
            if (facet == PatchFacet.POSITION) venuePositions[tp.uuid] = value as PositionFacet
            overrides += AttachOverrideDto(
                venue = venueRec,
                facet = facet,
                value = facetElement(value),
                base = facetElement(base),
                venueValue = facetElement(venueValue),
                drift = drift,
                decisionId = decisionId,
            )
        }

        /** Placements of a pair: by uuid inside the pair, then the rest in order, one for one. From uuid → target uuid. */
        fun pairPlacements(from: List<PlacementRow>, to: List<PlacementRow>): Map<UUID, UUID> {
            val targets = to.map { it.uuid }.toSet()
            val out = LinkedHashMap<UUID, UUID>()
            from.filter { it.uuid in targets }.forEach { out[it.uuid] = it.uuid }
            val rest = to.filter { it.uuid !in out.values }
            from.filter { it.uuid !in out }.zip(rest).forEach { (a, b) -> out[a.uuid] = b.uuid }
            return out
        }

        /** A facet value in the target's terms: its universe and rigging through [ref], its placements through [placements]. */
        fun mapFacet(value: FacetValue, placements: Map<UUID, UUID>, refOf: (UUID) -> UUID): FacetValue {
            fun ref(uuid: String?): String? = uuid?.let { u -> runCatching { UUID.fromString(u) }.getOrNull()?.let { refOf(it).toString() } ?: u }
            fun placement(uuid: String): String? = runCatching { UUID.fromString(uuid) }.getOrNull()?.let { placements[it]?.toString() }
            return when (value) {
                is AddressFacet -> value.copy(universeConfigUuid = ref(value.universeConfigUuid)!!)
                is PositionFacet -> value.copy(
                    riggingUuid = ref(value.riggingUuid),
                    placements = value.placements.mapNotNull { p -> placement(p.placementUuid)?.let { p.copy(placementUuid = it, riggingUuid = ref(p.riggingUuid)) } },
                )
                is FocusFacet -> value.copy(placements = value.placements.mapNotNull { p -> placement(p.placementUuid)?.let { p.copy(placementUuid = it) } })
                is MediaFacet -> value.copy(placements = value.placements.mapNotNull { p -> placement(p.placementUuid)?.let { p.copy(placementUuid = it) } })
            }
        }

        // ── Groups (D3) ─────────────────────────────────────────────────────────

        fun groups() {
            val pairs = match(show.groups, t.groups, { it.uuid }, { it.uuid }, { it.name }, { it.name })
            val origin = o?.groups.orEmpty()
            val hiddenPatches = hidesWanted.keys.filter { it.first == AttachKind.PATCH }.map { it.second }.toSet()
            // A group as the rig would see it: its members less the patches the attach hides.
            fun venueSide(g: GroupRow) = g.name to g.members.filter { it.patchUuid !in hiddenPatches }
            fun showSide(g: GroupRow) = g.name to g.members.map { it.copy(patchUuid = after[it.patchUuid] ?: it.patchUuid) }
            fun originSide(g: GroupRow) = venueSide(g.copy(members = g.members.map { it.copy(patchUuid = originToTarget[it.patchUuid] ?: it.patchUuid) }))
            for ((s, tg) in pairs) {
                val showRec = groupRecord(s)
                val venueRec = groupRecord(tg)
                val rename = if (s.name != tg.name) AttachRenameDto(AttachKind.GROUP, s.name, tg.name, showRec, venueRec) else null
                val who = judge(showSide(s), venueSide(tg), originRecord(origin, tg.uuid) { it.uuid }?.let(::originSide))
                val takeVenues = when (who) {
                    Who.SAME -> true
                    Who.VENUE -> true.also { drawing += change(AttachKind.GROUP, showRec, venueRec, "The venue changed its group ${tg.name}: yours takes it") }
                    Who.SHOW, Who.BOTH -> {
                        val both = who == Who.BOTH
                        val answer = decide(
                            id = "group:${s.uuid}",
                            kind = "GROUP",
                            recordKind = AttachKind.GROUP,
                            show = showRec,
                            venue = venueRec,
                            message = "Your group ${s.name} differs from the venue's ${tg.name}" + if (both) ": you and the venue both changed it" else "",
                            default = UpdateAnswer.KEEP_MINE,
                            bothChanged = both,
                            rename = rename,
                        )
                        (answer == UpdateAnswer.TAKE_VENUES).also {
                            if (it) drawing += change(AttachKind.GROUP, showRec, venueRec, "Your group ${s.name} becomes the venue's ${tg.name}")
                        }
                    }
                }
                if (takeVenues) {
                    replace(AttachKind.GROUP, s.id, s.uuid, tg.id, tg.uuid)
                    if (rename != null) renames += rename
                } else {
                    keep(AttachKind.GROUP, s.id, s.uuid, tg.uuid, showRec, venueRec, null)
                    keptGroups += s
                }
            }
            val paired = pairs.map { it.first.uuid }.toSet()
            for (s in show.groups.filter { it.uuid !in paired }) {
                after[s.uuid] = s.uuid
                keptGroups += s
                additions += AttachItemDto(AttachKind.GROUP, groupRecord(s), null, "Your group ${s.name}", "ADDITION")
            }
        }

        // ── No pair (§3.4) ──────────────────────────────────────────────────────

        fun unpaired(kind: AttachKind, records: List<Pair<UUID, AttachRecordDto>>) {
            val paired = pairedTargets[kind].orEmpty()
            for ((uuid, record) in records) {
                if (uuid in paired) continue
                when {
                    o != null && uuid in targetToOrigin -> wantHide(
                        PlannedHide(kind, record, HideReason.REMOVED_SINCE_ORIGIN, "Removed from this show since v${originDto!!.version}"),
                    )
                    o != null -> incoming += AttachItemDto(kind, null, record, "The venue added its ${kind.label} ${record.label()} since v${originDto!!.version}", "ADDED_SINCE_ORIGIN")
                        .also { if (kind == AttachKind.PATCH) output += it }
                    else -> {
                        val answer = decide(
                            id = "hide:${kind.name}:$uuid",
                            kind = "HIDE",
                            recordKind = kind,
                            show = null,
                            venue = record,
                            message = "The venue's ${kind.label} ${record.label()} comes in: hide it in this show?",
                            default = UpdateAnswer.TAKE_VENUES,
                            answersOffered = listOf(UpdateAnswer.TAKE_VENUES, UpdateAnswer.HIDE_VENUES),
                        )
                        if (answer == UpdateAnswer.HIDE_VENUES) {
                            wantHide(PlannedHide(kind, record, HideReason.ANSWERED, "Hidden in this show"))
                        } else {
                            incoming += AttachItemDto(kind, null, record, "The venue's ${kind.label} ${record.label()} comes in", "NO_ORIGIN")
                                .also { if (kind == AttachKind.PATCH) output += it }
                        }
                    }
                }
            }
        }

        // ── Hides, clashes and the in-use rule ──────────────────────────────────

        fun wantHide(hide: PlannedHide) {
            hidesWanted.putIfAbsent(hide.kind to UUID.fromString(hide.record.uuid), hide)
        }

        /**
         * Clash every record the show keeps against every venue record left visible, hiding the venue's
         * where it can (§3.2), then drop each hide the in-use rule refuses — which leaves more records
         * visible, so both run until nothing moves. What still clashes is a blocker (§3.6).
         */
        fun resolveHides(kinds: Set<AttachKind>) {
            val attempted = HashSet<Pair<AttachKind, UUID>>()
            while (true) {
                var moved = false
                for (c in clashes(kinds)) {
                    val id = c.kind to UUID.fromString(c.venue.uuid)
                    if (id in attempted || id in hidesWanted) continue
                    attempted += id
                    wantHide(PlannedHide(c.kind, c.venue, HideReason.KEY_CLASH, "Hidden: your ${c.kind.label} ${c.show.label()} has its ${c.what}", c.show))
                    moved = true
                }
                for ((id, hide) in hidesWanted.entries.filter { it.key.first in kinds }.toList()) {
                    val refusal = refusal(id.first, id.second) ?: continue
                    hidesWanted.remove(id)
                    hideRefusals[id] = refusal
                    moved = true
                    when (hide.reason) {
                        HideReason.REMOVED_SINCE_ORIGIN, HideReason.ANSWERED -> incoming += AttachItemDto(
                            hide.kind, null, hide.record,
                            "The venue's ${hide.kind.label} ${hide.record.label()} comes in: it can't be hidden, $refusal", "HIDE_REFUSED",
                        ).also { if (hide.kind == AttachKind.PATCH) output += it }
                        // Keep mine stands, beside the venue's record: the review says so, and a clash, if
                        // any, is found next pass.
                        HideReason.KEPT -> {
                            val i = kept.indexOfFirst { it.kind == hide.kind && it.venue?.uuid == hide.record.uuid }
                            if (i >= 0) {
                                kept[i] = kept[i].copy(
                                    detail = "Your ${hide.kind.label} ${kept[i].show?.label()} stays yours, and the venue's ${hide.record.label()} " +
                                        "stays too: it can't be hidden, $refusal",
                                    reason = "KEPT_BESIDE",
                                )
                            }
                            incoming += AttachItemDto(
                                hide.kind, kept.getOrNull(i)?.show, hide.record,
                                "The venue's ${hide.kind.label} ${hide.record.label()} comes in beside yours: it can't be hidden, $refusal", "HIDE_REFUSED",
                            ).also { if (hide.kind == AttachKind.PATCH) output += it }
                        }
                        HideReason.KEY_CLASH -> Unit
                    }
                }
                if (!moved) break
            }
            for (c in clashes(kinds)) {
                val id = c.kind to UUID.fromString(c.venue.uuid)
                blockers += AttachItemDto(
                    c.kind, c.show, c.venue,
                    "Your ${c.kind.label} ${c.show.label()} has its ${c.what}, which the venue's ${c.venue.label()} holds, and that can't be hidden" +
                        (hideRefusals[id]?.let { " ($it)" } ?: "") + " — rename yours first",
                    if (c.what.startsWith("head")) "HEAD_CLASH" else "KEY_CLASH",
                )
            }
        }

        class Clash(val kind: AttachKind, val show: AttachRecordDto, val venue: AttachRecordDto, val what: String)

        fun hidden(kind: AttachKind, uuid: UUID) = (kind to uuid) in hidesWanted

        fun clashes(kinds: Set<AttachKind>): List<Clash> = buildList {
            if (AttachKind.PATCH in kinds) {
                val visible = t.patches.filter { !hidden(AttachKind.PATCH, it.uuid) }
                for (s in keptPatches) {
                    visible.firstOrNull { it.key == s.key }?.let { add(Clash(AttachKind.PATCH, patchRecord(s), patchRecord(it), "key '${s.key}'")) }
                    val n = s.headNumber ?: continue
                    visible.firstOrNull { it.headNumber == n }?.let { add(Clash(AttachKind.PATCH, patchRecord(s), patchRecord(it), "head number $n")) }
                }
            }
            for (kind in kinds - AttachKind.PATCH) {
                val (venue, mine) = when (kind) {
                    AttachKind.GROUP -> t.groups.map { it.uuid to groupRecord(it) } to keptGroups.map { groupRecord(it) }
                    else -> t.hall(kind).map { it.uuid to hallRecord(it) } to keptHall[kind].orEmpty().map { hallRecord(it) }
                }
                val visible = venue.filter { !hidden(kind, it.first) }.map { it.second }
                for (s in mine) visible.firstOrNull { it.name == s.name }?.let { add(Clash(kind, s, it, "name '${s.name}'")) }
            }
        }

        /** Why hiding venue record [uuid] would be refused (`VENUE_HIDE_IN_USE`), or null when it may. */
        fun refusal(kind: AttachKind, uuid: UUID): String? {
            if (uuid in replacedTargets[kind].orEmpty()) return "it is the venue's record of one of yours"
            return when (kind) {
                AttachKind.RIGGING -> {
                    val target = uuid.toString()
                    fun hangs(p: PositionFacet?) = p != null && (p.riggingUuid == target || p.placements.any { it.riggingUuid == target })
                    val venueUnits = t.patches.filter { !hidden(AttachKind.PATCH, it.uuid) && hangs(venuePositions[it.uuid]) }.map { it.key }
                    val showUnits = keptPatches.filter { s ->
                        hangs((s.facets[PatchFacet.POSITION] as PositionFacet?)?.let { mapFacet(it, s.placements.associate { p -> p.uuid to (after[p.uuid] ?: p.uuid) }) { u -> after[u] ?: u } as PositionFacet })
                    }.map { it.key }
                    (venueUnits + showUnits).takeIf { it.isNotEmpty() }?.let { "fixtures hang on it (${it.joinToString()})" }
                }
                AttachKind.ELEMENT -> {
                    val venueViews = t.viewpoints.filter { !hidden(AttachKind.VIEWPOINT, it.uuid) && it.refs[SEAT_ELEMENT] == uuid }.map { it.name }
                    val showViews = keptHall[AttachKind.VIEWPOINT].orEmpty().filter { v -> v.refs[SEAT_ELEMENT]?.let { after[it] ?: it } == uuid }.map { it.name }
                    (venueViews + showViews).takeIf { it.isNotEmpty() }?.let { "seat views sit in it (${it.joinToString()})" }
                }
                else -> null
            }
        }

        fun renameBlockers(kind: AttachKind) {
            val keptNames = when (kind) {
                AttachKind.PATCH -> keptPatches.map { it.key }
                else -> keptGroups.map { it.name }
            }.toSet()
            for (r in renames.filter { it.kind == kind && it.to in keptNames }) {
                blockers += AttachItemDto(
                    kind, r.show, r.venue,
                    "Taking the venue's ${kind.label} renames ${r.from} to ${r.to}, which your own ${kind.label} keeps — rename that first",
                    "RENAME_TAKEN",
                )
            }
        }

        /** An override naming a universe or rigging the attached rig lacks contributes nothing (`RigScope.overridesOf`). */
        fun dormancy() {
            val universes = t.universes.map { it.uuid.toString() }.toSet() + keptUniverses.map { it.uuid.toString() }
            val riggings = t.riggings.filter { !hidden(AttachKind.RIGGING, it.uuid) }.map { it.uuid.toString() }.toSet() +
                keptHall[AttachKind.RIGGING].orEmpty().map { (after[it.uuid] ?: it.uuid).toString() }
            for (i in overrides.indices) {
                val value = plannedOverrides[i].value
                val dormant = when (value) {
                    is AddressFacet -> value.universeConfigUuid !in universes
                    is PositionFacet -> (listOf(value.riggingUuid) + value.placements.map { it.riggingUuid }).any { it != null && it !in riggings }
                    else -> false
                }
                if (dormant) overrides[i] = overrides[i].copy(dormant = true)
            }
        }

        // ── Parks and machine overrides (§3.5) ──────────────────────────────────

        val parksDropped = mutableListOf<ParkAddress>()
        val releases = mutableListOf<ParkAddress>()
        val hides = mutableListOf<AttachItemDto>()
        var parksInherited = 0

        val parksMoved = LinkedHashMap<ParkAddress, ParkAddress>()

        fun parks() {
            fun at(address: ParkAddress, numbers: Map<Int, Int>) = numbers[address.universe]?.let { address.copy(universe = it) } ?: address
            // The show's parks at the venue's universe numbers, each with the address its row has now.
            val ours = show.parks.entries.associate { (address, value) -> at(address, showUniverseNumbers) to (address to value) }
            val originParks = o?.parks?.mapKeys { at(it.key, originUniverseNumbers) }
            for (address in (ours.keys + t.parks.keys).sorted()) {
                val (row, mine) = ours[address] ?: (null to null)
                val venue = t.parks[address]
                val record = AttachRecordDto(address.toString(), "Park $address")
                val moved = if (row != null && row != address) " (your $row: its universe is the venue's ${address.universe})" else ""
                fun keep() {
                    if (row != null && row != address) parksMoved[row] = address
                }
                when {
                    mine != null && mine == venue -> {
                        parksDropped += row!!
                        parksInherited++
                    }
                    // The show's park is still the origin's, so a re-value or unpark since is the venue's (P3).
                    mine != null && originParks != null && originParks[address] == mine -> {
                        parksDropped += row!!
                        output += AttachItemDto(
                            AttachKind.PARK, record, if (venue != null) record else null,
                            if (venue != null) "The venue re-parked $address at $venue since v${originDto!!.version}: yours, at $mine, takes it$moved"
                            else "The venue unparked $address since v${originDto!!.version}: yours, at $mine, goes with it$moved",
                            "CHANGE",
                        )
                    }
                    mine != null && venue != null -> {
                        keep()
                        additions += AttachItemDto(AttachKind.PARK, record, record, "Your park of $address at $mine stays, over the venue's at $venue$moved", "REPARK")
                    }
                    mine != null -> {
                        keep()
                        additions += AttachItemDto(AttachKind.PARK, record, null, "Your park of $address at $mine$moved", "ADDITION")
                    }
                    venue != null && (originParks == null || originParks[address] != null) -> {
                        releases += address
                        hides += AttachItemDto(
                            AttachKind.PARK, null, record,
                            if (originParks == null) "The venue parks $address at $venue: released here, so this show's output stays as it is"
                            else "Unparked in this show since v${originDto!!.version}: the venue's park at $venue is released",
                            "RELEASE",
                        )
                    }
                    venue != null -> AttachItemDto(AttachKind.PARK, null, record, "The venue parked $address at $venue since v${originDto!!.version}: it comes in", "ADDED_SINCE_ORIGIN")
                        .let { incoming += it; output += it }
                }
            }
        }

        val machineOverridesDropped = mutableListOf<Int>()
        val machineOverridesRekeyed = LinkedHashMap<Int, UUID>()

        fun machineOverrides() {
            val venue = t.machineOverrides.groupBy { it.recordUuid }
            val freshByUuid = HashMap<UUID, UUID>()
            for ((kind, rows) in fresh) {
                val byId = rowsOf(kind).associate { it.first to it.second }
                for ((id, uuid) in rows) byId[id]?.let { freshByUuid[it] = uuid }
            }
            val mine = show.machineOverrides.groupBy { it.recordUuid }
            for ((recordUuid, rows) in mine) {
                val replacedBy = replacedUuids[recordUuid]
                when {
                    replacedBy != null -> rows.forEach { machineOverridesDropped += it.id }
                    recordUuid in freshByUuid -> rows.forEach { machineOverridesRekeyed[it.id] = freshByUuid.getValue(recordUuid) }
                }
            }
            // A replaced universe resolves against the venue's id from now on (P4 of the venue plan, P6).
            for (s in show.universes) {
                val tu = replacedUuids[s.uuid]?.let { u -> t.universes.firstOrNull { it.uuid == u } } ?: continue
                val showFields = mine[s.uuid].orEmpty().filter { it.table == Overrides.UNIVERSE_CONFIGS }.associate { it.field to it.valueJson }
                val venueFields = venue[tu.uuid].orEmpty().filter { it.table == Overrides.UNIVERSE_CONFIGS }.associate { it.field to it.valueJson }
                for (field in (showFields.keys + venueFields.keys).sorted()) {
                    val a = showFields[field]
                    val b = venueFields[field]
                    if (a == b) continue
                    output += AttachItemDto(
                        AttachKind.UNIVERSE, universeRecord(s), universeRecord(tu),
                        "Universe ${tu.number}'s $field on this desk is the venue's (${b ?: "none"}), not yours (${a ?: "none"})", "MACHINE_OVERRIDE",
                    )
                }
            }
        }

        fun stage() {
            if (show.stage != t.stage) {
                drawing += AttachItemDto(AttachKind.STAGE, null, null, "The stage is the venue's: ${dims(t.stage)}, not ${dims(show.stage)}", "CHANGE")
            }
        }

        private fun dims(b: StageBounds) = listOf(b.widthM, b.depthM, b.heightM).joinToString(" × ") { it?.let { v -> "$v m" } ?: "—" }

        // ── Bookkeeping ─────────────────────────────────────────────────────────

        fun replace(kind: AttachKind, showId: Int, showUuid: UUID, targetId: Int, targetUuid: UUID) {
            replaced.getOrPut(kind) { LinkedHashMap() }[showId] = targetId
            replacedUuids[showUuid] = targetUuid
            replacedTargets.getOrPut(kind) { HashSet() } += targetUuid
            pairedTargets.getOrPut(kind) { HashSet() } += targetUuid
            after[showUuid] = targetUuid
        }

        /** *Keep mine*: the target record is hidden and the show's row stays under a fresh uuid (P5). */
        fun keep(kind: AttachKind, showId: Int, showUuid: UUID, targetUuid: UUID, showRec: AttachRecordDto, venueRec: AttachRecordDto, hall: HallRow?) {
            val uuid = freshUuid(showUuid)
            fresh.getOrPut(kind) { LinkedHashMap() }[showId] = uuid
            pairedTargets.getOrPut(kind) { HashSet() } += targetUuid
            after[showUuid] = uuid
            if (hall != null) keptHall.getOrPut(kind) { mutableListOf() } += hall
            kept += AttachItemDto(kind, showRec, venueRec, "Your ${kind.label} ${showRec.label()} stays yours; the venue's ${venueRec.label()} is hidden", "KEPT")
            wantHide(PlannedHide(kind, venueRec, HideReason.KEPT, "Hidden by Keep mine: your ${showRec.label()} stays"))
        }

        fun decide(
            id: String,
            kind: String,
            recordKind: AttachKind,
            show: AttachRecordDto?,
            venue: AttachRecordDto,
            message: String,
            default: UpdateAnswer,
            bothChanged: Boolean = false,
            rename: AttachRenameDto? = null,
            answersOffered: List<UpdateAnswer> = listOf(UpdateAnswer.KEEP_MINE, UpdateAnswer.TAKE_VENUES),
        ): UpdateAnswer {
            val given = answers[id]
            if (given != null && given !in answersOffered) {
                throw VenueException.badRequest(CODE_ATTACH_INVALID, "'$given' does not answer '$id': it takes ${answersOffered.joinToString(" | ")}")
            }
            val answer = given ?: default
            asked += id
            decisions += AttachDecisionDto(id, kind, recordKind, show, venue, message, answersOffered, answer, decided = given != null, bothChanged = bothChanged, rename = rename)
            return answer
        }

        fun change(kind: AttachKind, show: AttachRecordDto?, venue: AttachRecordDto?, detail: String) = AttachItemDto(kind, show, venue, detail, "CHANGE")

        /** Every show row of [kind] as (id, uuid). */
        fun rowsOf(kind: AttachKind): List<Pair<Int, UUID>> = when (kind) {
            AttachKind.UNIVERSE -> show.universes.map { it.id to it.uuid }
            AttachKind.PATCH -> show.patches.map { it.id to it.uuid }
            AttachKind.GROUP -> show.groups.map { it.id to it.uuid }
            AttachKind.PARK, AttachKind.STAGE -> emptyList()
            else -> show.hall(kind).map { it.id to it.uuid }
        }

        fun build(): AttachPlan {
            // A record listed as coming in that a clash then hid does not come in.
            fun hiddenItem(item: AttachItemDto) =
                item.kind.hideKind != null && item.venue != null && hidden(item.kind, UUID.fromString(item.venue.uuid))
            incoming.removeAll(::hiddenItem)
            output.removeAll { it.reason in INCOMING_REASONS && hiddenItem(it) }
            // A record a clash hid offers no choice: neither answer would bring it in.
            decisions.removeAll { d ->
                d.kind == "HIDE" && hidesWanted[d.recordKind to UUID.fromString(d.venue.uuid)]?.reason.let { it != null && it != HideReason.ANSWERED }
            }
            val finalHides = hidesWanted.values.toList()
            hides.addAll(0, finalHides.map { h ->
                AttachItemDto(h.kind, h.clashWith, h.record, h.detail, h.reason.name)
            })
            val inherited = replaced.mapValues { it.value.size }.filterValues { it > 0 }.toMutableMap()
            if (parksInherited > 0) inherited[AttachKind.PARK] = parksInherited
            val freshUuids = fresh.mapValues { it.value.toMap() }
            val showUuids = AttachKind.entries.flatMap { rowsOf(it) }.map { it.second }.toSet() +
                show.patches.flatMap { p -> p.placements.map { it.uuid } }
            val uuidRewrites = after.filter { (from, to) -> from != to && from in showUuids }
            return AttachPlan(
                venueUuid = target.venueUuid,
                venueName = target.venueName,
                to = target.number,
                origin = originDto,
                output = output.toList(),
                drawing = drawing.toList(),
                inherited = inherited,
                overrides = overrides.toList(),
                kept = kept.toList(),
                hides = hides.toList(),
                additions = additions.toList(),
                incoming = incoming.toList(),
                renames = renames.toList(),
                decisions = decisions.toList(),
                decisionIds = asked.toSet(),
                blockers = blockers.toList(),
                writes = AttachWrites(
                    replaced = replaced.mapValues { it.value.toMap() },
                    freshUuids = freshUuids,
                    uuidRewrites = uuidRewrites,
                    overrides = plannedOverrides.toList(),
                    hides = finalHides.map { it.kind.hideKind!! to UUID.fromString(it.record.uuid) },
                    releases = releases.toList(),
                    parksDropped = parksDropped.toList(),
                    parksMoved = parksMoved.toMap(),
                    machineOverridesDropped = machineOverridesDropped.toList(),
                    machineOverridesRekeyed = machineOverridesRekeyed.toMap(),
                    fixtureRenames = renames.filter { it.kind == AttachKind.PATCH }.associate { it.from to it.to },
                    groupRenames = renames.filter { it.kind == AttachKind.GROUP }.associate { it.from to it.to },
                ),
            )
        }
    }

    private val INCOMING_REASONS = setOf("NO_ORIGIN", "ADDED_SINCE_ORIGIN", "HIDE_REFUSED")

    /** The [HallRow] value key a hall record's name is compared under. */
    private const val NAME = "name"

    private val rigKinds = setOf(AttachKind.PATCH, AttachKind.RIGGING, AttachKind.REGION, AttachKind.ELEMENT, AttachKind.VIEWPOINT)

    /** A viewpoint's seat: the [HallRow.refs] key naming the seating element it sits in. */
    const val SEAT_ELEMENT = "seatElementUuid"

    /** A platform's region: the [HallRow.refs] key naming the stage region it stands on. */
    const val PLATFORM_REGION = "regionUuid"

    /**
     * Pairs of [show] with [target]: by uuid first — a uuid held twice in the target matches nothing —
     * then by key among what is left, a key held twice likewise.
     */
    private fun <S, T> match(
        show: List<S>,
        target: List<T>,
        showUuid: (S) -> UUID,
        targetUuid: (T) -> UUID,
        showKey: (S) -> String,
        targetKey: (T) -> String,
    ): List<Pair<S, T>> {
        val out = mutableListOf<Pair<S, T>>()
        val byUuid = target.groupBy(targetUuid).filterValues { it.size == 1 }.mapValues { it.value.single() }
        val taken = HashSet<UUID>()
        val left = mutableListOf<S>()
        for (s in show) {
            val t = byUuid[showUuid(s)]
            if (t != null && taken.add(targetUuid(t))) out += s to t else left += s
        }
        val byKey = target.filter { targetUuid(it) !in taken }.groupBy(targetKey).filterValues { it.size == 1 }.mapValues { it.value.single() }
        for (s in left) {
            val t = byKey[showKey(s)] ?: continue
            if (taken.add(targetUuid(t))) out += s to t
        }
        return out
    }

    private data class Identity(val key: String, val type: String, val head: Int?, val placements: Int)

    private fun identityOf(p: PatchRow) = Identity(p.key, p.fixtureTypeKey, p.headNumber, p.placements.size)

    private fun identityDifferences(s: PatchRow, t: PatchRow) = buildList {
        if (s.key != t.key) add("key ${s.key} → ${t.key}")
        if (s.fixtureTypeKey != t.fixtureTypeKey) add("type ${s.fixtureTypeKey} → ${t.fixtureTypeKey}")
        if (s.headNumber != t.headNumber) add("head number ${s.headNumber ?: "none"} → ${t.headNumber ?: "none"}")
        if (s.placements.size != t.placements.size) add("${s.placements.size} → ${t.placements.size} placements")
    }

    private fun attributeNames(s: PatchRow, t: PatchRow) =
        (s.attributes.keys + t.attributes.keys).filter { s.attributes[it] != t.attributes[it] }.sorted()

    private fun universeRecord(u: UniverseRow) = AttachRecordDto(u.uuid.toString(), "universe ${u.universe}", u.number)
    private fun patchRecord(p: PatchRow) = AttachRecordDto(p.uuid.toString(), p.displayName, p.key)
    private fun hallRecord(r: HallRow) = AttachRecordDto(r.uuid.toString(), r.name)
    private fun groupRecord(g: GroupRow) = AttachRecordDto(g.uuid.toString(), g.name)

    private fun AttachRecordDto.label() = if (key != null && key != name) "$name ($key)" else name

    private fun PatchFacet.label() = name.lowercase()
}

/** What the pairing matches, hides and lists (§3.2): the six hideable kinds, universes, parks and the stage. */
@Serializable
enum class AttachKind(val hideKind: HideKind?, val label: String) {
    UNIVERSE(null, "universe"),
    PATCH(HideKind.PATCH, "fixture"),
    RIGGING(HideKind.RIGGING, "rigging"),
    REGION(HideKind.REGION, "stage region"),
    ELEMENT(HideKind.ELEMENT, "stage element"),
    VIEWPOINT(HideKind.VIEWPOINT, "viewpoint"),
    GROUP(HideKind.GROUP, "group"),
    PARK(null, "park"),
    STAGE(null, "stage"),
}

/** Whether the origin was used: the origin version is on this desk, missing from it, or of another venue (`FU-VENUE-TOURING`). */
@Serializable
enum class AttachOriginStatus { USED, MISSING, OTHER_VENUE }

@Serializable
data class AttachOriginDto(val venueUuid: String, val version: Int, val status: AttachOriginStatus, val detail: String)

/** A record named in the review: its uuid, its name, and a patch's key (a universe's `subnet:universe`). */
@Serializable
data class AttachRecordDto(val uuid: String, val name: String, val key: String? = null)

/**
 * One line of the review: its kind, the show's record and the venue's (either absent where there is
 * none), and why. [reason] is the line's code — `CHANGE`, `ADDITION`, `KEPT`, a hide's `KEPT` /
 * `REMOVED_SINCE_ORIGIN` / `KEY_CLASH` / `ANSWERED` / `RELEASE`, an incoming record's `NO_ORIGIN` /
 * `ADDED_SINCE_ORIGIN` / `HIDE_REFUSED` / `UNIVERSE`, a blocker's `KEY_CLASH` / `HEAD_CLASH` / `RENAME_TAKEN`.
 */
@Serializable
data class AttachItemDto(
    val kind: AttachKind,
    val show: AttachRecordDto? = null,
    val venue: AttachRecordDto? = null,
    val detail: String,
    val reason: String? = null,
)

/** A facet the show's record differs in, written as an override of the venue's patch. */
@Serializable
data class AttachOverrideDto(
    val venue: AttachRecordDto,
    val facet: PatchFacet,
    /** The show's value, in the venue's terms (universes, riggings and placements through the pairing). */
    val value: JsonElement,
    /** The target's value, or the origin's when both changed it (drift). */
    val base: JsonElement,
    /** The target version's value. */
    val venueValue: JsonElement,
    val drift: Boolean = false,
    /** The drift's decision, answered *Keep mine* (this override) or *Take venue's* (none). */
    val decisionId: String? = null,
    /** It names a universe or rigging the attached rig lacks, so it contributes nothing (`RigScope.overridesOf`). */
    val dormant: Boolean = false,
)

/** *Take venue's* on a differing key or group name: the show's references move from [from] to [to]. */
@Serializable
data class AttachRenameDto(
    val kind: AttachKind,
    val from: String,
    val to: String,
    val show: AttachRecordDto,
    val venue: AttachRecordDto,
    /** Who names [from] — cues, Looks, palettes, bindings and scripts — as the attach's review reads it before writing. */
    val usedBy: List<UsedByDto> = emptyList(),
)

/**
 * A choice the review offers, with its default applied unless answered: `IDENTITY` (D2), `HALL` and
 * `GROUP` (D3), `DRIFT` (both changed a facet) — each `KEEP_MINE | TAKE_VENUES` — and `HIDE` (without an
 * origin, an incoming record: `TAKE_VENUES` lets it in, `HIDE_VENUES` hides it).
 */
@Serializable
data class AttachDecisionDto(
    val id: String,
    val kind: String,
    val recordKind: AttachKind,
    val show: AttachRecordDto? = null,
    val venue: AttachRecordDto,
    val message: String,
    val answers: List<UpdateAnswer>,
    val answer: UpdateAnswer,
    val decided: Boolean = false,
    val bothChanged: Boolean = false,
    /** What *Take venue's* renames. */
    val rename: AttachRenameDto? = null,
)

/** One override the attach writes: the venue patch, the facet, the show's value and its base. */
data class PlannedOverride(val recordUuid: UUID, val facet: PatchFacet, val value: FacetValue, val base: FacetValue)

/**
 * What the attach (`VenueAttach`) writes, in §4.2's order. Every show row is named by its id; every venue
 * record by its uuid in the target version.
 */
data class AttachWrites(
    /** Show rows that go, by kind: show row id → the target row its links are re-pointed onto. */
    val replaced: Map<AttachKind, Map<Int, Int>>,
    /** Show rows that stay their own while a venue record matches them, by kind: row id → fresh uuid (P5). */
    val freshUuids: Map<AttachKind, Map<Int, UUID>>,
    /**
     * Every show record (and placement) uuid that changes, old → new: a replaced record's becomes the
     * target's, a kept one's its fresh uuid. What seat views' `seatElementUuid`, a platform's
     * `regionUuid` and the show's facet values are rewritten through.
     */
    val uuidRewrites: Map<UUID, UUID>,
    val overrides: List<PlannedOverride>,
    val hides: List<Pair<HideKind, UUID>>,
    val releases: List<ParkAddress>,
    /** The show's parks that go — the venue parks the channel at the same value — by the address their row has. */
    val parksDropped: List<ParkAddress>,
    /**
     * The show's parks that stay but move: on a universe that pairs with a venue universe of another
     * number, row address → the venue's number. A park names its universe by number, so it follows the
     * fixtures on it.
     */
    val parksMoved: Map<ParkAddress, ParkAddress> = emptyMap(),
    /** `machine_overrides` row ids that go: their records resolve against the venue's id now. */
    val machineOverridesDropped: List<Int>,
    /** `machine_overrides` row ids re-keyed to a kept record's fresh uuid. */
    val machineOverridesRekeyed: Map<Int, UUID>,
    /** *Take venue's* renames for `RigKeyReferences.rewrite`: fixture keys and group names, old → new. */
    val fixtureRenames: Map<String, String>,
    val groupRenames: Map<String, String>,
)

/**
 * The pairing's answer (§4.1, §4.2): the review — every list `AttachReviewDto` carries but the dead
 * references and P8's overlaps, which are judged on the written rig — and [writes], what the attach
 * writes. With a blocker the attach is refused (`ATTACH_BLOCKED`).
 */
data class AttachPlan(
    val venueUuid: UUID,
    val venueName: String,
    val to: Int,
    val origin: AttachOriginDto?,
    val output: List<AttachItemDto>,
    val drawing: List<AttachItemDto>,
    /** How many of the show's records the venue's replace, by kind. */
    val inherited: Map<AttachKind, Int>,
    val overrides: List<AttachOverrideDto>,
    val kept: List<AttachItemDto>,
    val hides: List<AttachItemDto>,
    val additions: List<AttachItemDto>,
    val incoming: List<AttachItemDto>,
    val renames: List<AttachRenameDto>,
    val decisions: List<AttachDecisionDto>,
    /**
     * Every decision id the pairing made — [decisions]' and those a clash then decided, which a review
     * made with other answers may have listed — so an answer to one is never refused as unknown.
     */
    val decisionIds: Set<String>,
    val blockers: List<AttachItemDto>,
    val writes: AttachWrites,
)
