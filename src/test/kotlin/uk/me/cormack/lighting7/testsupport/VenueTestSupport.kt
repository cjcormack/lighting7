package uk.me.cormack.lighting7.testsupport

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
