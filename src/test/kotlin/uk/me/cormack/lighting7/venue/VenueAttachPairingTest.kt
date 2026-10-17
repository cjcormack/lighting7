package uk.me.cormack.lighting7.venue

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.AddressFacet
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroupMember
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoParkedChannel
import uk.me.cormack.lighting7.models.DaoParkedChannels
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.DaoRiggings
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.FacetValue
import uk.me.cormack.lighting7.models.FocusFacet
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.models.ParkAddress
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.models.PlacementPosition
import uk.me.cormack.lighting7.models.PositionFacet
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.decodeFacet
import uk.me.cormack.lighting7.models.extraPlacementsOf
import uk.me.cormack.lighting7.models.venueFacetOf
import uk.me.cormack.lighting7.sync.ProjectCloner
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import uk.me.cormack.lighting7.testsupport.seedVenueReadyShow
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pairing behind *Attach to venue…* (venue-attach plan §3, session 1): a show detached from the
 * Sleeping Beauty-shaped fixture's venue, changed by the show or the venue or both, then paired
 * against a version — what each record becomes, and what the attach would write. Nothing is written:
 * the pairing is a pure function, and these tests read its plan.
 */
class VenueAttachPairingTest : RouteIntegrationTest() {

    private class Hall(val showId: Int, val venueId: Int)

    /** The rich fixture extracted into a venue (v1) and detached again: a standalone show with every record a v1 record. */
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

    private fun plan(h: Hall, to: Int? = null, answers: List<UpdateDecisionAnswer> = emptyList()): AttachPlan = transaction(state.database) {
        val inputs = VenueAttachRows.inputs(DaoProject[h.showId], h.venueId, to)
        VenueAttachPairing.pair(inputs.show, inputs.origin, inputs.target, answers)
    }

    private fun patch(projectId: Int, key: String): DaoFixturePatch =
        DaoFixturePatch.find { (DaoFixturePatches.project eq projectId) and (DaoFixturePatches.key eq key) }.single()

    private fun rowCounts(projectId: Int): Map<AttachKind, Int> = transaction(state.database) {
        val rows = VenueAttachRows.ofProject(DaoProject[projectId].id, DaoProject[projectId].id)
        mapOf(
            AttachKind.UNIVERSE to rows.universes.size,
            AttachKind.PATCH to rows.patches.size,
            AttachKind.RIGGING to rows.riggings.size,
            AttachKind.REGION to rows.regions.size,
            AttachKind.ELEMENT to rows.elements.count { it.pairable },
            AttachKind.VIEWPOINT to rows.viewpoints.size,
            AttachKind.GROUP to rows.groups.size,
            AttachKind.PARK to rows.parks.size,
        )
    }

    private fun AttachPlan.assertNoLayer() {
        assertEquals(emptyList(), overrides, "no overrides")
        assertEquals(emptyList(), writes.hides, "no hides")
        assertEquals(emptyList(), writes.releases, "no releases")
        assertEquals(emptyList(), kept)
        assertEquals(emptyList(), incoming)
        assertEquals(emptyList(), blockers)
    }

    @Test
    fun `a show detached and paired at the same version inherits every record`() {
        val h = detachedShow()
        val p = plan(h, to = 1)

        assertEquals(AttachOriginStatus.USED, p.origin?.status)
        p.assertNoLayer()
        assertEquals(emptyList(), p.decisions, "nothing to decide")
        assertEquals(emptyList(), p.output, "no output changes")
        assertEquals(emptyList(), p.drawing, "no drawing changes")
        assertEquals(emptyList(), p.renames)
        // Every v1 record replaces the show's copy of it, by uuid, so no uuid is rewritten.
        val venueSnapshot = transaction(state.database) { VenueVersions.versionsOf(h.venueId).single().snapshotProject.id.value }
        assertEquals(rowCounts(venueSnapshot).filterValues { it > 0 }, p.inherited)
        assertEquals(emptyMap(), p.writes.uuidRewrites)
        assertEquals(emptyMap(), p.writes.freshUuids)
        // What stays the show's own is its set and its copy of the Preset seating, which never pair.
        assertTrue(p.additions.isNotEmpty() && p.additions.all { it.kind == AttachKind.ELEMENT }, "${p.additions}")
        // The universe address Detach copied to the show goes: the venue's applies again, and it is the same.
        assertEquals(1, p.writes.machineOverridesDropped.size)
    }

    @Test
    fun `a show on a venue detached and paired back keeps its hides, overrides and releases`() {
        val f = seedShowOnVenue(state)
        val before = transaction(state.database) {
            ShowOverrides.drift(DaoProject[f.showId]).associate { it.facet to (it.value to it.base) }
        }
        detach(f.showId)
        val p = plan(Hall(f.showId, f.venueId), to = 1)

        assertEquals(listOf(f.hideKind to f.hiddenUuid), p.writes.hides, "the hide comes back: removed since v1")
        assertEquals(listOf(ParkAddress(0, 12)), p.writes.releases)
        assertTrue(p.additions.any { it.kind == AttachKind.PARK && it.reason == "REPARK" }, "the re-park of 1-007 stays")
        val overrides = p.writes.overrides.associateBy { it.facet }
        assertEquals(before.keys, overrides.keys)
        for ((facet, o) in overrides) {
            assertEquals(f.overriddenUuid, o.recordUuid)
            assertEquals(before.getValue(facet).first, o.value, "$facet's value")
            assertEquals(before.getValue(facet).second, o.base, "$facet's base")
        }
        assertTrue(p.overrides.none { it.dormant || it.drift })
        assertTrue(p.additions.any { it.kind == AttachKind.PATCH && it.show?.uuid == f.additionUuid.toString() })
        assertTrue(p.additions.any { it.kind == AttachKind.RIGGING && it.show?.uuid == f.boomUuid.toString() })
        assertEquals(emptyList(), p.blockers)
        assertEquals(emptyList(), p.decisions)
    }

    @Test
    fun `a focus the show changed after detaching becomes one override, based on the version`() {
        val h = detachedShow()
        transaction(state.database) { patch(h.showId, "hex-3").beamAngleDeg = 40 }
        val p = plan(h, to = 1)

        val o = p.writes.overrides.single()
        assertEquals(PatchFacet.FOCUS, o.facet)
        assertEquals(40, (o.value as FocusFacet).beamAngleDeg)
        val v1 = transaction(state.database) {
            val snapshot = VenueVersions.versionsOf(h.venueId).single().snapshotProject.id.value
            patch(snapshot, "hex-3").let { venueFacetOf(it, extraPlacementsOf(it), PatchFacet.FOCUS) }
        }
        assertEquals(v1, o.base, "base = v1")
        assertFalse(p.overrides.single().drift)
        assertEquals(emptyList(), p.decisions)
        assertEquals(emptyList(), p.writes.hides)
    }

    @Test
    fun `against a later version the venue's change is inherited and a facet both changed is drift`() {
        val h = detachedShow()
        transaction(state.database) { patch(h.showId, "hex-3").beamAngleDeg = 40 }
        // v2: the hall re-addresses hex-3 and re-lamps it.
        transaction(state.database) {
            patch(h.venueId, "hex-3").apply { startChannel = 300; beamAngleDeg = 36 }
        }
        publish(h.venueId, "hex-3 moved and re-lamped")
        val p = plan(h)

        assertEquals(2, p.to)
        assertEquals(1, p.origin?.version)
        assertTrue(p.writes.overrides.none { it.facet == PatchFacet.ADDRESS }, "the venue's address change is inherited")
        assertTrue(p.output.any { it.show?.key == "hex-3" && it.detail.contains("address") }, "${p.output}")
        val focus = p.overrides.single()
        assertEquals(PatchFacet.FOCUS, focus.facet)
        assertTrue(focus.drift, "both changed the focus")
        val drift = p.decisions.single { it.kind == "DRIFT" }
        assertEquals(focus.decisionId, drift.id)
        assertTrue(drift.bothChanged)
        assertEquals(UpdateAnswer.KEEP_MINE, drift.answer)
        // The base is v1's: what the show changed it from.
        val v1 = transaction(state.database) {
            val snapshot = VenueVersions.versionsOf(h.venueId).single { it.number == 1 }.snapshotProject.id.value
            patch(snapshot, "hex-3").let { venueFacetOf(it, extraPlacementsOf(it), PatchFacet.FOCUS) }
        }
        assertEquals(v1, decodeFacet(PatchFacet.FOCUS, focus.base), "base = v1")
        assertEquals(36, (decodeFacet(PatchFacet.FOCUS, focus.venueValue) as FocusFacet).beamAngleDeg)

        // Taking the venue's leaves no override, and lists the change.
        val taken = plan(h, answers = listOf(UpdateDecisionAnswer(drift.id, UpdateAnswer.TAKE_VENUES)))
        assertEquals(emptyList(), taken.writes.overrides)
        assertTrue(taken.decisions.single().decided)
    }

    @Test
    fun `a standalone show pairs with the venue by key and number, and every venue record it lacks comes in`() {
        val h = detachedShow()
        val standalone = transaction(state.database) {
            val project = DaoProject.new { name = "Pantomime" }
            val u0 = DaoUniverseConfig.new { this.project = project; subnet = 0; universe = 0; controllerType = "MOCK" }
            val venueHex = patch(VenueVersions.versionsOf(h.venueId).single().snapshotProject.id.value, "hex-4")
            DaoFixturePatch.new {
                this.project = project
                universeConfig = u0
                fixtureTypeKey = venueHex.fixtureTypeKey
                key = "hex-4"
                displayName = venueHex.displayName
                headNumber = venueHex.headNumber
                startChannel = 300
                stageHidden = venueHex.stageHidden
            }
            project.id.value
        }
        val p = plan(Hall(standalone, h.venueId))

        assertNull(p.origin, "no origin")
        val show = transaction(state.database) { VenueAttachRows.ofProject(DaoProject[standalone].id, DaoProject[standalone].id) }
        val venue = transaction(state.database) {
            VenueAttachRows.ofSnapshot(VenueVersions.versionsOf(h.venueId).single().snapshotProject.id, DaoProject[h.venueId].id)
        }
        val venueHex = venue.patches.single { it.key == "hex-4" }
        val venueU0 = venue.universes.single { it.universe == 0 }
        assertEquals(venueHex.uuid, p.writes.uuidRewrites[show.patches.single().uuid], "paired by key")
        assertEquals(venueU0.uuid, p.writes.uuidRewrites[show.universes.single().uuid], "paired by number")
        assertEquals(mapOf(AttachKind.UNIVERSE to 1, AttachKind.PATCH to 1), p.inherited)
        // The show's address is its own, with no origin to say otherwise.
        val address = p.writes.overrides.single { it.facet == PatchFacet.ADDRESS }
        assertEquals(AddressFacet(venueU0.uuid.toString(), 300), address.value)
        // Every venue record it lacks comes in, each offered a hide.
        assertEquals(venue.patches.size - 1, p.incoming.count { it.kind == AttachKind.PATCH })
        assertTrue(p.incoming.any { it.kind == AttachKind.UNIVERSE && it.venue?.key == "0:1" })
        assertEquals(p.incoming.count { it.kind != AttachKind.UNIVERSE && it.kind != AttachKind.PARK }, p.decisions.count { it.kind == "HIDE" })
        // Without an origin a venue park is released, so the show's output stays as it is.
        assertEquals(venue.parks.keys.sorted(), p.writes.releases)

        val hide = p.decisions.first { it.kind == "HIDE" && it.recordKind == AttachKind.PATCH }
        val hidden = plan(Hall(standalone, h.venueId), answers = listOf(UpdateDecisionAnswer(hide.id, UpdateAnswer.HIDE_VENUES)))
        assertEquals(listOf(HideKind.PATCH to UUID.fromString(hide.venue.uuid)), hidden.writes.hides)
    }

    @Test
    fun `a key a kept record holds hides the venue's record that has it`() {
        val h = detachedShow()
        // The show re-keys hex-4 after detaching; v2 adds a unit with that key.
        transaction(state.database) { patch(h.showId, "hex-4").key = "hex-9" }
        transaction(state.database) {
            val v = patch(h.venueId, "hex-4")
            DaoFixturePatch.new {
                project = DaoProject[h.venueId]
                universeConfig = v.universeConfig
                fixtureTypeKey = "hex"
                key = "hex-9"
                displayName = "Hex 9"
                startChannel = 400
            }
        }
        publish(h.venueId, "hex-9 added")
        val p = plan(h)

        val identity = p.decisions.single { it.kind == "IDENTITY" }
        assertEquals(UpdateAnswer.KEEP_MINE, identity.answer)
        val hides = p.hides.filter { it.kind == AttachKind.PATCH }.associate { it.venue!!.key to it.reason }
        assertEquals(mapOf<String?, String?>("hex-4" to "KEPT", "hex-9" to "KEY_CLASH"), hides)
        // Listed as added since v1 until the clash hid it: it comes in nowhere.
        assertTrue(p.incoming.none { it.venue?.key == "hex-9" }, "${p.incoming}")
        assertTrue(p.output.none { it.venue?.key == "hex-9" }, "${p.output}")
        assertEquals(emptyList(), p.blockers)
    }

    @Test
    fun `a key clash with a venue record that cannot be hidden blocks, as does a rename onto a kept key`() {
        val h = detachedShow()
        // The show re-keys hex-3 to hex-9 and keeps it; the venue re-keys hex-4 to hex-9, which the show
        // takes (its hex-4 is unchanged), so the venue's hex-9 is the show's own hex-4 and cannot be hidden.
        transaction(state.database) { patch(h.showId, "hex-3").key = "hex-9" }
        transaction(state.database) { patch(h.venueId, "hex-4").key = "hex-9" }
        publish(h.venueId, "hex-4 is hex-9")
        val p = plan(h)

        assertTrue(p.renames.any { it.from == "hex-4" && it.to == "hex-9" }, "${p.renames}")
        val reasons = p.blockers.map { it.reason }.toSet()
        assertEquals(setOf("KEY_CLASH", "RENAME_TAKEN"), reasons, "${p.blockers}")
        assertTrue(p.blockers.all { it.show?.key == "hex-9" || it.venue?.key == "hex-9" })
    }

    @Test
    fun `a changed fixture type keeps the show's under a fresh uuid, or takes the venue's with its renames`() {
        val h = detachedShow()
        val showHex = transaction(state.database) {
            patch(h.showId, "hex-4").apply { fixtureTypeKey = "generic-dimmer"; key = "hex-4b" }.let { it.id.value to it.uuid }
        }
        val p = plan(h, to = 1)

        val identity = p.decisions.single { it.kind == "IDENTITY" }
        assertEquals(UpdateAnswer.KEEP_MINE, identity.answer)
        assertEquals("hex-4b", identity.rename?.from)
        val fresh = p.writes.freshUuids.getValue(AttachKind.PATCH).getValue(showHex.first)
        assertTrue(fresh != showHex.second)
        assertEquals(fresh, p.writes.uuidRewrites[showHex.second])
        assertEquals(listOf(HideKind.PATCH to showHex.second), p.writes.hides, "the venue's hex-4 is hidden")
        assertTrue(p.kept.single().show?.key == "hex-4b")
        assertEquals(emptyList(), p.renames)

        val taken = plan(h, to = 1, answers = listOf(UpdateDecisionAnswer(identity.id, UpdateAnswer.TAKE_VENUES)))
        assertEquals(mapOf("hex-4b" to "hex-4"), taken.writes.fixtureRenames)
        assertNull(taken.writes.uuidRewrites[showHex.second], "replaced by the record of its own uuid")
        assertTrue(showHex.first in taken.writes.replaced.getValue(AttachKind.PATCH))
        assertEquals(emptyList(), taken.writes.hides)
        assertTrue(taken.output.any { it.show?.key == "hex-4b" && it.detail.contains("type") }, "a type change is output: ${taken.output}")
    }

    @Test
    fun `a group that differs is kept, and a rigging that differs takes the venue's`() {
        val h = detachedShow()
        val (groupId, boomId) = transaction(state.database) {
            val group = DaoFixtureGroup.find { (DaoFixtureGroups.project eq h.showId) and (DaoFixtureGroups.name eq "back-wash") }.single()
            DaoFixtureGroupMember.new { this.group = group; fixturePatch = patch(h.showId, "hex-4"); sortOrder = 1 }
            val boom = DaoRigging.find { (DaoRiggings.project eq h.showId) and (DaoRiggings.name eq "Boom-SL") }.single()
            boom.positionX = (boom.positionX ?: 0.0) + 1.5
            group.id.value to boom.id.value
        }
        val p = plan(h, to = 1)

        val group = p.decisions.single { it.kind == "GROUP" }
        assertEquals(UpdateAnswer.KEEP_MINE, group.answer)
        assertTrue(groupId in p.writes.freshUuids.getValue(AttachKind.GROUP))
        assertTrue(p.writes.hides.any { it.first == HideKind.GROUP })
        val rigging = p.decisions.single { it.kind == "HALL" }
        assertEquals(AttachKind.RIGGING, rigging.recordKind)
        assertEquals(UpdateAnswer.TAKE_VENUES, rigging.answer)
        assertTrue(boomId in p.writes.replaced.getValue(AttachKind.RIGGING))
        assertTrue(p.drawing.any { it.kind == AttachKind.RIGGING && it.show?.name == "Boom-SL" })
        assertEquals(emptyList(), p.blockers)

        // Flipped: the show's rigging kept under a fresh uuid (P5) and the venue's hidden, so the unit that
        // hangs on it, hex-3, follows the show's through a Position override naming the fresh uuid.
        val flipped = plan(h, to = 1, answers = listOf(UpdateDecisionAnswer(rigging.id, UpdateAnswer.KEEP_MINE)))
        val fresh = flipped.writes.freshUuids.getValue(AttachKind.RIGGING).getValue(boomId)
        assertTrue(flipped.writes.hides.any { it.first == HideKind.RIGGING })
        val position = flipped.writes.overrides.single { it.facet == PatchFacet.POSITION }.value as PositionFacet
        assertEquals(fresh.toString(), position.riggingUuid)
        assertFalse(flipped.overrides.single { it.facet == PatchFacet.POSITION }.dormant)
        assertEquals(emptyList(), flipped.blockers)
    }

    @Test
    fun `a show patch on a universe the venue lacks becomes an address override naming the show's universe`() {
        val h = detachedShow()
        val universe = transaction(state.database) {
            val u = DaoUniverseConfig.new { project = DaoProject[h.showId]; subnet = 0; universe = 5; controllerType = "MOCK" }
            patch(h.showId, "hex-4").apply { universeConfig = u; startChannel = 1 }
            u.uuid
        }
        val p = plan(h, to = 1)

        assertTrue(p.additions.any { it.kind == AttachKind.UNIVERSE && it.show?.uuid == universe.toString() })
        val address = p.writes.overrides.single { it.facet == PatchFacet.ADDRESS }
        assertEquals(AddressFacet(universe.toString(), 1), address.value)
        assertFalse(p.overrides.single { it.facet == PatchFacet.ADDRESS }.dormant, "the show's universe is in the attached rig")
    }

    @Test
    fun `a park unparked after detaching is released, and one the venue parked since comes in`() {
        val h = detachedShow()
        transaction(state.database) {
            DaoParkedChannel.find { (DaoParkedChannels.project eq h.showId) and (DaoParkedChannels.universe eq 0) and (DaoParkedChannels.channel eq 5) }
                .single().delete()
            DaoParkedChannel.new { project = DaoProject[h.venueId]; universe = 1; channel = 20; value = 99 }
        }
        publish(h.venueId, "1-020 parked")
        val p = plan(h)

        assertEquals(listOf(ParkAddress(0, 5)), p.writes.releases)
        val parked = p.incoming.single { it.kind == AttachKind.PARK }
        assertEquals("1-020", parked.venue?.uuid)
        assertTrue(parked in p.output, "an output change")
        assertEquals(2, p.inherited[AttachKind.PARK])
    }

    @Test
    fun `a park the venue re-valued or unparked since the origin is the venue's, and one the show re-valued stays`() {
        val h = detachedShow()
        transaction(state.database) {
            fun park(project: Int, universe: Int, channel: Int) = DaoParkedChannel.find {
                (DaoParkedChannels.project eq project) and (DaoParkedChannels.universe eq universe) and (DaoParkedChannels.channel eq channel)
            }.single()
            park(h.venueId, 0, 5).value = 64
            park(h.venueId, 1, 7).delete()
            park(h.showId, 0, 12).value = 33
        }
        publish(h.venueId, "parks moved")
        val p = plan(h)

        assertEquals(listOf(ParkAddress(0, 5), ParkAddress(1, 7)), p.writes.parksDropped.sorted(), "the show's copies at v1's values go")
        assertTrue(p.output.any { it.kind == AttachKind.PARK && it.show?.uuid == "0-005" && it.detail.contains("64") }, "${p.output}")
        assertTrue(p.output.any { it.kind == AttachKind.PARK && it.show?.uuid == "1-007" && it.detail.contains("unparked") }, "${p.output}")
        assertTrue(p.additions.any { it.kind == AttachKind.PARK && it.reason == "REPARK" && it.show?.uuid == "0-012" }, "the show's own re-value stays")
        assertEquals(emptyList(), p.writes.releases)
    }

    @Test
    fun `a show universe whose number a venue universe took blocks the attach`() {
        val h = detachedShow()
        // The show adds 0:2; v2 renumbers the venue's 0:1 to 0:2, which the show's 0:1 still pairs with by uuid.
        transaction(state.database) {
            DaoUniverseConfig.new { project = DaoProject[h.showId]; subnet = 0; universe = 2; controllerType = "MOCK" }
            DaoUniverseConfig.find { (DaoUniverseConfigs.project eq h.venueId) and (DaoUniverseConfigs.universe eq 1) }.single().universe = 2
        }
        publish(h.venueId, "universe 1 is 2")
        val p = plan(h)

        val blocker = p.blockers.single()
        assertEquals("UNIVERSE_CLASH", blocker.reason)
        assertEquals("0:2", blocker.show?.key)
        assertTrue(p.additions.none { it.kind == AttachKind.UNIVERSE }, "not listed as an addition")
        assertTrue(p.output.any { it.kind == AttachKind.UNIVERSE && it.detail.contains("0:2") }, "the renumber is output: ${p.output}")
    }

    @Test
    fun `a rigging the show renamed is a decision, and Keep mine beside a rigging that can't be hidden says both stay`() {
        val h = detachedShow()
        transaction(state.database) {
            DaoRigging.find { (DaoRiggings.project eq h.showId) and (DaoRiggings.name eq "Boom-SL") }.single().name = "Boom Left"
            // v2 hangs a new unit on the venue's Boom-SL, so it can't be hidden.
            val boom = DaoRigging.find { (DaoRiggings.project eq h.venueId) and (DaoRiggings.name eq "Boom-SL") }.single()
            DaoFixturePatch.new {
                project = DaoProject[h.venueId]
                universeConfig = patch(h.venueId, "hex-1").universeConfig
                fixtureTypeKey = "hex"
                key = "boom-spot"
                displayName = "Boom spot"
                startChannel = 450
                rigging = boom
            }
        }
        publish(h.venueId, "a unit on the boom")
        val p = plan(h)

        val hall = p.decisions.single { it.kind == "HALL" }
        assertEquals("Boom Left", hall.show?.name, "a rename alone differs")
        assertEquals(UpdateAnswer.TAKE_VENUES, hall.answer)

        val kept = plan(h, answers = listOf(UpdateDecisionAnswer(hall.id, UpdateAnswer.KEEP_MINE)))
        assertTrue(kept.writes.hides.none { it.first == HideKind.RIGGING }, "the venue's Boom-SL can't be hidden")
        val item = kept.kept.single { it.kind == AttachKind.RIGGING }
        assertEquals("KEPT_BESIDE", item.reason)
        assertTrue(item.detail.contains("hang on it"), item.detail)
        assertTrue(kept.incoming.any { it.kind == AttachKind.RIGGING && it.reason == "HIDE_REFUSED" && it.venue?.name == "Boom-SL" })
        assertEquals(emptyList(), kept.blockers, "different names: both may stand")
    }

    @Test
    fun `a rigging the venue re-made under a new uuid is still the origin's, so an untouched unit on it is the venue's change`() {
        val h = detachedShow()
        transaction(state.database) {
            val old = DaoRigging.find { (DaoRiggings.project eq h.venueId) and (DaoRiggings.name eq "Boom-SL") }.single()
            val remade = DaoRigging.new {
                project = DaoProject[h.venueId]
                name = "Boom-SL-new"
                kind = old.kind
                positionX = old.positionX
                positionY = old.positionY
                positionZ = old.positionZ
                yawDeg = old.yawDeg
                pitchDeg = old.pitchDeg
                rollDeg = old.rollDeg
                lengthM = old.lengthM
                sortOrder = old.sortOrder
            }
            patch(h.venueId, "hex-3").apply { rigging = remade; stageX = (stageX ?: 0.0) + 0.5 }
            old.delete()
            remade.name = "Boom-SL"
        }
        publish(h.venueId, "boom re-made, hex-3 nudged")
        val p = plan(h)

        assertTrue(p.decisions.none { it.kind == "DRIFT" }, "no false drift: ${p.decisions}")
        assertTrue(p.overrides.none { it.venue.key == "hex-3" }, "no override on hex-3: ${p.overrides}")
        assertTrue(p.drawing.any { it.show?.key == "hex-3" && it.detail.contains("position") }, "the venue's move is inherited: ${p.drawing}")
    }

    @Test
    fun `without an origin a park on a universe paired under another number is read and kept at the venue's number`() {
        val u = UUID.randomUUID()
        val show = VenueAttachPairing.Rows(
            universes = listOf(VenueAttachPairing.UniverseRow(1, u, 0, 1, "MOCK")),
            parks = mapOf(ParkAddress(1, 5) to 255.toUByte(), ParkAddress(1, 6) to 200.toUByte()),
        )
        val target = VenueAttachPairing.Rows(
            universes = listOf(VenueAttachPairing.UniverseRow(2, u, 0, 2, "MOCK")),
            parks = mapOf(ParkAddress(2, 5) to 255.toUByte(), ParkAddress(2, 6) to 255.toUByte()),
        )
        val p = VenueAttachPairing.pair(show, null, VenueAttachPairing.Version(UUID.randomUUID(), "Hall", 1, target))

        assertEquals(listOf(ParkAddress(1, 5)), p.writes.parksDropped, "the same level at the venue's number: inherited")
        assertEquals(mapOf(ParkAddress(1, 6) to ParkAddress(2, 6)), p.writes.parksMoved, "a re-park moves with its universe")
        assertEquals(emptyList(), p.writes.releases, "nothing released: the show still parks both channels")
        assertTrue(p.additions.any { it.reason == "REPARK" && it.show?.uuid == "2-006" }, "${p.additions}")
    }

    @Test
    fun `placements pair by uuid, then the rest in order`() {
        val u = UUID.randomUUID()
        fun facet(vararg placements: UUID) = mapOf<PatchFacet, FacetValue>(
            PatchFacet.ADDRESS to AddressFacet(u.toString(), 1),
            PatchFacet.POSITION to PositionFacet(placements = placements.mapIndexed { i, p -> PlacementPosition(p.toString(), stageX = i.toDouble()) }),
        )
        val shared = UUID.randomUUID()
        val mine = UUID.randomUUID()
        val theirs = UUID.randomUUID()
        val patchUuid = UUID.randomUUID()
        fun patchRow(placements: List<UUID>, facets: Map<PatchFacet, FacetValue>) = VenueAttachPairing.PatchRow(
            id = 1, uuid = patchUuid, key = "p", displayName = "P", fixtureTypeKey = "hex", headNumber = null,
            placements = placements.map { VenueAttachPairing.PlacementRow(0, it, null) }, facets = facets,
        )
        val universe = VenueAttachPairing.UniverseRow(1, u, 0, 0, "MOCK")
        // The show's are [shared, mine]; the venue's [theirs, shared]: mine pairs with theirs, the one left.
        val show = VenueAttachPairing.Rows(universes = listOf(universe), patches = listOf(patchRow(listOf(shared, mine), facet(shared, mine))))
        val target = VenueAttachPairing.Rows(universes = listOf(universe), patches = listOf(patchRow(listOf(theirs, shared), facet(theirs, shared))))
        val p = VenueAttachPairing.pair(show, null, VenueAttachPairing.Version(UUID.randomUUID(), "Hall", 1, target))

        assertEquals(theirs, p.writes.uuidRewrites[mine])
        val position = p.writes.overrides.single { it.facet == PatchFacet.POSITION }.value as PositionFacet
        assertEquals(setOf(shared.toString(), theirs.toString()), position.placements.map { it.placementUuid }.toSet(), "no placement dropped")
    }

    @Test
    fun `without an origin a record a clash hides offers no hide of its own`() {
        val u = UUID.randomUUID()
        val universe = VenueAttachPairing.UniverseRow(1, u, 0, 0, "MOCK")
        fun patchRow(id: Int, uuid: UUID, key: String, start: Int) = VenueAttachPairing.PatchRow(
            id = id, uuid = uuid, key = key, displayName = key, fixtureTypeKey = "hex", headNumber = null, placements = emptyList(),
            facets = mapOf(PatchFacet.ADDRESS to AddressFacet(u.toString(), start)),
        )
        val shared = UUID.randomUUID()
        val other = UUID.randomUUID()
        // The show's "a" is the venue's "b" by uuid, and keeps its key; the venue's "a" is another unit.
        val show = VenueAttachPairing.Rows(universes = listOf(universe), patches = listOf(patchRow(1, shared, "a", 1)))
        val target = VenueAttachPairing.Rows(universes = listOf(universe), patches = listOf(patchRow(2, shared, "b", 1), patchRow(3, other, "a", 20)))
        val p = VenueAttachPairing.pair(show, null, VenueAttachPairing.Version(UUID.randomUUID(), "Hall", 1, target))

        assertEquals(listOf(HideKind.PATCH to shared, HideKind.PATCH to other).toSet(), p.writes.hides.toSet())
        assertTrue(p.decisions.none { it.kind == "HIDE" && it.venue.uuid == other.toString() }, "${p.decisions}")
        assertTrue("hide:PATCH:$other" in p.decisionIds, "an answer to it, from a review that listed it, is still known")
        assertTrue(p.incoming.none { it.venue?.uuid == other.toString() })
    }

    @Test
    fun `without an origin every venue record the show lacks comes in`() {
        val f = seedShowOnVenue(state)
        detach(f.showId)
        transaction(state.database) { DaoProject[f.showId].apply { detachedFromVenueUuid = null; detachedFromVersion = null } }
        val p = plan(Hall(f.showId, f.venueId), to = 1)

        assertNull(p.origin)
        assertTrue(p.writes.hides.none { it.second == f.hiddenUuid })
        assertTrue(p.incoming.any { it.venue?.uuid == f.hiddenUuid.toString() && it.reason == "NO_ORIGIN" }, "${p.incoming}")
        assertTrue(p.decisions.any { it.kind == "HIDE" && it.venue.uuid == f.hiddenUuid.toString() })
        // The release still comes back: without an origin a venue park the show lacks is released.
        assertEquals(listOf(ParkAddress(0, 12)), p.writes.releases)
    }

    @Test
    fun `an origin this desk lacks pairs without one, and says so`() {
        val f = seedShowOnVenue(state)
        detach(f.showId)
        transaction(state.database) { DaoProject[f.showId].detachedFromVersion = 99 }
        val p = plan(Hall(f.showId, f.venueId), to = 1)

        assertEquals(AttachOriginStatus.MISSING, p.origin?.status)
        assertEquals(99, p.origin?.version)
        val detail = p.origin!!.detail
        assertTrue(detail.contains("doesn't have"), detail)
        assertTrue(p.incoming.any { it.venue?.uuid == f.hiddenUuid.toString() && it.reason == "NO_ORIGIN" })
    }

    @Test
    fun `a head number a show patch shares with a venue patch blocks the attach, though Detach let it through`() {
        val showId = seedVenueReadyShow(state)
        val venueId = runBlocking {
            state.venueExtract.extract(showId, "Commemoration Hall", VenueExtract.ExtractKind.entries.toSet(), dryRun = false, publishedBy = "Chris")
        }.venueId!!
        transaction(state.database) {
            val show = DaoProject[showId]
            val u0 = DaoUniverseConfig.find {
                (DaoUniverseConfigs.project eq RigScope.of(show).venue!!.snapshotId) and (DaoUniverseConfigs.universe eq 0)
            }.single()
            DaoFixturePatch.new {
                project = show
                universeConfig = u0
                fixtureTypeKey = "hex"
                key = "follow-1"
                displayName = "Follow 1"
                headNumber = 103
                startChannel = 480
            }
        }
        detach(showId)
        transaction(state.database) {
            assertNull(DaoProject[showId].venueUuid, "Detach's own check misses a head number")
            assertEquals(2, DaoFixturePatch.find { (DaoFixturePatches.project eq showId) and (DaoFixturePatches.headNumber eq 103) }.count())
        }
        val p = plan(Hall(showId, venueId), to = 1)

        val blocker = p.blockers.single()
        assertEquals("HEAD_CLASH", blocker.reason)
        assertEquals("follow-1", blocker.show?.key)
        assertEquals("hex-2", blocker.venue?.key)
    }

    @Test
    fun `detach records the origin, a clone keeps it and an extract clears it`() {
        val h = detachedShow()
        val (venueUuid, origin) = transaction(state.database) {
            DaoProject[h.venueId].uuid to DaoProject[h.showId].let { it.detachedFromVenueUuid to it.detachedFromVersion }
        }
        assertEquals(venueUuid to 1, origin)

        val clone = ProjectCloner(state).clone(h.showId, "Sleeping Beauty (copy)", null)
        transaction(state.database) {
            val copy = DaoProject[clone.projectId]
            assertEquals(venueUuid, copy.detachedFromVenueUuid)
            assertEquals(1, copy.detachedFromVersion)
        }

        runBlocking {
            state.venueExtract.extract(h.showId, "Commemoration Hall 2", VenueExtract.ExtractKind.entries.toSet(), dryRun = false, publishedBy = "Chris")
        }
        transaction(state.database) {
            val show = DaoProject[h.showId]
            assertNotNull(show.venueUuid)
            assertNull(show.detachedFromVenueUuid, "a show that extracts a venue is on it, not detached from another")
            assertNull(show.detachedFromVersion)
        }
    }
}
