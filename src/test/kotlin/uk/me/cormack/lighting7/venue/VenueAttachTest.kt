package uk.me.cormack.lighting7.venue

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.RigKeyReferences
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.midi.BindingTarget
import uk.me.cormack.lighting7.midi.BindingTargetJson
import uk.me.cormack.lighting7.models.ALL_TABLES
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoControlSurfaceBinding
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoEffectTubeStates
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroupMember
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatchPlacement
import uk.me.cormack.lighting7.models.DaoFixturePatchPlacements
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookRow
import uk.me.cormack.lighting7.models.DaoParkedChannel
import uk.me.cormack.lighting7.models.DaoParkedChannels
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.models.DaoShowHide
import uk.me.cormack.lighting7.models.DaoShowHides
import uk.me.cormack.lighting7.models.DaoShowOverride
import uk.me.cormack.lighting7.models.DaoShowOverrides
import uk.me.cormack.lighting7.models.DaoShowParkRelease
import uk.me.cormack.lighting7.models.DaoShowParkReleases
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.DaoTemplateRow
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.EffectTargetRef
import uk.me.cormack.lighting7.models.ParkAddress
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.RigOwner
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.models.VenueParks
import uk.me.cormack.lighting7.models.extraPlacementsOf
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.routes.CODE_VENUE_ADMIN_ONLY
import uk.me.cormack.lighting7.routes.CODE_VENUE_VERSION_READ_ONLY
import uk.me.cormack.lighting7.sync.ProjectExporter
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.seedVenueReadyShow
import java.awt.Color
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * *Attach to venue…* (venue-attach plan session 2): a standalone show put onto a venue version in one
 * transaction — the pairing's plan written, the venue's guards run on the result, the review the same
 * run rolled back — and Detach's round trip (P2) undone exactly.
 */
class VenueAttachTest : RouteIntegrationTest() {

    private class Hall(val showId: Int, val venueId: Int)

    /** The rich fixture extracted into a venue (v1) and detached again: a standalone show whose origin is v1. */
    private fun detachedShow(): Hall {
        val showId = seedVenueReadyShow(state)
        val venueId = runBlocking {
            state.venueExtract.extract(showId, "Commemoration Hall", VenueExtract.ExtractKind.entries.toSet(), dryRun = false, publishedBy = "Chris")
        }.venueId!!
        detach(showId)
        return Hall(showId, venueId)
    }

    private fun detach(showId: Int) = runBlocking { state.venueDetach.detach(showId, dryRun = false) }

    private fun publish(venueId: Int, note: String) = runBlocking { state.venueVersions.publish(venueId, note, "Chris") }

    private fun attach(h: Hall, to: Int? = null, decisions: List<UpdateDecisionAnswer> = emptyList(), dryRun: Boolean = false): AttachReviewDto =
        runBlocking { state.venueAttach.attach(h.showId, h.venueId, to, decisions, dryRun) }.review

    private fun patch(projectId: Int, key: String): DaoFixturePatch =
        DaoFixturePatch.find { (DaoFixturePatches.project eq projectId) and (DaoFixturePatches.key eq key) }.single()

    private fun refusal(block: () -> Unit): VenueException =
        runCatching(block).exceptionOrNull() as? VenueException ?: error("expected a VenueException")

    @Test
    fun `detaching a show and attaching it back at the same version changes nothing`() {
        val f = seedShowOnVenue(state)
        val rigBefore = effectiveRig(f.showId)
        val parksBefore = transaction(state.database) { VenueParks.of(f.showId).effective }
        val layerBefore = layerOf(f.showId)
        val outputBefore = outputOf(f.showId)
        assertTrue(outputBefore.values.sumOf { it.size } > 20, "the programmer reaches the rig: $outputBefore")
        assertTrue(layerBefore.values.all { it.isNotEmpty() }, "a hide, overrides and a release to bring back: $layerBefore")

        detach(f.showId)
        val result = attach(Hall(f.showId, f.venueId), to = 1)

        assertTrue(result.applied)
        assertEquals(emptyList(), result.output, "attaching changes no output")
        assertEquals(emptyList(), result.blockers)
        assertEquals(emptyList(), result.overlaps)
        assertEquals(rigBefore, effectiveRig(f.showId), "the same effective rig, field for field")
        assertEquals(parksBefore, transaction(state.database) { VenueParks.of(f.showId).effective }, "the same parks")
        assertEquals(layerBefore, layerOf(f.showId), "the same hides, overrides and releases")
        assertEquals(outputBefore, outputOf(f.showId), "the same output from the same programmer")
        transaction(state.database) {
            val show = DaoProject[f.showId]
            assertEquals(1, show.venueVersion)
            assertNull(show.detachedFromVenueUuid)
        }
    }

    @Test
    fun `the review is the attach's answer`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val h = detachedShow()
        // A focus edit (an override) and a group grown (kept, the venue's hidden).
        transaction(state.database) {
            patch(h.showId, "hex-3").beamAngleDeg = 40
            val group = DaoFixtureGroup.find { (DaoFixtureGroups.project eq h.showId) and (DaoFixtureGroups.name eq "back-wash") }.single()
            DaoFixtureGroupMember.new { this.group = group; fixturePatch = patch(h.showId, "hex-4"); sortOrder = 1 }
        }

        val review = client.get("/api/rest/projects/${h.showId}/venue/attach?venue=${h.venueId}&to=1")
        assertEquals(HttpStatusCode.OK, review.status, review.bodyAsText())
        val applied = client.post("/api/rest/projects/${h.showId}/venue/attach") { json("""{"venueId":${h.venueId},"to":1}""") }
        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())

        val r = review.obj()
        val a = applied.obj()
        assertEquals("true", r["dryRun"]!!.jsonPrimitive.content)
        assertEquals("true", a["applied"]!!.jsonPrimitive.content)
        assertEquals(r - setOf("dryRun", "applied"), a - setOf("dryRun", "applied"), "the review promised what the attach did")
        assertTrue(r["overrides"].toString().contains("FOCUS"), "$r")
        assertTrue(r["kept"].toString().contains("back-wash"), "$r")
    }

    @Test
    fun `a key clash with a venue record that can't be hidden, and a rename onto a kept key, refuse the attach`() {
        val h = detachedShow()
        transaction(state.database) { patch(h.showId, "hex-3").key = "hex-9" }
        transaction(state.database) { patch(h.venueId, "hex-4").key = "hex-9" }
        publish(h.venueId, "hex-4 is hex-9")
        assertBlocked(h, setOf("KEY_CLASH", "RENAME_TAKEN"))
    }

    @Test
    fun `a head number a show patch shares with a venue patch refuses the attach`() {
        val showId = seedVenueReadyShow(state)
        val venueId = runBlocking {
            state.venueExtract.extract(showId, "Commemoration Hall", VenueExtract.ExtractKind.entries.toSet(), dryRun = false, publishedBy = "Chris")
        }.venueId!!
        transaction(state.database) {
            val show = DaoProject[showId]
            val u0 = DaoUniverseConfig.find { (DaoUniverseConfigs.project eq RigScope.of(show).venue!!.snapshotId) and (DaoUniverseConfigs.universe eq 0) }.single()
            DaoFixturePatch.new {
                project = show; universeConfig = u0; fixtureTypeKey = "hex"; key = "follow-1"; displayName = "Follow 1"; headNumber = 103; startChannel = 480
            }
        }
        detach(showId)
        assertBlocked(Hall(showId, venueId), setOf("HEAD_CLASH"), to = 1)
    }

    @Test
    fun `a show universe whose number a venue universe holds refuses the attach`() {
        val h = detachedShow()
        transaction(state.database) {
            DaoUniverseConfig.new { project = DaoProject[h.showId]; subnet = 0; universe = 2; controllerType = "MOCK" }
            DaoUniverseConfig.find { (DaoUniverseConfigs.project eq h.venueId) and (DaoUniverseConfigs.universe eq 1) }.single().universe = 2
        }
        publish(h.venueId, "universe 1 is 2")
        assertBlocked(h, setOf("UNIVERSE_CLASH"))
    }

    private fun assertBlocked(h: Hall, reasons: Set<String>, to: Int? = null) {
        val review = attach(h, to, dryRun = true)
        assertEquals(reasons, review.blockers.mapNotNull { it.reason }.toSet(), "${review.blockers}")
        val refused = refusal { attach(h, to) }
        assertEquals(VenueAttach.CODE_ATTACH_BLOCKED, refused.code)
        assertEquals(HttpStatusCode.Conflict, refused.status)
        transaction(state.database) {
            val show = DaoProject[h.showId]
            assertNull(show.venueUuid, "still standalone")
            assertNotNull(show.detachedFromVenueUuid, "its origin kept")
        }
    }

    @Test
    fun `an overlap the attach introduces must be answered, and is applied when it is`() {
        val h = detachedShow()
        // The show patches a spot on its own 0:9; v2 adds 0:9 with a house spot on the same channel.
        val (spot, house) = transaction(state.database) {
            val u = DaoUniverseConfig.new { project = DaoProject[h.showId]; subnet = 0; universe = 9; controllerType = "MOCK" }
            val spot = DaoFixturePatch.new {
                project = DaoProject[h.showId]; universeConfig = u; fixtureTypeKey = "generic-dimmer"; key = "panto-spot"; displayName = "Panto spot"; startChannel = 1
            }
            val vu = DaoUniverseConfig.new { project = DaoProject[h.venueId]; subnet = 0; universe = 9; controllerType = "MOCK" }
            val house = DaoFixturePatch.new {
                project = DaoProject[h.venueId]; universeConfig = vu; fixtureTypeKey = "generic-dimmer"; key = "house-spot"; displayName = "House spot"; startChannel = 1
            }
            spot.uuid to house.uuid
        }
        publish(h.venueId, "a house spot on 0:9")

        val review = attach(h, dryRun = true)
        val overlap = review.overlaps.single()
        assertEquals("overlap:$spot:$house", overlap.id)
        assertFalse(overlap.decided)
        assertTrue(UpdateAnswer.READDRESS_MINE in overlap.answers)
        assertEquals(VenueAttach.CODE_ATTACH_UNDECIDED, refusal { attach(h) }.code)
        transaction(state.database) { assertNull(DaoProject[h.showId].venueUuid, "refused: still standalone") }
        // An answer to no decision is refused.
        val bad = refusal { attach(h, decisions = listOf(UpdateDecisionAnswer("overlap:nope", UpdateAnswer.KEEP_BOTH))) }
        assertEquals(VenueAttachPairing.CODE_ATTACH_INVALID, bad.code)
        assertEquals(HttpStatusCode.BadRequest, bad.status)

        val done = attach(h, decisions = listOf(UpdateDecisionAnswer(overlap.id, UpdateAnswer.READDRESS_MINE, startChannel = 20)))
        assertTrue(done.overlaps.single().decided)
        transaction(state.database) {
            val mine = patch(h.showId, "panto-spot")
            assertEquals(20, mine.startChannel)
            assertEquals(9, mine.universeConfig.universe)
            assertEquals(RigScope.of(h.showId).venue!!.snapshotId, mine.universeConfig.project.id, "on the venue's 0:9")
        }
    }

    @Test
    fun `removing a spot the attach overlaps names everything still pointing at it, a binding and a cue layer included`() {
        val h = detachedShow()
        transaction(state.database) {
            val show = DaoProject[h.showId]
            val u = DaoUniverseConfig.new { project = show; subnet = 0; universe = 9; controllerType = "MOCK" }
            DaoFixturePatch.new { project = show; universeConfig = u; fixtureTypeKey = "generic-dimmer"; key = "panto-spot"; displayName = "Panto spot"; startChannel = 1 }
            val vu = DaoUniverseConfig.new { project = DaoProject[h.venueId]; subnet = 0; universe = 9; controllerType = "MOCK" }
            DaoFixturePatch.new { project = DaoProject[h.venueId]; universeConfig = vu; fixtureTypeKey = "generic-dimmer"; key = "house-spot"; displayName = "House spot"; startChannel = 1 }
            val look = DaoLook.new { project = show; name = "Spot look" }
            DaoCueLayer.new { cue = DaoCue.find { DaoCues.project eq h.showId }.first(); this.look = look; targets = listOf(CueTargetDto("fixture", "panto-spot")) }
            DaoControlSurfaceBinding.new {
                project = show; deviceTypeKey = "xtouch-mini"; controlId = "fader-8"; targetType = "fixtureProperty"
                targetPayload = BindingTargetJson.encodeToString<BindingTarget>(BindingTarget.FixtureProperty("panto-spot", "dimmer"))
            }
        }
        publish(h.venueId, "a house spot on 0:9")
        val overlap = attach(h, dryRun = true).overlaps.single()

        val done = attach(h, decisions = listOf(UpdateDecisionAnswer(overlap.id, UpdateAnswer.REMOVE_MINE)))
        val dead = done.deadReferences.single { it.key == "panto-spot" }
        assertEquals(setOf("CUE", "BINDING"), dead.usedBy.map { it.kind }.toSet(), "${dead.usedBy}")
    }

    @Test
    fun `removing a kept patch that an incoming venue patch overlaps brings back the venue's`() {
        val h = detachedShow()
        // The show re-types hex-4 (kept, the venue's hidden); v2 puts a house spot on hex-4's channel.
        val venueHex = transaction(state.database) {
            val hex = patch(h.showId, "hex-4").apply { fixtureTypeKey = "generic-dimmer" }
            DaoFixturePatch.new {
                project = DaoProject[h.venueId]; universeConfig = patch(h.venueId, "hex-4").universeConfig
                fixtureTypeKey = "generic-dimmer"; key = "house-spot"; displayName = "House spot"; startChannel = hex.startChannel
            }
            patch(h.venueId, "hex-4").uuid
        }
        publish(h.venueId, "a house spot")
        val review = attach(h, dryRun = true)
        val overlap = review.overlaps.single { it.mine.key == "hex-4" }
        assertTrue("brings back the venue's" in overlap.message, overlap.message)

        attach(h, decisions = listOf(UpdateDecisionAnswer(overlap.id, UpdateAnswer.REMOVE_MINE)))
        transaction(state.database) {
            assertEquals(0, DaoShowHide.find { (DaoShowHides.project eq h.showId) and (DaoShowHides.recordUuid eq venueHex) }.count(), "the venue's hex-4 is not hidden")
            val rig = RigScope.of(h.showId)
            val hex = rig.patches(DaoFixturePatches.key eq "hex-4").single()
            assertEquals(venueHex, hex.uuid, "the show's hex-4 is the venue's")
            assertEquals(RigOwner.VENUE, rig.ownerOf(hex.project.id))
        }
    }

    @Test
    fun `a park on a universe the venue renumbered moves with it`() {
        val h = detachedShow()
        transaction(state.database) {
            // The show re-values its copy of the venue's 1-007; v2 renumbers universe 1 to 3.
            DaoParkedChannel.find { (DaoParkedChannels.project eq h.showId) and (DaoParkedChannels.universe eq 1) and (DaoParkedChannels.channel eq 7) }
                .single().value = 200
            DaoUniverseConfig.find { (DaoUniverseConfigs.project eq h.venueId) and (DaoUniverseConfigs.universe eq 1) }.single().universe = 3
            DaoParkedChannel.find { (DaoParkedChannels.project eq h.venueId) and (DaoParkedChannels.universe eq 1) }.forEach { it.universe = 3 }
        }
        publish(h.venueId, "universe 1 is 3")
        val result = attach(h)

        assertEquals(emptyList(), result.blockers)
        transaction(state.database) {
            val parks = VenueParks.of(h.showId)
            assertEquals(200.toUByte(), parks.effective[ParkAddress(3, 7)], "the show's re-park moved with its universe: ${parks.effective}")
            assertNull(parks.effective[ParkAddress(1, 7)])
            assertEquals(setOf(ParkAddress(3, 7)), parks.own.keys)
            assertEquals(emptySet(), parks.releases)
        }
    }

    @Test
    fun `taking the venue's identity renames cue, Look, palette and binding rows, and a selection effect's item`() {
        val h = detachedShow()
        // The show re-keyed hex-3 to hex-3b after detaching, everything that named it with it.
        val seeded = transaction(state.database) {
            val show = DaoProject[h.showId]
            val keys = RigScope.of(show).patches().map { it.key }.toSet()
            patch(h.showId, "hex-3").key = "hex-3b"
            RigKeyReferences.rewrite(show, mapOf("hex-3" to "hex-3b"), emptyMap(), emptyList(), keys)
            val cue = DaoCue.find { DaoCues.project eq h.showId }.first()
            val assignment = DaoCuePropertyAssignment.new { this.cue = cue; targetType = "fixture"; targetKey = "hex-3b"; propertyName = "dimmer"; value = "200" }
            val effect = DaoCueAdHocEffect.new {
                this.cue = cue; targetType = EffectTargetRef.Selection.TYPE
                targetKey = EffectTargetRef.Selection(listOf(TargetRef.Fixture("hex-3b"), TargetRef.Fixture("hex-1"))).key
                effectType = "Pulse"; category = "dimmer"; beatDivision = 1.0; blendMode = "OVERRIDE"; distribution = "LINEAR"
                parameters = emptyMap()
            }
            val look = DaoLook.new { project = show; name = "Hex 3 special" }
            val lookRow = DaoLookRow.new { this.look = look; targetType = "fixture"; targetKey = "hex-3b"; propertyName = "dimmer"; value = "128"; sortOrder = 0 }
            val palette = DaoTemplate.new { project = show; name = "hex-3 focus" }
            val paletteRow = DaoTemplateRow.new { template = palette; targetType = "fixture"; targetKey = "hex-3b"; propertyName = "dimmer"; value = "90"; sortOrder = 0 }
            val binding = DaoControlSurfaceBinding.new {
                project = show; deviceTypeKey = "xtouch-mini"; controlId = "fader-3"; targetType = "fixtureProperty"
                targetPayload = BindingTargetJson.encodeToString<BindingTarget>(BindingTarget.FixtureProperty("hex-3b", "dimmer"))
            }
            DaoScript.new { project = show; name = "Hex chase"; script = """fixture<Fixture>("hex-3b")""" }
            listOf(assignment.id.value, effect.id.value, lookRow.id.value, paletteRow.id.value, binding.id.value)
        }

        val review = attach(h, to = 1, dryRun = true)
        val identity = review.decisions.single { it.kind == "IDENTITY" }
        runBlocking { state.projectManager.switchProject(h.showId) }
        val live = attach(h, to = 1, decisions = listOf(UpdateDecisionAnswer(identity.id, UpdateAnswer.TAKE_VENUES)), dryRun = true)
        assertTrue(live.notes.single().contains("hex-3b → hex-3"), "the live show is told what the reload drops: ${live.notes}")
        assertEquals(emptyList(), review.notes, "keeping its own key renames nothing")
        assertEquals(UpdateAnswer.KEEP_MINE, identity.answer)
        val reach = identity.rename!!.usedBy.map { it.kind }.toSet()
        assertTrue(setOf("CUE", "LOOK", "TEMPLATE", "BINDING", "SCRIPT").all { it in reach }, "the rename's reach: $reach")

        val done = attach(h, to = 1, decisions = listOf(UpdateDecisionAnswer(identity.id, UpdateAnswer.TAKE_VENUES)))
        assertEquals("hex-3b", done.renames.single().from)
        assertEquals("hex-3", done.renames.single().to)
        assertTrue(done.deadReferences.any { it.key == "hex-3b" && it.usedBy.any { u -> u.kind == "SCRIPT" } }, "a script is not rewritten: ${done.deadReferences}")
        transaction(state.database) {
            val (assignment, effect, lookRow, paletteRow, binding) = seeded
            assertEquals("hex-3", DaoCuePropertyAssignment[assignment].targetKey)
            assertEquals(
                EffectTargetRef.Selection(listOf(TargetRef.Fixture("hex-3"), TargetRef.Fixture("hex-1"))).key,
                DaoCueAdHocEffect[effect].targetKey,
                "a selection's item",
            )
            assertEquals("hex-3", DaoLookRow[lookRow].targetKey)
            assertEquals("hex-3", DaoTemplateRow[paletteRow].targetKey)
            assertEquals(BindingTarget.FixtureProperty("hex-3", "dimmer"), BindingTargetJson.decodeFromString<BindingTarget>(DaoControlSurfaceBinding[binding].targetPayload))
            assertEquals(0, DaoFixturePatch.find { (DaoFixturePatches.project eq h.showId) and (DaoFixturePatches.key eq "hex-3b") }.count(), "the show's row is the venue's now")
        }
    }

    @Test
    fun `no uuid is held by both a show row and a visible venue row afterwards`() {
        val h = detachedShow()
        // Kept rows of three kinds: a re-typed patch (with its placements), a grown group, a moved rigging.
        transaction(state.database) {
            val hex = patch(h.showId, "hex-4").apply { fixtureTypeKey = "generic-dimmer" }
            DaoFixturePatchPlacement.new { fixturePatch = hex; label = "Spare"; stageX = 1.0 }
            val group = DaoFixtureGroup.find { (DaoFixtureGroups.project eq h.showId) and (DaoFixtureGroups.name eq "back-wash") }.single()
            DaoFixtureGroupMember.new { this.group = group; fixturePatch = patch(h.showId, "hex-1"); sortOrder = 5 }
        }
        val boom = transaction(state.database) {
            uk.me.cormack.lighting7.models.DaoRigging.find {
                (uk.me.cormack.lighting7.models.DaoRiggings.project eq h.showId) and (uk.me.cormack.lighting7.models.DaoRiggings.name eq "Boom-SL")
            }.single().apply { positionX = (positionX ?: 0.0) + 1.0 }.uuid
        }
        val review = attach(h, to = 1, dryRun = true)
        val hall = review.decisions.single { it.kind == "HALL" }
        val done = attach(h, to = 1, decisions = listOf(UpdateDecisionAnswer(hall.id, UpdateAnswer.KEEP_MINE)))
        assertEquals(setOf(AttachKind.PATCH, AttachKind.GROUP, AttachKind.RIGGING), done.kept.map { it.kind }.toSet())

        transaction(state.database) {
            val rig = RigScope.of(h.showId)
            fun shared(own: List<UUID>, visible: List<UUID>) = own.toSet() intersect visible.toSet()
            fun <R> split(rows: List<R>, project: (R) -> org.jetbrains.exposed.v1.core.dao.id.EntityID<Int>, uuid: (R) -> UUID) =
                rows.partition { rig.ownerOf(project(it)) == RigOwner.SHOW }.let { (own, venue) -> own.map(uuid) to venue.map(uuid) }
            val patches = rig.patches().toList()
            val (ownPatches, venuePatches) = split(patches, { it.project.id }, { it.uuid })
            assertEquals(emptySet(), shared(ownPatches, venuePatches), "patches")
            val placements = patches.flatMap { p -> extraPlacementsOf(p).map { p to it } }
            val (ownPlacements, venuePlacements) = split(placements, { it.first.project.id }, { it.second.uuid })
            assertEquals(emptySet(), shared(ownPlacements, venuePlacements), "placements")
            val (ownGroups, venueGroups) = split(rig.groups().toList(), { it.project.id }, { it.uuid })
            assertEquals(emptySet(), shared(ownGroups, venueGroups), "groups")
            val (ownRiggings, venueRiggings) = split(rig.riggings().toList(), { it.project.id }, { it.uuid })
            assertEquals(emptySet(), shared(ownRiggings, venueRiggings), "riggings")
            val (ownElements, venueElements) = split(rig.stageElements().toList(), { it.project.id }, { it.uuid })
            assertEquals(emptySet(), shared(ownElements, venueElements), "elements")
            val (ownViews, venueViews) = split(rig.stageViewpoints().toList(), { it.project.id }, { it.uuid })
            assertEquals(emptySet(), shared(ownViews, venueViews), "viewpoints")
            assertFalse(boom in ownRiggings, "the kept boom took a fresh uuid")
            assertTrue(ownRiggings.isNotEmpty() && ownPatches.isNotEmpty() && ownGroups.isNotEmpty())
        }
    }

    @Test
    fun `a spent tube stays spent when its patch takes a new uuid`() {
        val h = detachedShow()
        val old = transaction(state.database) {
            val hex = patch(h.showId, "hex-4").apply { fixtureTypeKey = "generic-dimmer" }
            DaoEffectTubeStates.insert {
                it[patchUuid] = hex.uuid
                it[trigger] = "output1"
                it[spentAt] = nowUtc()
            }
            hex.uuid
        }
        attach(h, to = 1)
        transaction(state.database) {
            val kept = patch(h.showId, "hex-4").uuid
            assertTrue(kept != old, "kept under a fresh uuid")
            assertEquals(listOf(kept), DaoEffectTubeStates.selectAll().map { it[DaoEffectTubeStates.patchUuid] })
        }
    }

    @Test
    fun `a dry run leaves the database as it was`() {
        val h = detachedShow()
        transaction(state.database) { patch(h.showId, "hex-3").beamAngleDeg = 40 }
        val before = database()
        val review = attach(h, to = 1, dryRun = true)
        assertTrue(review.dryRun)
        assertFalse(review.applied)
        assertTrue(review.overrides.isNotEmpty())
        assertEquals(before, database())
    }

    @Test
    fun `a running show reloads, and one attached behind on output runs against its pin`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        detach(f.showId)
        runBlocking { state.projectManager.switchProject(f.showId) }
        val showBefore = state.show

        val attached = client.post("/api/rest/projects/${f.showId}/venue/attach") { json("""{"venueId":${f.venueId},"to":1}""") }
        assertEquals(HttpStatusCode.OK, attached.status, attached.bodyAsText())
        assertNotSame(showBefore, state.show, "the live show reloaded")
        assertEquals("DRAWING", attached.obj()["behind"]!!.jsonPrimitive.content, "v2 only moved a truss")
        assertFalse(state.venueHold.status.value.held)
        assertFalse(state.venueHold.status.value.runAgainst)

        // Detached again, and v3 re-addresses a unit the show drives: attaching at v1 is behind on output.
        detach(f.showId)
        runBlocking { state.projectManager.switchProject(f.showId) }
        transaction(state.database) {
            DaoFixturePatch.find { DaoFixturePatches.project eq f.venueId }
                .filter { it.fixtureTypeKey == "hex" && it.uuid != f.hiddenUuid && it.uuid != f.overriddenUuid }
                .minBy { it.key }.startChannel = 260
        }
        publish(f.venueId, "a hex re-addressed")
        val behind = client.post("/api/rest/projects/${f.showId}/venue/attach") { json("""{"venueId":${f.venueId},"to":1}""") }
        assertEquals(HttpStatusCode.OK, behind.status, behind.bodyAsText())
        val body = behind.obj()
        assertEquals("OUTPUT", body["behind"]!!.jsonPrimitive.content)
        assertEquals("true", body["runAgainst"]!!.jsonPrimitive.content, "the review says so")
        val hold = state.venueHold.status.value
        assertEquals(f.showId, hold.projectId)
        assertFalse(hold.held, "not blacked out mid-run")
        assertTrue(hold.runAgainst, "run against v1")
        assertEquals(1, hold.pinned)
        assertEquals(3, hold.installed)
    }

    @Test
    fun `the attach clears the origin, writes the pin and its repo hint, and the export carries the pin alone`() {
        val h = detachedShow()
        transaction(state.database) {
            (DaoSyncConfig.find { DaoSyncConfigs.project eq h.venueId }.firstOrNull() ?: DaoSyncConfig.new { project = DaoProject[h.venueId] })
                .repoUrl = "https://github.com/cjcormack/commemoration-hall.git"
        }
        attach(h, to = 1)
        val venueUuid = transaction(state.database) {
            val show = DaoProject[h.showId]
            assertEquals(DaoProject[h.venueId].uuid, show.venueUuid)
            assertEquals(1, show.venueVersion)
            assertEquals("cjcormack/commemoration-hall", show.venueRepo)
            assertNull(show.detachedFromVenueUuid)
            assertNull(show.detachedFromVersion)
            assertEquals(0, DaoShowHide.find { DaoShowHides.project eq h.showId }.count())
            show.venueUuid.toString()
        }
        val dir = Files.createTempDirectory("attach-export-")
        try {
            ProjectExporter(state).export(h.showId, dir)
            val project = TestJson.parseToJsonElement(dir.resolve("project.json").toFile().readText()).jsonObject
            assertEquals(venueUuid, project["venueUuid"]?.jsonPrimitive?.content)
            assertEquals(1, project["venueVersion"]?.jsonPrimitive?.content?.toInt())
            assertEquals("cjcormack/commemoration-hall", project["venueRepo"]?.jsonPrimitive?.content)
            assertNull(project["detachedFromVenueUuid"])
            assertNull(project["detachedFromVersion"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `attach takes a standalone show only, and either role may attach`() = testApplication {
        mountTestApp(state)
        seedUser(state, "admin", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val op = client.loginCookieHeader("op")
        val admin = client.loginCookieHeader("admin")
        val f = seedShowOnVenue(state)
        val snapshot = transaction(state.database) { VenueVersions.versionsOf(f.venueId).first().snapshotProject.id.value }
        val body = """{"venueId":${f.venueId}}"""

        assertCode(HttpStatusCode.Conflict, VenueAttach.CODE_NOT_STANDALONE, client.post("/api/rest/projects/${f.showId}/venue/attach") { cookie(op); json(body) })
        assertCode(HttpStatusCode.Conflict, VenueAttach.CODE_NOT_STANDALONE, client.post("/api/rest/projects/${f.venueId}/venue/attach") { cookie(admin); json(body) })
        assertCode(HttpStatusCode.Forbidden, CODE_VENUE_ADMIN_ONLY, client.post("/api/rest/projects/${f.venueId}/venue/attach") { cookie(op); json(body) })
        assertCode(HttpStatusCode.Conflict, CODE_VENUE_VERSION_READ_ONLY, client.post("/api/rest/projects/$snapshot/venue/attach") { cookie(admin); json(body) })
        assertCode(HttpStatusCode.BadRequest, VenueAttachPairing.CODE_ATTACH_INVALID, client.get("/api/rest/projects/${f.showId}/venue/attach") { cookie(op) })

        // A venue with nothing published, and a version it lacks.
        detach(f.showId)
        val bare = transaction(state.database) { DaoProject.new { name = "Empty hall"; kind = ProjectKind.VENUE }.id.value }
        assertCode(
            HttpStatusCode.Conflict, VenueVersions.CODE_NOTHING_PUBLISHED,
            client.post("/api/rest/projects/${f.showId}/venue/attach") { cookie(op); json("""{"venueId":$bare}""") },
        )
        assertCode(
            HttpStatusCode.NotFound, VenueVersions.CODE_VERSION_UNKNOWN,
            client.get("/api/rest/projects/${f.showId}/venue/attach?venue=${f.venueId}&to=9") { cookie(op) },
        )

        // An operator attaches: it changes only the show.
        val attached = client.post("/api/rest/projects/${f.showId}/venue/attach") { cookie(op); json("""{"venueId":${f.venueId},"to":1}""") }
        assertEquals(HttpStatusCode.OK, attached.status, attached.bodyAsText())
        assertCode(HttpStatusCode.Conflict, VenueAttach.CODE_NOT_STANDALONE, client.post("/api/rest/projects/${f.showId}/venue/attach") { cookie(op); json(body) })
    }

    @Test
    fun `attach_to_venue reviews and attaches over MCP, and the live show needs reloadLiveShow`() {
        val tools = SetupTools(state, AiTools(state))
        fun call(arguments: String) = runBlocking { tools.executeTool("attach_to_venue", Json.parseToJsonElement(arguments).jsonObject, caller = "Tester") }
        val h = detachedShow()
        transaction(state.database) { patch(h.showId, "hex-3").beamAngleDeg = 40 }

        val dry = call("""{"projectId":${h.showId},"venueId":${h.venueId},"version":1,"dryRun":true}""")
        assertTrue(dry.success, dry.result)
        val review = Json.parseToJsonElement(dry.result).jsonObject
        assertTrue(review["overrides"].toString().contains("FOCUS"), dry.result)
        transaction(state.database) { assertNull(DaoProject[h.showId].venueUuid, "a dry run keeps nothing") }

        val bad = call("""{"projectId":${h.showId},"venueId":${h.venueId},"decisions":[{"id":"x","answer":"MAYBE"}]}""")
        assertFalse(bad.success)
        assertTrue("decisions[0]" in bad.result, bad.result)

        runBlocking { state.projectManager.switchProject(h.showId) }
        val refused = call("""{"projectId":${h.showId},"venueId":${h.venueId},"version":1}""")
        assertFalse(refused.success)
        assertTrue("reloadLiveShow" in refused.result, refused.result)
        val showBefore = state.show
        val done = call("""{"projectId":${h.showId},"venueId":${h.venueId},"version":1,"reloadLiveShow":true}""")
        assertTrue(done.success, done.result)
        assertNotSame(showBefore, state.show, "the live show reloaded")
        transaction(state.database) { assertEquals(1, DaoProject[h.showId].venueVersion) }

        val again = call("""{"projectId":${h.showId},"venueId":${h.venueId},"dryRun":true}""")
        assertFalse(again.success)
        assertTrue(VenueAttach.CODE_NOT_STANDALONE in again.result, again.result)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** The effective rig, each record by its uuid and every field the rig reads, row ids left out. */
    private fun effectiveRig(projectId: Int): Map<String, Any?> = transaction(state.database) {
        val rig = RigScope.of(projectId)
        mapOf(
            "patches" to rig.patches().map { p ->
                val e = rig.effective(p)
                listOf(
                    e.uuid, e.key, e.displayName, e.fixtureTypeKey, e.headNumber, e.universeConfig.uuid, e.universeConfig.subnet,
                    e.universeConfig.universe, e.startChannel, e.rigging?.uuid, e.stageX, e.stageY, e.stageZ, e.baseYawDeg,
                    e.basePitchDeg, e.baseRollDeg, e.lengthM, e.focus, e.beamAngleDeg, e.lampWatts, e.lampKelvin, e.locate,
                    e.kindOverride, e.gelCode, e.fittedMedia, e.overridden, e.stageHidden, e.infrastructure,
                    rig.effectivePlacements(p, extraPlacementsOf(p)).map { pl ->
                        listOf(pl.uuid, pl.label, pl.rigging?.uuid, pl.stageX, pl.stageY, pl.stageZ, pl.baseYawDeg, pl.basePitchDeg, pl.baseRollDeg, pl.lengthM, pl.focus, pl.fittedMedia)
                    },
                )
            }.sortedBy { it[1] as String },
            "universes" to rig.universeConfigs().map { listOf(it.uuid, it.subnet, it.universe, it.controllerType) }.sortedBy { it[0].toString() },
            "riggings" to rig.riggings().map { listOf(it.uuid, it.name, it.kind, it.positionX, it.positionY, it.positionZ, it.yawDeg, it.pitchDeg, it.rollDeg, it.lengthM) }
                .sortedBy { it[1] as String },
            "regions" to rig.stageRegions().map { listOf(it.uuid, it.name) }.sortedBy { it[1] as String },
            "elements" to rig.stageElements().map { listOf(it.uuid, it.name, it.kind, it.layer, it.params) }.sortedBy { it[1] as String },
            "viewpoints" to rig.stageViewpoints().map { listOf(it.uuid, it.name, it.seatElementUuid) }.sortedBy { it[1] as String },
            "groups" to rig.groups().map { g ->
                listOf(g.uuid, g.name, rig.members(g).map { listOf(it.fixturePatch.uuid, it.panOffset, it.tiltOffset) })
            }.sortedBy { it[1] as String },
            "stage" to rig.stageBounds(),
        )
    }

    /** The show's layer over its venue, by what each row names. */
    private fun layerOf(projectId: Int): Map<String, Set<String>> = transaction(state.database) {
        mapOf(
            "hides" to DaoShowHide.find { DaoShowHides.project eq projectId }.map { "${it.recordKind}:${it.recordUuid}" }.toSet(),
            "overrides" to DaoShowOverride.find { DaoShowOverrides.project eq projectId }.map { "${it.recordUuid}:${it.facet}=${it.value}@${it.base}" }.toSet(),
            "releases" to DaoShowParkRelease.find { DaoShowParkReleases.project eq projectId }.map { "${it.universe}-${it.channel}" }.toSet(),
        )
    }

    /** Every non-zero channel the project sends once the programmer sets each fixture's dimmer, colour and position, on a clean open. */
    private fun outputOf(projectId: Int): Map<Int, Map<Int, UByte>> {
        runBlocking { state.projectManager.switchProject(projectId) }
        state.show.fixtures.fixtures.sortedBy { it.key }.forEachIndexed { i, fixture ->
            val values = mapOf(
                "dimmer" to CueAssignmentResolver.PropertyValue.Slider((40 + i * 9).toUByte()),
                "rgbColour" to CueAssignmentResolver.PropertyValue.Colour(ExtendedColour(Color(200, 100 + i, 50))),
                "position" to CueAssignmentResolver.PropertyValue.Position(pan = (90 + i).toUByte(), tilt = 150u),
            )
            for ((property, value) in values) {
                runCatching {
                    state.show.fxEngine.programmer.writeProperty(ProgrammerOwner.WEB, state.show.fixtures.untypedGroupableFixture(fixture.key), property, value)
                }
            }
        }
        return state.show.fixtures.controllers.associate { c ->
            c.universe.universe to (1..512).associateWith { (c as MockDmxController).getEffectiveValue(it) }.filterValues { it != 0.toUByte() }
        }
    }

    /** Every row of every table, as text. */
    private fun database(): Map<String, List<String>> = transaction(state.database) {
        ALL_TABLES.associate { table ->
            table.tableName to table.selectAll().map { row ->
                // Read back as text: a column's type parameter is only the Kotlin view of its values.
                @Suppress("UNCHECKED_CAST")
                table.columns.joinToString("|") { row[it as Column<Any?>]?.toString() ?: "∅" }
            }.sorted()
        }
    }

    private suspend fun HttpResponse.obj(): JsonObject = TestJson.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun assertCode(status: HttpStatusCode, code: String, response: HttpResponse) {
        val body = response.bodyAsText()
        assertEquals(status, response.status, body)
        assertEquals(code, TestJson.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content, body)
    }

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun HttpRequestBuilder.cookie(value: String) = header(HttpHeaders.Cookie, value)
}
