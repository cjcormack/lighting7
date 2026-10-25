package uk.me.cormack.lighting7.venue

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.ai.RigKeyReferences
import uk.me.cormack.lighting7.models.DaoEffectTubeStates
import uk.me.cormack.lighting7.models.DaoShowColour
import uk.me.cormack.lighting7.models.DaoFixtureGroupMembers
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatchPlacements
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoMachineOverrides
import uk.me.cormack.lighting7.models.DaoParkedChannels
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoRiggings
import uk.me.cormack.lighting7.models.DaoShowHide
import uk.me.cormack.lighting7.models.DaoShowHides
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.models.DaoShowOverride
import uk.me.cormack.lighting7.models.DaoShowParkRelease
import uk.me.cormack.lighting7.models.DaoStageElements
import uk.me.cormack.lighting7.models.DaoStageRegions
import uk.me.cormack.lighting7.models.DaoStageViewpoints
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.ParkAddress
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.StageElementKind
import uk.me.cormack.lighting7.models.VenueLinks
import uk.me.cormack.lighting7.models.encodeFacet
import uk.me.cormack.lighting7.models.storedParamsObject
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.telemetry.Tracing
import java.util.UUID

/**
 * *Attach to venue…* (venue-attach plan §4, D1–D4, P2–P6): put a standalone show onto a venue version
 * — the show's rows that are the venue's records go, its differences become what a show on a venue
 * already holds (facet overrides, hides, releases and its own additions), and the pin comes back.
 *
 * **What it writes** is the plan [VenueAttachPairing] answers, in one transaction and in §4.2's order:
 * every link from a replaced show row moved onto the target row it pairs with — by row, not by uuid,
 * since a pair made by key has two ([VenueLinks.Link.repointByIds]; a placement's patch and a member's
 * group are ownership, and go with their rows); the show's uuid-keyed references to a paired record
 * (a seat view's seating, a platform's region) rewritten, and its machine overrides of replaced records
 * dropped and of kept ones re-keyed; the pin written and the origin cleared; the replaced rows deleted,
 * children first; then the overrides with their base, the hides, the releases, a fresh uuid for every
 * kept row (P5), and *Take venue's* renames through [RigKeyReferences.rewrite].
 *
 * **Then the venue's guards**, on a fresh [RigScope], as Update runs them ([VenueRigChecks]): a key,
 * head number or name a show record shares with a visible venue record is a blocker, and an address
 * overlap the attach introduces — one that was not between the same two units before — is P8's
 * decision. Last, the keys leaving the rig that the show's rows still name, and every script naming a
 * renamed key (scripts are never rewritten), are its dead references.
 *
 * **The review is the attach rolled back** (P4): the same run, thrown away on a dry run, a blocker or an
 * overlap left unanswered — so the review can never promise what the attach won't do.
 */
class VenueAttach(private val state: State) {

    private val logger = LoggerFactory.getLogger(VenueAttach::class.java)

    /** What a run answers, and what its renames touched (for the live desk's announcements). */
    internal class Result(val review: AttachReviewDto, val touched: RigKeyReferences.Touched)

    /** The review of attaching [projectId] to venue [venueId] at [to] (the installed version when null). */
    suspend fun review(projectId: Int, venueId: Int, to: Int?, decisions: List<UpdateDecisionAnswer> = emptyList()): AttachReviewDto =
        run(projectId, venueId, to, decisions, dryRun = true).review

    /** Attach [projectId] with [decisions]; [dryRun] answers without keeping anything. The caller reloads a live show. */
    internal suspend fun attach(projectId: Int, venueId: Int, to: Int?, decisions: List<UpdateDecisionAnswer>, dryRun: Boolean): Result =
        run(projectId, venueId, to, decisions, dryRun)

    private suspend fun run(projectId: Int, venueId: Int, to: Int?, decisions: List<UpdateDecisionAnswer>, dryRun: Boolean): Result =
        Tracing.transactionSuspending("venue.attach", "venue.attach") { span ->
            span?.setData("venue.dry_run", dryRun)
            withContext(Dispatchers.IO) {
                // Refused before anything is read, so a project that is not a standalone show is answered by name.
                val behindGap = transaction(state.database) {
                    requireStandalone(projectId)
                    val versions = VenueVersions.versionsOf(venueId)
                    val installed = versions.firstOrNull()?.number
                    val number = to ?: installed
                    if (installed != null && number != null && number < installed && versions.any { it.number == number }) number to installed else null
                }
                // An attach behind is judged inside the transaction, against the pin it writes; the versions'
                // diff exports them, so it is read here first, into the cache, off the desk's one connection.
                behindGap?.let { (from, installed) -> runCatching { state.venueVersions.diff(venueId, from, installed) } }
                val result = transaction(state.database) {
                    val outcome = apply(projectId, venueId, to, decisions)
                    val review = outcome.review
                    val blocked = review.blockers.isNotEmpty()
                    val undecided = review.overlaps.filter { !it.decided }
                    if (dryRun || blocked || undecided.isNotEmpty()) TransactionManager.current().rollback()
                    if (!dryRun) {
                        if (blocked) {
                            throw VenueException.conflict(
                                CODE_ATTACH_BLOCKED,
                                "This show cannot attach to ${review.venueName} v${review.to} yet: " + review.blockers.joinToString("; ") { it.detail },
                            )
                        }
                        if (undecided.isNotEmpty()) {
                            throw VenueException.conflict(
                                CODE_ATTACH_UNDECIDED,
                                "${undecided.size} clash${if (undecided.size == 1) "" else "es"} need${if (undecided.size == 1) "s" else ""} " +
                                    "a decision before the attach: " + undecided.joinToString("; ") { it.message },
                            )
                        }
                    }
                    Result(review.copy(dryRun = dryRun, applied = !dryRun), outcome.touched)
                }
                if (!dryRun) logger.info("Attached project {} to {} v{}", projectId, result.review.venueName, result.review.to)
                result
            }
        }

    /** [projectId] as a standalone show, or the refusal. */
    private fun requireStandalone(projectId: Int): DaoProject {
        val project = DaoProject.findById(projectId)
            ?: throw VenueException(HttpStatusCode.NotFound, CODE_NOT_STANDALONE, "Project not found")
        if (project.kind != ProjectKind.SHOW || project.venueUuid != null) {
            val what = when (project.kind) {
                ProjectKind.VENUE -> "a venue"
                ProjectKind.VENUE_VERSION -> "a venue version"
                ProjectKind.SHOW -> "already a show on a venue: detach it first, or update it"
            }
            throw VenueException.conflict(CODE_NOT_STANDALONE, "${project.name} is $what — only a standalone show attaches to a venue")
        }
        return project
    }

    private class Outcome(val review: AttachReviewDto, val touched: RigKeyReferences.Touched)

    /** The attach itself, §4.2's steps in order, inside the caller's transaction. */
    private fun apply(projectId: Int, venueId: Int, to: Int?, answers: List<UpdateDecisionAnswer>): Outcome {
        val show = requireStandalone(projectId)

        val inputs = VenueAttachRows.inputs(show, venueId, to)
        // A kept row's fresh uuid is a function of the show and the row, so the review and the attach
        // (and an overlap's id, which names one) agree whatever the answers.
        val plan = VenueAttachPairing.pair(inputs.show, inputs.origin, inputs.target, answers) { old ->
            UUID.nameUUIDFromBytes("attach:${show.uuid}:$old".toByteArray())
        }
        val w = plan.writes
        val venue = DaoProject[venueId]
        val versions = VenueVersions.versionsOf(venueId)
        val installed = versions.first().number
        val snapshotId = versions.first { it.number == plan.to }.snapshotProject.id

        // The show as it stands, for the guards' before and each rename's reach.
        val before = RigScope.of(show)
        val beforeKeys = VenueRigChecks.keysOf(before)
        val patchedKeys = beforeKeys.fixtures.keys
        val beforeOverlaps = overlappingPairs(VenueRigChecks.spansOf(before)) { w.uuidRewrites[it] ?: it }
        val renamesOffered = (plan.renames + plan.decisions.mapNotNull { it.rename }).distinctBy { it.kind to it.from }
        val reach = RigKeyReferences.users(
            show,
            renamesOffered.filter { it.kind == AttachKind.PATCH }.map { it.from }.toSet(),
            renamesOffered.filter { it.kind == AttachKind.GROUP }.map { it.from }.toSet(),
            patchedKeys,
        )
        fun AttachRenameDto.withReach(): AttachRenameDto {
            val name = if (kind == AttachKind.PATCH) "fixture:$from" else "group:$from"
            return copy(usedBy = reach[name].orEmpty().map(VenueRigChecks::usedBy))
        }

        for (link in VenueLinks.ALL) {
            if (link.isOwnership) continue
            link.repointByIds(show.id, w.replaced[kindOf(link.target)].orEmpty())
        }

        rewriteReferences(show.id, w.uuidRewrites)
        if (w.machineOverridesDropped.isNotEmpty()) {
            DaoMachineOverrides.deleteWhere { DaoMachineOverrides.id inList w.machineOverridesDropped.map { EntityID(it, DaoMachineOverrides) } }
        }
        for ((id, uuid) in w.machineOverridesRekeyed) {
            DaoMachineOverrides.update({ DaoMachineOverrides.id eq id }) { it[recordUuid] = uuid }
        }
        moveTubeStates(inputs.show.patches.mapNotNull { p -> w.uuidRewrites[p.uuid]?.let { p.uuid to it } }.toMap())

        show.venueUuid = venue.uuid
        show.venueVersion = plan.to
        show.venueRepo = VenuePins.repoHintOf(venueId)
        show.detachedFromVenueUuid = null
        show.detachedFromVersion = null

        val replacedPatches = ids(w, AttachKind.PATCH, DaoFixturePatches)
        val replacedGroups = ids(w, AttachKind.GROUP, DaoFixtureGroups)
        if (replacedPatches.isNotEmpty()) DaoFixturePatchPlacements.deleteWhere { fixturePatch inList replacedPatches }
        if (replacedGroups.isNotEmpty()) DaoFixtureGroupMembers.deleteWhere { group inList replacedGroups }
        deleteReplaced(w, AttachKind.PATCH, DaoFixturePatches)
        deleteReplaced(w, AttachKind.GROUP, DaoFixtureGroups)
        deleteReplaced(w, AttachKind.RIGGING, DaoRiggings)
        deleteReplaced(w, AttachKind.REGION, DaoStageRegions)
        deleteReplaced(w, AttachKind.ELEMENT, DaoStageElements)
        deleteReplaced(w, AttachKind.VIEWPOINT, DaoStageViewpoints)
        deleteReplaced(w, AttachKind.UNIVERSE, DaoUniverseConfigs)
        for (address in w.parksDropped) {
            DaoParkedChannels.deleteWhere {
                (DaoParkedChannels.project eq show.id) and (DaoParkedChannels.universe eq address.universe) and
                    (DaoParkedChannels.channel eq address.channel)
            }
        }
        moveParks(show.id, w.parksMoved)

        for (o in w.overrides) {
            DaoShowOverride.new {
                project = show
                recordUuid = o.recordUuid
                facet = o.facet
                value = encodeFacet(o.value)
                base = encodeFacet(o.base)
            }
        }
        for ((recordUuid, colour) in w.colours) {
            DaoShowColour.new {
                project = show
                this.recordUuid = recordUuid
                this.colour = colour
            }
        }
        for ((kind, uuid) in w.hides) {
            DaoShowHide.new {
                project = show
                recordKind = kind
                recordUuid = uuid
            }
        }
        for (address in w.releases) {
            DaoShowParkRelease.new {
                project = show
                universe = address.universe
                channel = address.channel
            }
        }
        writeFreshUuids(w)
        val renamedPatchIds = inputs.show.patches.filter { it.key in w.fixtureRenames }
            .mapNotNull { w.replaced[AttachKind.PATCH]?.get(it.id) }
        val touched = RigKeyReferences.rewrite(show, w.fixtureRenames, w.groupRenames, renamedPatchIds, patchedKeys)

        val after = RigScope.of(show)
        val named = plan.blockers.mapNotNull { b -> b.show?.let { b.kind to (it.key ?: it.name) } }.toSet()
        val clashes = VenueRigChecks.clashesOf(after, snapshotId, "v${plan.to}")
            .filter { (it.kind to it.name) !in named }
            .map { AttachItemDto(it.kind, it.mine, it.venue, it.detail.replaceFirstChar(Char::uppercase), if (it.head) "HEAD_CLASH" else "KEY_CLASH") }
        val afterSpans = VenueRigChecks.spansOf(after)
        val introduced = VenueRigChecks.overlapPairs(afterSpans)
            .filter { (key, _) -> setOf(key.first, key.second) !in beforeOverlaps }.values
        val suggested = HashMap<UUID, VenueRigChecks.Span>()
        val overlaps = introduced.mapNotNull { (a, b) -> VenueRigChecks.decisionOf(show, a, b, afterSpans, suggested) }
        val known = plan.decisionIds + overlaps.map { it.id }
        answers.firstOrNull { it.id !in known }?.let { bad ->
            throw VenueException.badRequest(VenueAttachPairing.CODE_ATTACH_INVALID, "No decision '${bad.id}' in this attach")
        }
        val decided = overlaps.map { d ->
            val answer = answers.firstOrNull { it.id == d.id }
            if (answer != null && answer.answer !in d.answers) {
                throw VenueException.badRequest(
                    VenueAttachPairing.CODE_ATTACH_INVALID,
                    "'${answer.answer}' does not answer '${d.id}': it takes ${d.answers.joinToString(" | ")}",
                )
            }
            d.copy(decided = answer != null, answer = answer?.answer, startChannel = answer?.startChannel)
        }
        val notes = VenueRigChecks.applyDecisions(show, decided, afterSpans, VenueAttachPairing.CODE_ATTACH_INVALID)
        // *Remove mine* on a patch kept by *Keep mine* takes the hide of the venue's patch with it, so the
        // unit is the venue's again rather than gone from the show.
        val counterparts = plan.kept.filter { it.kind == AttachKind.PATCH && it.show != null && it.venue != null }.mapNotNull { k ->
            w.uuidRewrites[UUID.fromString(k.show!!.uuid)]?.let { fresh -> fresh.toString() to k.venue!! }
        }.toMap()
        for (d in decided.filter { it.decided && it.answer == UpdateAnswer.REMOVE_MINE }) {
            val venueRecord = counterparts[d.mine.uuid] ?: continue
            DaoShowHide.find {
                (DaoShowHides.project eq show.id) and (DaoShowHides.recordKind eq HideKind.PATCH) and
                    (DaoShowHides.recordUuid eq UUID.fromString(venueRecord.uuid))
            }.forEach { it.delete() }
        }

        val dead = VenueRigChecks.deadReferences(
            show, beforeKeys, VenueRigChecks.keysOf(RigScope.of(show)), "leaves the rig on attaching to ${venue.name} v${plan.to}",
        )

        val behind = ShowBehind.judge(state, projectId)
        val live = runCatching { state.projectManager.currentProject.id.value }.getOrNull() == projectId
        val review = AttachReviewDto(
            projectId = projectId,
            venueId = venueId,
            venueName = venue.name,
            to = plan.to,
            installed = installed,
            behind = behind.behind,
            runAgainst = live && behind.behind == VenueBehind.OUTPUT,
            origin = plan.origin,
            output = plan.output,
            drawing = plan.drawing,
            inherited = plan.inherited,
            overrides = plan.overrides,
            kept = plan.kept,
            hides = plan.hides,
            additions = plan.additions,
            incoming = plan.incoming,
            renames = plan.renames.map { it.withReach() },
            decisions = plan.decisions.map { d -> d.rename?.let { d.copy(rename = it.withReach()) } ?: d },
            overlaps = decided.map { d ->
                val back = counterparts[d.mine.uuid]?.let { " Remove mine brings back the venue's ${it.name}." }.orEmpty()
                val note = notes[d.id]?.let { " $it" }.orEmpty()
                if (back.isEmpty() && note.isEmpty()) d else d.copy(message = d.message + note + back)
            },
            blockers = plan.blockers + clashes,
            deadReferences = dead,
            notes = if (live && w.fixtureRenames.isNotEmpty()) listOf(
                "The live show reloads, and the programmer's values, Locate and hand-run FX on a renamed fixture " +
                    "(${w.fixtureRenames.entries.joinToString { (from, to) -> "$from → $to" }}) do not come back: they name its old key.",
            ) else emptyList(),
        )
        return Outcome(review, touched)
    }

    /** Every pair of units in [spans] that overlap, each named by its uuid after the attach ([after]). */
    private fun overlappingPairs(spans: List<VenueRigChecks.Span>, after: (UUID) -> UUID): Set<Set<UUID>> = buildSet {
        for (a in spans) for (b in spans) {
            if (a.uuid < b.uuid && a.overlaps(b)) add(setOf(after(a.uuid), after(b.uuid)))
        }
    }

    /** Seat views' seating and platforms' regions that name a record whose uuid changes. */
    private fun rewriteReferences(show: EntityID<Int>, rewrites: Map<UUID, UUID>) {
        if (rewrites.isEmpty()) return
        DaoStageViewpoints.selectAll().where { DaoStageViewpoints.project eq show }.forEach { row ->
            val next = row[DaoStageViewpoints.seatElementUuid]?.let { rewrites[it] } ?: return@forEach
            DaoStageViewpoints.update({ DaoStageViewpoints.id eq row[DaoStageViewpoints.id] }) { it[seatElementUuid] = next }
        }
        DaoStageElements.selectAll()
            .where { (DaoStageElements.project eq show) and (DaoStageElements.kind eq StageElementKind.PLATFORM.name) }
            .forEach { row ->
                val params = storedParamsObject(row[DaoStageElements.params])
                val region = (params[VenueAttachPairing.PLATFORM_REGION] as? JsonPrimitive)?.content
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return@forEach
                val next = rewrites[region] ?: return@forEach
                val written = JsonObject(params + (VenueAttachPairing.PLATFORM_REGION to JsonPrimitive(next.toString()))).toString()
                DaoStageElements.update({ DaoStageElements.id eq row[DaoStageElements.id] }) { it[DaoStageElements.params] = written }
            }
    }

    /**
     * The show's parks on a renumbered universe, to the venue's number — through a number no universe
     * has first, so two universes that swapped numbers never meet on the unique index.
     */
    private fun moveParks(show: EntityID<Int>, moves: Map<ParkAddress, ParkAddress>) {
        fun move(from: ParkAddress, universe: Int) = DaoParkedChannels.update({
            (DaoParkedChannels.project eq show) and (DaoParkedChannels.universe eq from.universe) and (DaoParkedChannels.channel eq from.channel)
        }) { it[DaoParkedChannels.universe] = universe }
        for ((from, to) in moves) move(from, -1 - to.universe)
        for ((_, to) in moves) move(to.copy(universe = -1 - to.universe), to.universe)
    }

    /**
     * A spent tube is machine-local, keyed by its patch's uuid (`effect_tube_state`): a patch whose uuid
     * the attach changes keeps its spent tubes, unless the new uuid's are already spent.
     */
    private fun moveTubeStates(patches: Map<UUID, UUID>) {
        for ((old, new) in patches) {
            DaoEffectTubeStates.selectAll().where { DaoEffectTubeStates.patchUuid eq old }.forEach { row ->
                val trigger = row[DaoEffectTubeStates.trigger]
                val taken = DaoEffectTubeStates.selectAll()
                    .where { (DaoEffectTubeStates.patchUuid eq new) and (DaoEffectTubeStates.trigger eq trigger) }.any()
                if (taken) DaoEffectTubeStates.deleteWhere { DaoEffectTubeStates.id eq row[DaoEffectTubeStates.id] }
                else DaoEffectTubeStates.update({ DaoEffectTubeStates.id eq row[DaoEffectTubeStates.id] }) { it[patchUuid] = new }
            }
        }
    }

    /** Every kept row's fresh uuid (P5), and its placements' when it is a patch. */
    private fun writeFreshUuids(w: AttachWrites) {
        for ((kind, rows) in w.freshUuids) {
            val (table, uuid) = uuidColumnOf(kind)
            for ((id, fresh) in rows) table.update({ table.id eq id }) { it[uuid] = fresh }
        }
        val keptPatches = w.freshUuids[AttachKind.PATCH]?.keys.orEmpty()
        if (keptPatches.isEmpty()) return
        DaoFixturePatchPlacements.selectAll()
            .where { DaoFixturePatchPlacements.fixturePatch inList keptPatches.map { EntityID(it, DaoFixturePatches) } }
            .forEach { row ->
                val next = w.uuidRewrites[row[DaoFixturePatchPlacements.uuid]] ?: return@forEach
                DaoFixturePatchPlacements.update({ DaoFixturePatchPlacements.id eq row[DaoFixturePatchPlacements.id] }) { it[uuid] = next }
            }
    }

    private fun ids(w: AttachWrites, kind: AttachKind, table: IntIdTable): List<EntityID<Int>> =
        w.replaced[kind]?.keys.orEmpty().map { EntityID(it, table) }

    private fun deleteReplaced(w: AttachWrites, kind: AttachKind, table: IntIdTable) {
        val ids = ids(w, kind, table)
        if (ids.isNotEmpty()) table.deleteWhere { table.id inList ids }
    }

    private fun kindOf(table: IntIdTable): AttachKind = when (table) {
        DaoUniverseConfigs -> AttachKind.UNIVERSE
        DaoFixturePatches -> AttachKind.PATCH
        DaoRiggings -> AttachKind.RIGGING
        DaoFixtureGroups -> AttachKind.GROUP
        DaoStageElements -> AttachKind.ELEMENT
        else -> error("No attach kind for ${table.tableName}")
    }

    private fun uuidColumnOf(kind: AttachKind): Pair<IntIdTable, Column<UUID>> = when (kind) {
        AttachKind.UNIVERSE -> DaoUniverseConfigs to DaoUniverseConfigs.uuid
        AttachKind.PATCH -> DaoFixturePatches to DaoFixturePatches.uuid
        AttachKind.RIGGING -> DaoRiggings to DaoRiggings.uuid
        AttachKind.REGION -> DaoStageRegions to DaoStageRegions.uuid
        AttachKind.ELEMENT -> DaoStageElements to DaoStageElements.uuid
        AttachKind.VIEWPOINT -> DaoStageViewpoints to DaoStageViewpoints.uuid
        AttachKind.GROUP -> DaoFixtureGroups to DaoFixtureGroups.uuid
        AttachKind.PARK, AttachKind.STAGE -> error("$kind has no rows")
    }

    companion object {
        const val CODE_NOT_STANDALONE = "NOT_A_STANDALONE_SHOW"
        const val CODE_ATTACH_UNDECIDED = "ATTACH_UNDECIDED"
        const val CODE_ATTACH_BLOCKED = "ATTACH_BLOCKED"
    }
}

/** `POST /projects/{id}/venue/attach`. */
@Serializable
data class VenueAttachRequest(
    val venueId: Int,
    /** The version to attach to; null is the installed one. */
    val to: Int? = null,
    val decisions: List<UpdateDecisionAnswer> = emptyList(),
    val dryRun: Boolean = false,
)

/**
 * The attach's review, or what it did (venue-attach plan §4.1): every list [AttachPlan] carries, with
 * P8's address [overlaps] and the [deadReferences] judged on the written rig, and each rename's reach.
 */
@Serializable
data class AttachReviewDto(
    val projectId: Int,
    val venueId: Int,
    val venueName: String,
    /** Always null: a standalone show has no version to come from. The field Update's review carries. */
    val from: Int? = null,
    val to: Int,
    /** The venue's installed version; above [to] when the show attaches behind. */
    val installed: Int,
    /** How far behind its venue the show is once attached. */
    val behind: VenueBehind = VenueBehind.NONE,
    /**
     * The show is live and attaches behind on output: it carries on, run against v[to], rather than
     * going dark (§4.4), as a running show does when a publish puts it behind.
     */
    val runAgainst: Boolean = false,
    val origin: AttachOriginDto? = null,
    val output: List<AttachItemDto> = emptyList(),
    val drawing: List<AttachItemDto> = emptyList(),
    /** How many of the show's records the venue's replace, by kind. */
    val inherited: Map<AttachKind, Int> = emptyMap(),
    val overrides: List<AttachOverrideDto> = emptyList(),
    val kept: List<AttachItemDto> = emptyList(),
    val hides: List<AttachItemDto> = emptyList(),
    val additions: List<AttachItemDto> = emptyList(),
    val incoming: List<AttachItemDto> = emptyList(),
    val renames: List<AttachRenameDto> = emptyList(),
    /** The pairing's choices: identity, hall structure, group, drift and (without an origin) hide. */
    val decisions: List<AttachDecisionDto> = emptyList(),
    /** Address overlaps the attach introduces (P8), answered by id with the decisions; one unanswered refuses the attach. */
    val overlaps: List<UpdateDecisionDto> = emptyList(),
    val blockers: List<AttachItemDto> = emptyList(),
    val deadReferences: List<DeadReferenceDto> = emptyList(),
    /** What else to know before attaching: for a live show, the renamed fixtures whose live values the reload drops. */
    val notes: List<String> = emptyList(),
    val dryRun: Boolean = true,
    val applied: Boolean = false,
)
