package uk.me.cormack.lighting7.venue

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.ParkManager
import uk.me.cormack.lighting7.dmx.ParkedChannel
import uk.me.cormack.lighting7.dmx.ReleasedPark
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.dmx.UnparkOutcome
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoParkedChannel
import uk.me.cormack.lighting7.models.DaoParkedChannels
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoShowParkRelease
import uk.me.cormack.lighting7.models.DaoShowParkReleases
import uk.me.cormack.lighting7.models.ParkAddress
import uk.me.cormack.lighting7.models.RigOwner
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.VenueParks
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
import uk.me.cormack.lighting7.testsupport.VenueShowFixture
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Venue parks with per-show overrides: a venue's parks are part of its rig and versioned with it; a
 * show on the venue inherits its pinned version's, overlaid by its own re-parks and less its releases
 * (`models/VenueParks.kt`, `dmx/ParkManager.kt`); a venue park change is output for the hold and the
 * Update review unless the show parks or releases the channel itself; and every transition — a switch
 * between two shows on the venue, a reload that moves the pin, the hold — takes a parked channel from
 * its old value to its new one without a frame at zero.
 *
 * `seedShowOnVenue` extracts the rich project (whose parks are 0/5 at 128, 0/12 at 0 and 1/7 at 255)
 * into the venue, then the show releases 0/12 and re-parks 1/7 at 200.
 */
class VenueParksTest : RouteIntegrationTest() {

    private val a5 = ParkAddress(0, 5)
    private val a12 = ParkAddress(0, 12)
    private val b7 = ParkAddress(1, 7)

    @Test
    fun `a show inherits its pinned version's parks, overlaid by its own re-park and less its release`() {
        val f = seedShowOnVenue(state)
        transaction(state.database) {
            val layers = VenueParks.of(f.showId)
            assertTrue(layers.onVenue)
            assertEquals(mapOf(a5 to 128u.toUByte(), a12 to 0u.toUByte(), b7 to 255u.toUByte()), layers.venue)
            assertEquals(mapOf(b7 to 200u.toUByte()), layers.own)
            assertEquals(setOf(a12), layers.releases)
            assertEquals(mapOf(a5 to 128u.toUByte(), b7 to 200u.toUByte()), layers.effective)
            assertEquals(RigOwner.VENUE, layers.ownerOf(a5))
            assertEquals(RigOwner.SHOW, layers.ownerOf(b7))
            assertNull(layers.ownerOf(a12))
            // Extract moved the show's parks into the venue: its draft holds all three.
            assertEquals(3, DaoParkedChannel.find { DaoParkedChannels.project eq f.venueId }.count())
        }
        assertEquals(
            setOf(ParkedChannel(0, 5, 128u, RigOwner.VENUE), ParkedChannel(1, 7, 200u, RigOwner.SHOW)),
            ParkManager.storedParks(state.database, f.showId).toSet(),
            "a switch keeps the effective parks off zero",
        )

        open(f.showId)
        val pm = state.show.parkManager
        assertTrue(pm.isOnVenue)
        assertEquals(
            listOf(ParkedChannel(0, 5, 128u, RigOwner.VENUE), ParkedChannel(1, 7, 200u, RigOwner.SHOW, 255u)),
            pm.getAllParked(),
        )
        assertEquals(listOf(ReleasedPark(0, 12, 0u)), pm.getReleased())
        assertEquals(128u.toUByte(), controller(0).transmittedValue(5))
        assertEquals(200u.toUByte(), controller(1).transmittedValue(7))
    }

    @Test
    fun `unpark releases a venue park, a re-park's unpark brings the venue's back, and the version is never written`() = runBlocking {
        val f = seedShowOnVenue(state)
        open(f.showId)
        val pm = state.show.parkManager
        val snapshotBefore = snapshotParks(f)
        val u0 = controller(0)

        // Releasing the venue's 0/5 hands its value down: the output does not move.
        assertEquals(UnparkOutcome.RELEASED, pm.unpark(0, 5))
        assertFalse(pm.isParked(0, 5))
        assertTrue(pm.isReleased(0, 5))
        assertEquals(128u.toUByte(), u0.transmittedValue(5), "a release hands the venue's value down")
        assertTrue(transaction(state.database) { releaseRows(f.showId).contains(a5) })

        // The show's 1/7 at 200 over the venue's 255: unparking it brings the venue's back.
        assertEquals(UnparkOutcome.VENUE_RESTORED, pm.unpark(1, 7))
        assertEquals(255u.toUByte(), pm.getParkedValue(1, 7))
        assertEquals(RigOwner.VENUE, pm.ownerOf(1, 7))
        assertEquals(255u.toUByte(), controller(1).transmittedValue(7))

        // Parking a released channel replaces the release (one word per channel); its unpark brings
        // the venue's park back rather than the release.
        pm.park(0, 12, 77u)
        assertFalse(pm.isReleased(0, 12))
        assertFalse(transaction(state.database) { releaseRows(f.showId).contains(a12) })
        assertEquals(UnparkOutcome.VENUE_RESTORED, pm.unpark(0, 12))
        assertEquals(0u.toUByte(), pm.getParkedValue(0, 12), "the venue's park at 0 stands again")

        // Restoring takes the venue's 0/5 back.
        assertTrue(pm.restoreVenuePark(0, 5))
        assertEquals(128u.toUByte(), pm.getParkedValue(0, 5))
        assertFalse(pm.restoreVenuePark(0, 5), "nothing left to restore")
        // A restore over the show's own re-park drops it.
        pm.park(0, 5, 10u)
        assertTrue(pm.restoreVenuePark(0, 5))
        assertEquals(128u.toUByte(), pm.getParkedValue(0, 5))

        assertEquals(snapshotBefore, snapshotParks(f), "a show never writes its version's parks")
        transaction(state.database) {
            assertEquals(emptySet(), releaseRows(f.showId))
            assertEquals(emptyMap(), VenueParks.rowsOf(DaoProject[f.showId].id))
        }
    }

    @Test
    fun `a venue park the show had to drop stays out, whatever the show parks or restores there`() = runBlocking {
        val f = seedShowOnVenue(state)
        open(f.showId)
        val pm = state.show.parkManager
        val snapshotBefore = snapshotParks(f)
        // What `Show.dropRefusedParks` does with a venue park at a level the desk refuses.
        pm.forget(0, 5)
        assertFalse(pm.isParked(0, 5))
        assertEquals(snapshotBefore, snapshotParks(f), "forgetting a venue park never writes the version")

        pm.park(0, 5, 60u)
        assertFalse(pm.restoreVenuePark(0, 5), "no venue park stands to take back: the show's park stays")
        assertEquals(60u.toUByte(), pm.getParkedValue(0, 5))
        assertEquals(UnparkOutcome.UNPARKED, pm.unpark(0, 5), "with the venue's dropped, the show's park unparks")
        assertFalse(pm.isParked(0, 5))
        assertEquals(60u.toUByte(), controller(0).transmittedValue(5), "and hands its value down")
    }

    @Test
    fun `dropping a show's refused re-park leaves the venue's admissible park under it, and judges it too`() {
        val f = seedShowOnVenue(state)
        open(f.showId)
        // Two one-shot trigger channels: a firing level is refused there, a level of 0 is not.
        val triggers = (1..512).filter {
            state.show.triggerOutput.parkRefusal(0, it, 255u) != null && state.show.triggerOutput.parkRefusal(0, it, 0u) == null
        }
        assertTrue(triggers.size >= 2, "the rig has trigger channels: $triggers")
        val (admissible, refused) = triggers.take(2)
        val snapshot = transaction(state.database) { RigScope.of(f.showId).venue!!.snapshotId }
        transaction(state.database) {
            // Rows no write boundary would let through: what an old row or an import can carry.
            for ((channel, venueValue) in listOf(admissible to 0, refused to 255)) {
                DaoParkedChannel.new { project = DaoProject[snapshot]; universe = 0; this.channel = channel; value = venueValue }
                DaoParkedChannel.new { project = DaoProject[f.showId]; universe = 0; this.channel = channel; value = 255 }
            }
        }
        val versionBefore = snapshotParks(f)

        open(f.showId)

        val pm = state.show.parkManager
        assertEquals(0u.toUByte(), pm.getParkedValue(0, admissible), "the venue's park at 0 stands once the show's 255 is dropped")
        assertEquals(RigOwner.VENUE, pm.ownerOf(0, admissible))
        assertFalse(pm.isParked(0, refused), "the venue's own firing level under it is judged and dropped too")
        assertEquals(versionBefore, snapshotParks(f), "the version is never written")
        transaction(state.database) {
            val own = VenueParks.rowsOf(DaoProject[f.showId].id)
            assertFalse(ParkAddress(0, admissible) in own || ParkAddress(0, refused) in own, "the show's refused rows are deleted: $own")
        }
    }

    @Test
    fun `a venue park change is output for the hold, unless the show parks or releases the channel`() {
        val f = seedShowOnVenue(state)
        // v3 adds a park on 0/300: it reaches the show.
        publish(f, "Park the house lights") { venuePark(f, 300, 90) }
        val third = ShowBehind.judge(state, f.showId)
        assertEquals(VenueBehind.OUTPUT, third.behind)
        val change = third.output.single { it.kind == VenueChangeKind.PARK }
        assertEquals(VenueChangeType.ADDED, change.change)
        assertEquals("0-300", change.address)

        // The show releases 0/300 (dormant at its pin): the venue's park never reaches it.
        transaction(state.database) {
            DaoShowParkRelease.new { project = DaoProject[f.showId]; universe = 0; channel = 300 }
        }
        assertEquals(VenueBehind.DRAWING, ShowBehind.judge(state, f.showId).behind)

        // v4 changes 1/7 (which the show re-parks) and 0/12 (which it releases): still drawing.
        publish(f, "Retune two parks") {
            draftPark(f, 1, 7).value = 10
            draftPark(f, 0, 12).value = 5
        }
        val fourth = ShowBehind.judge(state, f.showId)
        assertEquals(VenueBehind.DRAWING, fourth.behind, "${fourth.output}")
        assertEquals(3, fourth.discounted.count { it.kind == VenueChangeKind.PARK }, "${fourth.discounted}")

        // v5 changes 0/5, which the show runs as the venue has it: output.
        publish(f, "Retune the house park") { draftPark(f, 0, 5).value = 140 }
        assertEquals(VenueBehind.OUTPUT, ShowBehind.judge(state, f.showId).behind)
    }

    @Test
    fun `a venue that unparks and parks a channel again at the same value has changed nothing`() {
        val f = seedShowOnVenue(state)
        transaction(state.database) {
            draftPark(f, 0, 5).delete()
            venuePark(f, 5, 128)
        }
        // Parks are matched by channel, not by row uuid: the draft is the installed version still.
        assertEquals(0, state.venueVersions.draftChanges(f.venueId))
        val refused = runCatching { runBlocking { state.venueVersions.publish(f.venueId, "Nothing", "Test") } }.exceptionOrNull()
        assertEquals(VenueVersions.CODE_DRAFT_EMPTY, (refused as VenueException).code)
    }

    @Test
    fun `the Update review carries the show's parks and releases over the venue's changes`() {
        val f = seedShowOnVenue(state)
        publish(f, "Retune") {
            draftPark(f, 1, 7).value = 10
            draftPark(f, 0, 12).delete()
        }
        val review = runBlocking { state.venueUpdate.review(f.showId, null) }
        val park = review.carryOver.single { it.kind == "PARK" }
        assertTrue(park.detail.contains("1-007 at 200 stays") && park.detail.contains("from 255 to 10"), park.detail)
        val release = review.carryOver.single { it.kind == "RELEASE" }
        assertTrue(release.detail.contains("parks nothing there"), release.detail)
        assertTrue(review.output.any { it.kind == VenueChangeKind.PARK }, "the hall's park changes are listed: ${review.output}")
    }

    @Test
    fun `a switch between two shows on one venue keeps a venue-parked channel at its value`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        val created = client.post("/api/rest/projects") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Second show","venueId":${f.venueId}}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val second = TestJson.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int

        open(f.showId)
        val old = controller(0)
        assertEquals(128u.toUByte(), old.transmittedValue(5))
        // The first show releases 0/5, handing 128 down: the channel is not parked here any more,
        // so only the incoming show's effective parks — the venue's 0/5 among them — keep it lit.
        runBlocking { state.show.parkManager.unpark(0, 5) }
        assertEquals(128u.toUByte(), old.transmittedValue(5))

        open(second)
        val frozen = checkNotNull(old.frozen) { "the outgoing controller was frozen" }
        assertEquals(128u.toUByte(), frozen[5], "the second show inherits the venue's 0/5: held across the switch, not blacked out")
        assertEquals(128u.toUByte(), old.transmittedValue(5))
        val fresh = controller(0)
        assertEquals(128u.toUByte(), fresh.transmittedValue(5))
        assertTrue(fresh.writesTo(5).none { it == 0u.toUByte() }, "never through zero: ${fresh.writesTo(5)}")
        assertEquals(255u.toUByte(), controller(1).transmittedValue(7), "the second show runs the venue's 1/7")
    }

    @Test
    fun `a reload that moves the pin adds and removes venue parks without a frame at zero`() = runBlocking {
        val f = seedShowOnVenue(state)
        open(f.showId)
        val old = controller(0)
        publish(f, "Move the house park") {
            draftPark(f, 0, 5).delete()
            venuePark(f, 20, 90)
        }

        state.venueUpdate.update(f.showId, null, emptyList(), dryRun = false)
        state.projectManager.switchProject(f.showId, reload = true)

        val frozen = checkNotNull(old.frozen)
        assertEquals(128u.toUByte(), frozen[5], "the dropped park is carried at its value")
        val fresh = controller(0)
        assertFalse(state.show.parkManager.isParked(0, 5))
        assertEquals(90u.toUByte(), state.show.parkManager.getParkedValue(0, 20))
        assertEquals(90u.toUByte(), fresh.transmittedValue(20), "the version's new park stands")
        val changes = fresh.changesTo(5)
        assertTrue(changes.none { it.newValue == 0u.toUByte() && it.fadeMs == 0L }, "never snapped to zero: $changes")
    }

    @Test
    fun `a held show's venue parks get through the hold`() {
        val f = seedShowOnVenue(state)
        val free = transaction(state.database) {
            DaoFixturePatch.find { DaoFixturePatches.project eq f.venueId }
                .filter { it.fixtureTypeKey == "hex" && it.uuid != f.hiddenUuid && it.uuid != f.overriddenUuid }
                .minBy { it.key }.key
        }
        publish(f, "Re-address $free") {
            DaoFixturePatch.find { (DaoFixturePatches.project eq f.venueId) and (DaoFixturePatches.key eq free) }.single().startChannel = 260
        }
        open(f.showId)
        assertTrue(state.venueHold.status.value.held)
        val u0 = controller(0)
        u0.setValue(6, 200u)
        assertEquals(128u.toUByte(), u0.transmittedValue(5), "the venue's park gets through the hold")
        assertEquals(0u.toUByte(), u0.transmittedValue(6), "the rest is held at zero")
        assertEquals(200u.toUByte(), controller(1).transmittedValue(7), "the show's own re-park too")
    }

    @Test
    fun `detach flattens the parks the show runs into its own rows, uuids kept`() = runBlocking {
        val f = seedShowOnVenue(state)
        val venueUuid5 = transaction(state.database) { draftPark(f, 0, 5).uuid }
        val before = transaction(state.database) { VenueParks.of(f.showId).effective }

        val result = state.venueDetach.detach(f.showId, dryRun = false)
        assertEquals(listOf("0-005"), result.copied.parks)
        assertEquals(1, result.parkReleasesDropped)

        transaction(state.database) {
            val after = VenueParks.of(f.showId)
            assertFalse(after.onVenue)
            assertEquals(before, after.effective, "the detached show parks exactly what it ran")
            assertEquals(after.effective, after.own)
            assertEquals(emptySet(), releaseRows(f.showId))
            val row = DaoParkedChannel.find { (DaoParkedChannels.project eq f.showId) and (DaoParkedChannels.channel eq 5) }.single()
            assertEquals(venueUuid5, row.uuid)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun open(projectId: Int) {
        runBlocking { state.projectManager.switchProject(projectId) }
    }

    private fun controller(universe: Int): MockDmxController =
        state.show.fixtures.controller(Universe(0, universe)) as MockDmxController

    private fun publish(f: VenueShowFixture, note: String, change: () -> Unit): Int {
        transaction(state.database) { change() }
        return runBlocking { state.venueVersions.publish(f.venueId, note, "Test") }.number
    }

    private fun venuePark(f: VenueShowFixture, channel: Int, value: Int) {
        DaoParkedChannel.new { project = DaoProject[f.venueId]; universe = 0; this.channel = channel; this.value = value }
    }

    private fun draftPark(f: VenueShowFixture, universe: Int, channel: Int): DaoParkedChannel =
        DaoParkedChannel.find {
            (DaoParkedChannels.project eq f.venueId) and (DaoParkedChannels.universe eq universe) and (DaoParkedChannels.channel eq channel)
        }.single()

    private fun releaseRows(showId: Int): Set<ParkAddress> =
        DaoShowParkRelease.find { DaoShowParkReleases.project eq showId }.map { ParkAddress(it.universe, it.channel) }.toSet()

    private fun snapshotParks(f: VenueShowFixture): Map<ParkAddress, UByte> = transaction(state.database) {
        VenueParks.rowsOf(RigScope.of(f.showId).venue!!.snapshotId)
    }
}
