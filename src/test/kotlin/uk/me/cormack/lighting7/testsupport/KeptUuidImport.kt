package uk.me.cormack.lighting7.testsupport

import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.KeptUuidCopy

/**
 * A second copy of project [sourceId] as [name], every record keeping its uuid and only the
 * project's own uuid fresh — the shape a venue version has (venue plan P2). Production's
 * [KeptUuidCopy], which publishing a venue version uses; kept as a one-line helper so the tests
 * that read it (`RigScopeEquivalenceTest`, `VenueLinksRepointTest`) say what they mean. Returns the
 * copy's project id.
 */
fun importWithKeptRecordUuids(state: State, sourceId: Int, name: String): Int =
    KeptUuidCopy.copy(state, sourceId, name).projectId
