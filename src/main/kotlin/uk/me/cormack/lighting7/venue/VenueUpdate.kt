package uk.me.cormack.lighting7.venue

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.telemetry.Tracing
import uk.me.cormack.lighting7.models.AddressFacet
import uk.me.cormack.lighting7.models.DaoBuskRigTiles
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueEvents
import uk.me.cormack.lighting7.models.DaoCueScenery
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackScenery
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroupMembers
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatchPlacements
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookScenery
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.DaoRiggings
import uk.me.cormack.lighting7.models.DaoShowHide
import uk.me.cormack.lighting7.models.DaoShowHides
import uk.me.cormack.lighting7.models.DaoShowOverride
import uk.me.cormack.lighting7.models.DaoShowOverrides
import uk.me.cormack.lighting7.models.DaoStageElement
import uk.me.cormack.lighting7.models.DaoStageElements
import uk.me.cormack.lighting7.models.DaoStageRegion
import uk.me.cormack.lighting7.models.DaoStageRegions
import uk.me.cormack.lighting7.models.DaoStageViewpoint
import uk.me.cormack.lighting7.models.DaoStageViewpoints
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.models.ParkAddress
import uk.me.cormack.lighting7.models.VenueParks
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.models.PositionFacet
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.VenueLinks
import uk.me.cormack.lighting7.models.facetElement
import uk.me.cormack.lighting7.state.State
import java.util.UUID

/**
 * *Update* a show to a newer version of its venue (venue plan §3.5, P3, P8; session 6): the review of
 * what it would do, then the update itself, in one transaction.
 *
 * **What an update does.** It moves the show's pin from version F to version T and re-points every
 * link the show's own rows hold into F's rows — a show patch on a venue universe or rigging, a show
 * group's venue member, a busk tile, a cue event, a cue's, stack's or Look's scenery — to T's row of
 * the same uuid, link by link through [VenueLinks.ALL] (so each link's unmatched rows can be dropped
 * as that link needs). Hides and facet overrides are keyed by uuid, so they
 * follow by themselves. What T no longer has is **dropped**: a link to a removed record goes (a member,
 * a tile, an event, a scenery row is deleted; a patch or placement hung on a removed rigging comes off
 * it, keeping its position), and so does a hide or an override of a record T lacks. Nothing is
 * converted and no key is rewritten: Look, template and cue rows name fixtures by key, so a key T
 * removes leaves them pointing at nothing, marked *Missing fixture* by the health check that exists.
 *
 * **What it asks first** (P8). An address overlap the update introduces between something the show
 * owns — its own patch, or a venue patch it re-addresses — and a venue patch is a **decision**:
 * *Keep both*, *Re-address mine*, *Hide the venue's* or *Remove mine*. A key or name clash with the
 * show's own records, or a show patch on a universe T removes, is a **blocker**: it cannot be kept, so
 * the show must change first. An update with an open decision is refused (`VENUE_UPDATE_UNDECIDED`),
 * one with a blocker likewise (`VENUE_UPDATE_BLOCKED`). A drifted override may be answered *Take
 * venue's* in the same request; left alone it is kept, and its drift shows on the Patch list after.
 *
 * **The review is the update rolled back** — as Extract's dry run is: the same code moves the pin and
 * re-points every link, reads the result, and throws it away, so the review can never disagree with
 * what Update will do.
 */
class VenueUpdate(private val state: State) {

    private val logger = LoggerFactory.getLogger(VenueUpdate::class.java)

    /** What the review says, or what the update did. */
    suspend fun review(projectId: Int, to: Int?): VenueUpdateDto = run(projectId, to, emptyList(), dryRun = true)

    /**
     * Update [projectId] to [to] (the installed version when null) with [decisions]; [dryRun] answers
     * without keeping anything. The caller reloads a live show.
     */
    suspend fun update(projectId: Int, to: Int?, decisions: List<UpdateDecisionAnswer>, dryRun: Boolean): VenueUpdateDto =
        run(projectId, to, decisions, dryRun)

    private suspend fun run(projectId: Int, toRequested: Int?, decisions: List<UpdateDecisionAnswer>, dryRun: Boolean): VenueUpdateDto =
        Tracing.transactionSuspending("venue.update", "venue.update") { span ->
            span?.setData("venue.dry_run", dryRun)
            runTraced(projectId, toRequested, decisions, dryRun)
        }

    private suspend fun runTraced(projectId: Int, toRequested: Int?, decisions: List<UpdateDecisionAnswer>, dryRun: Boolean): VenueUpdateDto =
        withContext(Dispatchers.IO) {
            val pin = transaction(state.database) { pinOf(projectId, toRequested) }
            // The hall's changes, read through the version cache before the transaction (snapshots never change).
            val hall = Tracing.span("venue.diff", "v${pin.from} to v${pin.to}") { state.venueVersions.diff(pin.venueId, pin.from, pin.to) }
            val result = transaction(state.database) {
                val outcome = apply(projectId, pin, decisions)
                val blocked = outcome.blockers.isNotEmpty()
                val undecided = outcome.decisions.any { !it.decided }
                if (dryRun || blocked || undecided) TransactionManager.current().rollback()
                if (!dryRun) {
                    if (blocked) {
                        throw VenueException.conflict(
                            CODE_UPDATE_BLOCKED,
                            "This show cannot move to v${pin.to} yet: " + outcome.blockers.joinToString("; ") { it.detail },
                        )
                    }
                    if (undecided) {
                        val open = outcome.decisions.filter { !it.decided }
                        throw VenueException.conflict(
                            CODE_UPDATE_UNDECIDED,
                            "${open.size} clash${if (open.size == 1) "" else "es"} need${if (open.size == 1) "s" else ""} a decision before " +
                                "the update: " + open.joinToString("; ") { it.message },
                        )
                    }
                }
                outcome
            }
            if (!dryRun) logger.info("Updated project {} from {} v{} to v{}", projectId, pin.venueName, pin.from, pin.to)
            VenueUpdateDto(
                projectId = projectId,
                venueId = pin.venueId,
                venueName = pin.venueName,
                from = pin.from,
                to = pin.to,
                fromNote = pin.fromNote,
                toNote = pin.toNote,
                output = hall.output,
                drawing = hall.drawing,
                carryOver = result.carryOver,
                drift = result.drift,
                dropped = result.dropped,
                decisions = result.decisions,
                blockers = result.blockers,
                deadReferences = result.dead,
                dryRun = dryRun,
                applied = !dryRun,
            )
        }

    // ── The pin ─────────────────────────────────────────────────────────────────

    private data class Pin(
        val venueId: Int,
        val venueName: String,
        val from: Int,
        val to: Int,
        val fromSnapshot: EntityID<Int>,
        val toSnapshot: EntityID<Int>,
        val fromNote: String?,
        val toNote: String?,
    )

    /** [projectId]'s pin and the version it would move to, or the refusal. Inside a transaction. */
    private fun pinOf(projectId: Int, toRequested: Int?): Pin {
        val project = DaoProject.findById(projectId)
            ?: throw VenueException(HttpStatusCode.NotFound, CODE_UPDATE_NOT_A_SHOW, "Project not found")
        if (!project.venueBound()) {
            throw VenueException.conflict(CODE_UPDATE_NOT_A_SHOW, "This project is not a show on a venue: there is nothing to update")
        }
        if (VenuePins.isMissing(project)) throw VenuePins.missing(project)
        val venueUuid = project.venueUuid!!
        val from = project.venueVersion!!
        val venue = VenuePins.venueOf(venueUuid)!!
        val versions = VenueVersions.versionsOf(venue.id.value)
        val installed = versions.first().number
        val to = toRequested ?: installed
        val target = versions.firstOrNull { it.number == to }
            ?: throw VenueException(HttpStatusCode.NotFound, VenueVersions.CODE_VERSION_UNKNOWN, "${venue.name} has no v$to")
        if (to <= from) {
            throw VenueException.conflict(
                CODE_UP_TO_DATE,
                if (to == from) "This show is on ${venue.name} v$from already" else "An update moves a show forward: v$to is older than its v$from",
            )
        }
        val source = versions.first { it.number == from }
        return Pin(venue.id.value, venue.name, from, to, source.snapshotProject.id, target.snapshotProject.id, source.note, target.note)
    }

    private fun DaoProject.venueBound(): Boolean = venueUuid != null && venueVersion != null && kind == ProjectKind.SHOW

    // ── The update, inside the caller's transaction ─────────────────────────────

    private class Outcome(
        val carryOver: List<UpdateItemDto>,
        val drift: List<UpdateDriftDto>,
        val dropped: List<UpdateItemDto>,
        val decisions: List<UpdateDecisionDto>,
        val blockers: List<UpdateItemDto>,
        val dead: List<DeadReferenceDto>,
    )

    private fun apply(projectId: Int, pin: Pin, answers: List<UpdateDecisionAnswer>): Outcome {
        val project = DaoProject[projectId]
        val before = RigScope.of(project)
        val beforeSpans = VenueRigChecks.spansOf(before)
        val beforeKeys = VenueRigChecks.keysOf(before)

        val dropped = mutableListOf<UpdateItemDto>()
        val blockers = mutableListOf<UpdateItemDto>()

        // Hides of records the target lacks: there is nothing left to hide.
        val hides = DaoShowHide.find { DaoShowHides.project eq project.id }.toList()
        val droppedHides = hides.filter { recordIn(pin.toSnapshot, it.recordKind, it.recordUuid) == null }
        for (hide in droppedHides) {
            val name = recordIn(pin.fromSnapshot, hide.recordKind, hide.recordUuid) ?: hide.recordUuid.toString()
            dropped += UpdateItemDto("HIDE", name, "Your hide of ${hide.recordKind.label()} $name: v${pin.to} has no such ${hide.recordKind.label()}", hide.recordUuid.toString())
        }

        // Overrides judged against the target: drift is kept (or taken back by a decision), a missing subject dropped.
        val drifts = ShowOverrides.drift(project, pin.toSnapshot)
        val fromPatches = DaoFixturePatch.find { DaoFixturePatches.project eq pin.fromSnapshot }.associateBy { it.uuid }
        val missing = drifts.filter { it.missing }
        for (d in missing) {
            val name = fromPatches[d.recordUuid]?.let { "${it.displayName} (${it.key})" } ?: d.recordUuid.toString()
            dropped += UpdateItemDto(
                "OVERRIDE", name,
                "Your ${d.facet.name.lowercase()} override on $name: it is gone in v${pin.to}, so the override has nothing to apply to",
                d.recordUuid.toString(),
            )
        }
        val takeVenue = answers.filter { it.answer == UpdateAnswer.TAKE_VENUES }.map { it.id }.toSet()
        val driftDtos = drifts.filter { it.drifted }.map { d ->
            val id = driftId(d.recordUuid, d.facet)
            UpdateDriftDto(
                id = id,
                recordUuid = d.recordUuid.toString(),
                patchKey = d.patchKey,
                patchName = fromPatches[d.recordUuid]?.displayName,
                facet = d.facet,
                value = d.value?.let(::facetElement),
                venue = d.current?.let(::facetElement),
                base = d.base?.let(::facetElement),
                answer = if (id in takeVenue) UpdateAnswer.TAKE_VENUES else UpdateAnswer.KEEP_MINE,
            )
        }

        // Move the pin, then every link.
        project.venueVersion = pin.to
        val outcomes = VenueLinks.ALL.associateWith { it.repoint(project.id, pin.fromSnapshot, pin.toSnapshot) }
        for ((link, outcome) in outcomes) {
            if (outcome.unmatched.isEmpty()) continue
            val targets = link.table.select(link.table.id, link.column)
                .where { link.table.id inList outcome.unmatched.map { EntityID(it, link.table) } }
                .associate { it[link.table.id].value to it[link.column]?.value }
            for (rowId in outcome.unmatched) {
                val target = targets[rowId] ?: continue
                dropLink(link, rowId, target, pin, dropped, blockers)
            }
        }
        // The dropped hides and overrides go once the links are clear of them.
        droppedHides.forEach { it.delete() }
        if (missing.isNotEmpty()) {
            DaoShowOverride.find { DaoShowOverrides.project eq project.id }
                .filter { o -> missing.any { it.recordUuid == o.recordUuid && it.facet == o.facet } }
                .forEach { it.delete() }
        }
        // *Take venue's* on a drift is a revert.
        if (takeVenue.isNotEmpty()) {
            DaoShowOverride.find { DaoShowOverrides.project eq project.id }
                .filter { driftId(it.recordUuid, it.facet) in takeVenue }
                .forEach { it.delete() }
        }

        // The rig as the target sees it: clashes, the overlaps the update introduces, what goes dark.
        val after = RigScope.of(project)
        blockers += VenueRigChecks.clashesOf(after, pin.toSnapshot, "v${pin.to}").map { UpdateItemDto("KEY_CLASH", it.name, it.detail) }
        val afterSpans = VenueRigChecks.spansOf(after)
        val beforePairs = VenueRigChecks.overlapPairs(beforeSpans)
        val introduced = VenueRigChecks.overlapPairs(afterSpans).filter { it.key !in beforePairs.keys }.values.toList()
        // Suggestions are made in turn, each avoiding the ones before it, so two units re-addressed in
        // one update are never offered the same channels.
        val suggested = HashMap<UUID, VenueRigChecks.Span>()
        val decisions = introduced.mapNotNull { (a, b) -> VenueRigChecks.decisionOf(project, a, b, afterSpans, suggested) }
        val driftIds = driftDtos.map { it.id }.toSet()
        val knownIds = decisions.map { it.id }.toSet() + driftIds
        answers.firstOrNull { it.id !in knownIds }?.let { bad ->
            throw VenueException.badRequest(CODE_UPDATE_INVALID, "No decision '${bad.id}' in this update")
        }
        answers.firstOrNull { it.id in driftIds && it.answer !in DRIFT_ANSWERS }?.let { bad ->
            throw VenueException.badRequest(CODE_UPDATE_INVALID, "'${bad.answer}' does not answer '${bad.id}': it takes ${DRIFT_ANSWERS.joinToString(" | ")}")
        }
        val decided = decisions.map { d ->
            val answer = answers.firstOrNull { it.id == d.id }
            if (answer != null && answer.answer !in d.answers) {
                throw VenueException.badRequest(
                    CODE_UPDATE_INVALID,
                    "'${answer.answer}' does not answer '${d.id}': it takes ${d.answers.joinToString(" | ")}",
                )
            }
            d.copy(decided = answer != null, answer = answer?.answer, startChannel = answer?.startChannel)
        }
        val warnings = VenueRigChecks.applyDecisions(project, decided, afterSpans, CODE_UPDATE_INVALID)

        val dead = VenueRigChecks.deadReferences(project, beforeKeys, VenueRigChecks.keysOf(RigScope.of(project)), "is gone in v${pin.to}") +
            dormantOverrides(RigScope.of(project), pin)

        val carryOver = carryOverOf(project, hides - droppedHides.toSet(), drifts, decided, takeVenue, pin)
        return Outcome(
            carryOver = carryOver,
            drift = driftDtos,
            dropped = dropped,
            decisions = decided.map { if (warnings[it.id] != null) it.copy(message = "${it.message} ${warnings[it.id]}") else it },
            blockers = blockers,
            dead = dead,
        )
    }

    // ── Dropped links ───────────────────────────────────────────────────────────

    /** One of the show's rows linking to [targetId], a row of the pinned version that the target lacks. */
    private fun dropLink(
        link: VenueLinks.Link,
        rowId: Int,
        targetId: Int,
        pin: Pin,
        dropped: MutableList<UpdateItemDto>,
        blockers: MutableList<UpdateItemDto>,
    ) {
        val what = nameOf(link.target, targetId)
        val v = "v${pin.to}"
        when (link.table) {
            DaoFixturePatches -> {
                val patch = DaoFixturePatch[rowId]
                if (link.target == DaoUniverseConfigs) {
                    // A universe cannot be hidden or come off: the show's patch must move first.
                    blockers += UpdateItemDto(
                        "UNIVERSE_REMOVED", "${patch.displayName} (${patch.key})",
                        "your ${patch.displayName} (${patch.key}) is patched on $what, which $v removes — re-patch it onto another universe, or remove it, first",
                        patch.uuid.toString(),
                    )
                    return
                }
                DaoFixturePatches.update({ DaoFixturePatches.id eq rowId }) { it[DaoFixturePatches.rigging] = null }
                dropped += UpdateItemDto("LINK", "${patch.displayName} (${patch.key})", "Your ${patch.displayName} hung on $what, which $v removes: it comes off it and keeps its position", patch.uuid.toString())
            }
            DaoFixturePatchPlacements -> {
                check(link.target == DaoRiggings) { "Unexpected unmatched link ${link.name}" }
                val patchId = DaoFixturePatchPlacements.select(DaoFixturePatchPlacements.fixturePatch)
                    .where { DaoFixturePatchPlacements.id eq rowId }.single()[DaoFixturePatchPlacements.fixturePatch]
                val patch = DaoFixturePatch[patchId]
                DaoFixturePatchPlacements.update({ DaoFixturePatchPlacements.id eq rowId }) { it[DaoFixturePatchPlacements.rigging] = null }
                dropped += UpdateItemDto("LINK", "${patch.displayName} (${patch.key})", "A placement of your ${patch.displayName} hung on $what, which $v removes: it comes off it", patch.uuid.toString())
            }
            DaoFixtureGroupMembers -> {
                check(link.target == DaoFixturePatches) { "Unexpected unmatched link ${link.name}" }
                val groupId = DaoFixtureGroupMembers.select(DaoFixtureGroupMembers.group)
                    .where { DaoFixtureGroupMembers.id eq rowId }.single()[DaoFixtureGroupMembers.group]
                deleteRow(link.table, rowId)
                dropped += UpdateItemDto("LINK", what, "Your group ${DaoFixtureGroup[groupId].name} loses $what, which $v removes")
            }
            DaoBuskRigTiles -> {
                deleteRow(link.table, rowId)
                dropped += UpdateItemDto("LINK", what, "The busk rig's tile for $what goes: $v removes it")
            }
            DaoCueEvents -> {
                val cueId = DaoCueEvents.select(DaoCueEvents.cue).where { DaoCueEvents.id eq rowId }.single()[DaoCueEvents.cue]
                deleteRow(link.table, rowId)
                dropped += UpdateItemDto("LINK", what, "${VenueRigChecks.cueLabel(DaoCue[cueId])}'s event on $what goes: $v removes it")
            }
            DaoCueScenery -> {
                val cueId = DaoCueScenery.select(DaoCueScenery.cue).where { DaoCueScenery.id eq rowId }.single()[DaoCueScenery.cue]
                deleteRow(link.table, rowId)
                dropped += UpdateItemDto("LINK", what, "${VenueRigChecks.cueLabel(DaoCue[cueId])}'s scenery change to $what goes: $v removes it")
            }
            DaoCueStackScenery -> {
                val stackId = DaoCueStackScenery.select(DaoCueStackScenery.stack).where { DaoCueStackScenery.id eq rowId }.single()[DaoCueStackScenery.stack]
                deleteRow(link.table, rowId)
                dropped += UpdateItemDto("LINK", what, "Stack ${DaoCueStack[stackId].name}'s set of $what goes: $v removes it")
            }
            DaoLookScenery -> {
                val lookId = DaoLookScenery.select(DaoLookScenery.look).where { DaoLookScenery.id eq rowId }.single()[DaoLookScenery.look]
                deleteRow(link.table, rowId)
                dropped += UpdateItemDto("LINK", what, "Look ${DaoLook[lookId].name}'s scenery on $what goes: $v removes it")
            }
            // A placement or member belongs to its patch or group, which a show owns: never a venue row.
            else -> error("Unexpected unmatched link ${link.name} row $rowId")
        }
    }

    private fun deleteRow(table: IntIdTable, rowId: Int) {
        table.deleteWhere { table.id eq rowId }
    }

    private fun nameOf(table: IntIdTable, id: Int): String = when (table) {
        DaoFixturePatches -> DaoFixturePatch.findById(id)?.let { "${it.displayName} (${it.key})" }
        DaoUniverseConfigs -> DaoUniverseConfig.findById(id)?.let { "universe ${it.universe}" }
        DaoRiggings -> DaoRigging.findById(id)?.name
        DaoFixtureGroups -> DaoFixtureGroup.findById(id)?.let { "group ${it.name}" }
        DaoStageElements -> DaoStageElement.findById(id)?.name
        else -> null
    } ?: "a record"

    /** The show's overrides the target leaves dormant: an address or position naming a universe or rigging it lacks. */
    private fun dormantOverrides(rig: RigScope, pin: Pin): List<DeadReferenceDto> {
        val venue = rig.venue ?: return emptyList()
        val patches = rig.patches(DaoFixturePatches.project eq venue.snapshotId).associateBy { it.uuid }
        return rig.overrides.flatMap { (uuid, facets) ->
            val patch = patches[uuid] ?: return@flatMap emptyList()
            val applied = rig.overridesOf(patch)
            facets.keys.filter { it !in applied }.map { facet ->
                val names = when (facets.getValue(facet)) {
                    is AddressFacet -> "a universe"
                    is PositionFacet -> "a rigging"
                    else -> "a record"
                }
                DeadReferenceDto(
                    kind = "OVERRIDE",
                    key = patch.key,
                    name = patch.displayName,
                    detail = "Your ${facet.name.lowercase()} override on ${patch.displayName} names $names v${pin.to} doesn't have: " +
                        "it stays, dormant, and the venue's ${facet.name.lowercase()} applies until you change it",
                )
            }
        }
    }

    // ── Carry over ──────────────────────────────────────────────────────────────

    private fun carryOverOf(
        project: DaoProject,
        hides: List<DaoShowHide>,
        drifts: List<ShowOverrides.FacetDrift>,
        decisions: List<UpdateDecisionDto>,
        takeVenue: Set<String>,
        pin: Pin,
    ): List<UpdateItemDto> {
        val out = mutableListOf<UpdateItemDto>()
        val toPatches = DaoFixturePatch.find { DaoFixturePatches.project eq pin.toSnapshot }.associateBy { it.uuid }
        for (d in drifts) {
            if (d.missing || d.drifted || driftId(d.recordUuid, d.facet) in takeVenue) continue
            val p = toPatches[d.recordUuid]
            out += UpdateItemDto("OVERRIDE", p?.displayName ?: d.recordUuid.toString(), "Your ${d.facet.name.lowercase()} override on ${p?.displayName ?: "a venue fixture"}", d.recordUuid.toString())
        }
        for (h in hides) {
            val name = recordIn(pin.toSnapshot, h.recordKind, h.recordUuid) ?: continue
            out += UpdateItemDto("HIDE", name, "Your hide of ${h.recordKind.label()} $name", h.recordUuid.toString())
        }
        val decidedMine = decisions.map { it.mine.uuid }.toSet()
        for (p in RigScope.of(project).own.patches()) {
            if (p.uuid.toString() in decidedMine) continue
            out += UpdateItemDto("ADDITION", "${p.displayName} (${p.key})", "Your ${p.displayName}", p.uuid.toString())
        }
        out += parkCarryOver(project, pin)
        return out
    }

    /**
     * The show's own parks and its releases of venue parks: keyed by channel, they follow the pin by
     * themselves, and the show's word on a channel stands over whatever the target parks there — which
     * is why a venue park change under one is not output for this show (`ShowBehind`). Each says what
     * the venue does on its channel from the pin to the target.
     */
    private fun parkCarryOver(project: DaoProject, pin: Pin): List<UpdateItemDto> {
        val from = VenueParks.rowsOf(pin.fromSnapshot)
        val to = VenueParks.rowsOf(pin.toSnapshot)
        fun venueMove(address: ParkAddress): String? {
            val a = from[address]
            val b = to[address]
            return when {
                a == b -> null
                a == null -> "v${pin.to} parks it at $b"
                b == null -> "v${pin.to} no longer parks it"
                else -> "the venue's park moves from $a to $b in v${pin.to}"
            }
        }
        val parks = VenueParks.rowsOf(project.id).toSortedMap().map { (address, value) ->
            val detail = venueMove(address)?.let { "Your park of $address at $value stays: $it" }
                ?: to[address]?.let { "Your park of $address at $value, over the venue's at $it" }
                ?: "Your park of $address at $value"
            UpdateItemDto("PARK", "Park $address", detail)
        }
        val releases = VenueParks.releasesOf(project.id).sorted().map { address ->
            val detail = when {
                to[address] == null -> "Your release of the venue's park on $address: v${pin.to} parks nothing there, so it releases nothing (kept)"
                else -> venueMove(address)?.let { "Your release of the venue's park on $address stays: $it" }
                    ?: "Your release of the venue's park on $address"
            }
            UpdateItemDto("RELEASE", "Park $address", detail)
        }
        return parks + releases
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /** The name of the record of [kind] with [uuid] in [snapshot], or null when it has none. */
    private fun recordIn(snapshot: EntityID<Int>, kind: HideKind, uuid: UUID): String? = when (kind) {
        HideKind.PATCH -> DaoFixturePatch.find { (DaoFixturePatches.project eq snapshot) and (DaoFixturePatches.uuid eq uuid) }.firstOrNull()?.let { "${it.displayName} (${it.key})" }
        HideKind.RIGGING -> DaoRigging.find { (DaoRiggings.project eq snapshot) and (DaoRiggings.uuid eq uuid) }.firstOrNull()?.name
        HideKind.ELEMENT -> DaoStageElement.find { (DaoStageElements.project eq snapshot) and (DaoStageElements.uuid eq uuid) }.firstOrNull()?.name
        HideKind.REGION -> DaoStageRegion.find { (DaoStageRegions.project eq snapshot) and (DaoStageRegions.uuid eq uuid) }.firstOrNull()?.name
        HideKind.GROUP -> DaoFixtureGroup.find { (DaoFixtureGroups.project eq snapshot) and (DaoFixtureGroups.uuid eq uuid) }.firstOrNull()?.name
        HideKind.VIEWPOINT -> DaoStageViewpoint.find { (DaoStageViewpoints.project eq snapshot) and (DaoStageViewpoints.uuid eq uuid) }.firstOrNull()?.name
    }

    private fun HideKind.label() = when (this) {
        HideKind.PATCH -> "fixture"
        HideKind.RIGGING -> "rigging"
        HideKind.ELEMENT -> "stage element"
        HideKind.REGION -> "stage region"
        HideKind.GROUP -> "group"
        HideKind.VIEWPOINT -> "viewpoint"
    }

    companion object {
        const val CODE_UPDATE_UNDECIDED = "VENUE_UPDATE_UNDECIDED"
        const val CODE_UPDATE_BLOCKED = "VENUE_UPDATE_BLOCKED"
        const val CODE_UPDATE_INVALID = "VENUE_UPDATE_INVALID"
        const val CODE_UPDATE_NOT_A_SHOW = "NOT_A_VENUE_SHOW"
        const val CODE_UP_TO_DATE = "VENUE_UP_TO_DATE"

        private val DRIFT_ANSWERS = listOf(UpdateAnswer.KEEP_MINE, UpdateAnswer.TAKE_VENUES)

        fun driftId(recordUuid: UUID, facet: PatchFacet) = "drift:$recordUuid:${facet.name}"
    }
}

/** An answer to one of the review's decisions. */
@Serializable
enum class UpdateAnswer { KEEP_BOTH, READDRESS_MINE, HIDE_VENUES, REMOVE_MINE, KEEP_MINE, TAKE_VENUES }

/** One decision answered in an update request; [startChannel] only with `READDRESS_MINE` (default: the suggestion). */
@Serializable
data class UpdateDecisionAnswer(val id: String, val answer: UpdateAnswer, val startChannel: Int? = null)

@Serializable
data class VenueUpdateRequest(
    /** The version to move to; null is the installed one. */
    val to: Int? = null,
    val decisions: List<UpdateDecisionAnswer> = emptyList(),
    val dryRun: Boolean = false,
)

@Serializable
data class VenueRunAgainstRequest(val on: Boolean)

/** One line of the review: a record carried over, dropped, or blocking. */
@Serializable
data class UpdateItemDto(
    /** `OVERRIDE | HIDE | ADDITION | PARK | RELEASE | LINK | KEY_CLASH | UNIVERSE_REMOVED`. */
    val kind: String,
    val name: String,
    val detail: String,
    val recordUuid: String? = null,
)

/** An override the venue moved underneath (drift): kept unless answered `TAKE_VENUES`. */
@Serializable
data class UpdateDriftDto(
    val id: String,
    val recordUuid: String,
    val patchKey: String? = null,
    val patchName: String? = null,
    val facet: PatchFacet,
    /** The show's value of the facet. */
    val value: JsonElement? = null,
    /** The target version's value. */
    val venue: JsonElement? = null,
    /** The venue's value when the override was made (or last kept). */
    val base: JsonElement? = null,
    val answer: UpdateAnswer = UpdateAnswer.KEEP_MINE,
)

/** One side of a decision: a unit, its address in the updated rig, and whose it is (`OWN | OVERRIDE | VENUE`). */
@Serializable
data class UpdateUnitDto(
    val uuid: String,
    val patchId: Int,
    val key: String,
    val name: String,
    val address: String,
    val kind: String,
)

/** A clash the update introduces that needs an answer (P8). */
@Serializable
data class UpdateDecisionDto(
    val id: String,
    val kind: String,
    val message: String,
    val mine: UpdateUnitDto,
    val other: UpdateUnitDto,
    val answers: List<UpdateAnswer>,
    val suggestedStartChannel: Int? = null,
    /** `2-480`: where *Re-address mine* would put it. */
    val suggestedAddress: String? = null,
    val decided: Boolean = false,
    val answer: UpdateAnswer? = null,
    val startChannel: Int? = null,
)

/** Who names a dead key. */
@Serializable
data class UsedByDto(val kind: String, val id: Int, val name: String)

/** A key the update leaves pointing at nothing, or an override it leaves dormant. */
@Serializable
data class DeadReferenceDto(
    /** `FIXTURE | GROUP | OVERRIDE`. */
    val kind: String,
    val key: String,
    val name: String? = null,
    val usedBy: List<UsedByDto> = emptyList(),
    val detail: String,
)

/** The review, or what an update did. */
@Serializable
data class VenueUpdateDto(
    val projectId: Int,
    val venueId: Int,
    val venueName: String,
    val from: Int,
    val to: Int,
    val fromNote: String? = null,
    val toNote: String? = null,
    /** What changed in the hall from [from] to [to], as History's diff classifies it. */
    val output: List<VenueChangeDto> = emptyList(),
    val drawing: List<VenueChangeDto> = emptyList(),
    val carryOver: List<UpdateItemDto> = emptyList(),
    val drift: List<UpdateDriftDto> = emptyList(),
    val dropped: List<UpdateItemDto> = emptyList(),
    val decisions: List<UpdateDecisionDto> = emptyList(),
    val blockers: List<UpdateItemDto> = emptyList(),
    val deadReferences: List<DeadReferenceDto> = emptyList(),
    val dryRun: Boolean = true,
    val applied: Boolean = false,
)
