package uk.me.cormack.lighting7.testsupport

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.DaoParkedChannel
import uk.me.cormack.lighting7.models.DaoShowParkRelease
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.ShowColours
import uk.me.cormack.lighting7.models.DaoFixtureGroupMember
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.DaoRiggings
import uk.me.cormack.lighting7.models.DaoStageRegion
import uk.me.cormack.lighting7.models.DaoStageRegions
import uk.me.cormack.lighting7.models.DaoStageViewpoint
import uk.me.cormack.lighting7.models.DaoStageViewpoints
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.AddressFacet
import uk.me.cormack.lighting7.models.MediaFacet
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.models.PositionFacet
import uk.me.cormack.lighting7.venue.ShowHides
import uk.me.cormack.lighting7.venue.ShowOverrides
import uk.me.cormack.lighting7.venue.VenueException
import uk.me.cormack.lighting7.venue.VenueExtract
import java.util.UUID
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoStageElement
import uk.me.cormack.lighting7.models.SeatingParams
import uk.me.cormack.lighting7.models.StageElementKind
import uk.me.cormack.lighting7.models.StageElementLayer
import uk.me.cormack.lighting7.models.encodeElementParams
import uk.me.cormack.lighting7.state.State

/**
 * The rich fixture as a show ready to extract a venue from (venue plan session 3): not current (the
 * integration test's own project is), its four placeholder hexes made real `hex` units spaced so
 * their 12 channels do not overlap — so the rig loads and two loads can be compared — and a Preset
 * seating block added beside the fixture's Fixed one (venue plan D6).
 */
fun seedVenueReadyShow(state: State): Int {
    val id = seedRichProject(state)
    transaction(state.database) {
        val project = DaoProject[id]
        project.isCurrent = false
        DaoFixturePatch.find { (DaoFixturePatches.project eq id) and (DaoFixturePatches.fixtureTypeKey eq "hex-fixture") }
            .forEach { it.fixtureTypeKey = "hex"; it.startChannel = it.startChannel * 2 - 10 }
        DaoStageElement.new {
            this.project = project
            name = "Cabaret tables"
            kind = StageElementKind.SEATING.name
            layer = StageElementLayer.VENUE.name
            positionY = -6.0
            // No block: a Preset.
            params = encodeElementParams(
                StageElementKind.SEATING,
                SeatingParams(rows = 2, seatsPerRow = 4, rowPitchM = 1.2, seatPitchM = 0.6),
            )
            sortOrder = 9
        }
    }
    return id
}

/**
 * A show on a venue, as venue plan session 4's sync tests need one (§3.4): the rich fixture extracted
 * into "Commemoration Hall" (v1, the show pinned to it), the venue's draft then changed and published
 * as **v2** — the show stays on v1 — and the show given a layer of its own: a **hide** of a venue
 * record, an **addition** on a venue universe (non-default gel and position, so canonical JSON carries
 * them), and a show group holding a venue patch beside the addition (D12). The show still holds the
 * fixture's cues, cue events, scenery and busk rig, now linking into the venue. And (session 5) the show
 * **overrides** three facets of one venue patch, every value non-default so canonical JSON carries it:
 * its Address (start channel 470 on the venue's universe 0), its Position (hung on the show's own
 * "Panto boom SR" — a show-owned rigging, so a clone must remap the value's rigging uuid) and its Media
 * (gel L202).
 */
data class VenueShowFixture(
    val showId: Int,
    val venueId: Int,
    val hideKind: HideKind,
    val hiddenUuid: UUID,
    val additionUuid: UUID,
    /** The venue patch the show overrides, by uuid. */
    val overriddenUuid: UUID,
    /** The show's own rigging the Position override hangs it on. */
    val boomUuid: UUID,
)

fun seedShowOnVenue(state: State): VenueShowFixture {
    val showId = seedVenueReadyShow(state)
    val venueId = runBlocking {
        state.venueExtract.extract(showId, "Commemoration Hall", VenueExtract.ExtractKind.entries.toSet(), dryRun = false, publishedBy = "Chris")
    }.venueId!!
    // v2: the hall moves its FOH truss. The show stays on v1 (an Update is session 6).
    transaction(state.database) {
        DaoRigging.find { DaoRiggings.project eq venueId }.orderBy(DaoRiggings.sortOrder to org.jetbrains.exposed.v1.core.SortOrder.ASC)
            .first().apply { positionX = (positionX ?: 0.0) + 0.75 }
    }
    runBlocking { state.venueVersions.publish(venueId, "FOH truss moved stage left", "Chris") }

    val (hideKind, hidden) = transaction(state.database) {
        val show = DaoProject[showId]
        val snapshot = RigScope.of(show).venue!!.snapshotId
        // The first venue record the show does not use, tried kind by kind through the same rule the
        // route applies (`VENUE_HIDE_IN_USE`), so the hide is one an operator could make.
        val candidates = DaoFixturePatch.find { DaoFixturePatches.project eq snapshot }.map { HideKind.PATCH to it.uuid } +
            DaoRigging.find { DaoRiggings.project eq snapshot }.map { HideKind.RIGGING to it.uuid } +
            DaoStageRegion.find { DaoStageRegions.project eq snapshot }.map { HideKind.REGION to it.uuid } +
            DaoStageViewpoint.find { DaoStageViewpoints.project eq snapshot }.map { HideKind.VIEWPOINT to it.uuid }
        candidates.first { (kind, uuid) ->
            try {
                ShowHides.setHiddenByUuid(show, kind, uuid, hidden = true)
            } catch (_: VenueException) {
                false
            }
        }
    }

    val (additionUuid, venuePatchId) = transaction(state.database) {
        val show = DaoProject[showId]
        val rig = RigScope.of(show)
        val universe = DaoUniverseConfig.find {
            (DaoUniverseConfigs.project eq rig.venue!!.snapshotId) and (DaoUniverseConfigs.universe eq 0)
        }.single()
        val addition = DaoFixturePatch.new {
            project = show
            universeConfig = universe
            fixtureTypeKey = "hex"
            key = "panto-follow"
            displayName = "Panto follow"
            startChannel = 400
            sortOrder = 40
            stageX = 1.5
            stageY = -2.0
            gelCode = "L201"
        }
        val venuePatch = rig.patches().first { it.project.id.value != showId && it.uuid != hidden }
        val group = DaoFixtureGroup.new {
            project = show
            name = "Panto specials"
        }
        DaoFixtureGroupMember.new {
            this.group = group
            fixturePatch = addition
            sortOrder = 0
            panOffset = 5.0
        }
        DaoFixtureGroupMember.new {
            this.group = group
            fixturePatch = venuePatch
            sortOrder = 1
            tiltOffset = -3.0
        }
        addition.uuid to venuePatch.id.value
    }

    val (overriddenUuid, boomUuid) = transaction(state.database) {
        val show = DaoProject[showId]
        val boom = DaoRigging.new {
            project = show
            name = "Panto boom SR"
            kind = "BOOM"
            positionX = -4.5
            positionY = 1.0
            positionZ = 0.0
            sortOrder = 30
        }
        val target = ShowOverrides.target(show, venuePatchId)
        val universe = DaoUniverseConfig.find {
            (DaoUniverseConfigs.project eq target.venue.snapshotId) and (DaoUniverseConfigs.universe eq 0)
        }.single()
        ShowOverrides.put(show, target, PatchFacet.ADDRESS, AddressFacet(universe.uuid.toString(), 470))
        ShowOverrides.put(
            show, target, PatchFacet.POSITION,
            PositionFacet(riggingUuid = boom.uuid.toString(), stageZ = 2.25, baseYawDeg = 15.0, basePitchDeg = 180.0),
        )
        ShowOverrides.put(show, target, PatchFacet.MEDIA, MediaFacet(gelCode = "L202"))
        // Fixture colours (formatVersion 36): the show recolours the overridden venue patch and the
        // venue's first group, both to colours the venue does not use.
        ShowColours.put(show.id, target.patch.uuid, "pink")
        val venueGroup = DaoFixtureGroup.find { DaoFixtureGroups.project eq target.venue.snapshotId }
            .orderBy(DaoFixtureGroups.name to org.jetbrains.exposed.v1.core.SortOrder.ASC).first()
        ShowColours.put(show.id, venueGroup.uuid, "cyan")
        target.patch.uuid to boom.uuid
    }
    // Venue parks (formatVersion 27): the rich project's three parks moved into the venue with the
    // extract, so the show inherits them from its version. It releases one (0/12) and re-parks another
    // (1/7) at a value of its own, over the venue's 255 — every value non-default.
    transaction(state.database) {
        val show = DaoProject[showId]
        DaoShowParkRelease.new {
            project = show
            universe = 0
            channel = 12
        }
        DaoParkedChannel.new {
            project = show
            universe = 1
            channel = 7
            value = 200
        }
    }
    return VenueShowFixture(showId, venueId, hideKind, hidden, additionUuid, overriddenUuid, boomUuid)
}
