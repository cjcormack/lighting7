package uk.me.cormack.lighting7.show

import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerWriter

/**
 * Locate as a starting point. Until a located fixture is edited, Locate is a toggle that puts back
 * what was there; the moment the operator edits it — a value, a fader, a fan, an aim, a palette or
 * Look pressed onto it, a layer edited onto it — the Locate becomes the operator's own programmer
 * values ([ProgrammerOwner.WEB], re-owned in place so nothing on stage moves) and the fixture is no
 * longer located. What the edit itself sets wins: a layer landing on a located key takes that key, so
 * a position palette pressed over a located head moves it.
 */

/** The key of the patch [fixture] is — its parent's for a head. */
internal fun patchKeyOf(fixture: GroupableFixture): String =
    (fixture as? FixtureElement<*>)?.parentFixture?.key ?: fixture.targetKey

/**
 * Commit the Locate of every located target reaching a patch among [fixtureKeys] (a head reaches its
 * fixture's Locate, and the fixture its heads'). On each key in [release] Locate's slot is dropped
 * rather than kept, with any operator value it was hiding. Stores only — the caller publishes what
 * [release] changed. Returns whether anything was located there.
 */
internal fun Show.commitLocates(
    fixtureKeys: Set<String>,
    release: Set<CueAssignmentResolver.Key> = emptySet(),
): Boolean {
    if (locateManager.activeTargets.value.isEmpty()) return false
    val patchOf = HashMap<String, String?>()
    fun patch(key: String) = patchOf.getOrPut(key) { patchKeyOrNull(key) }
    val patches = fixtureKeys.mapNotNullTo(HashSet(), ::patch)
    if (patches.isEmpty()) return false
    val committed = locateManager.commit(
        reaches = { writes -> writes.any { patch(it.targetKey) in patches } },
        commit = { writes ->
            for (write in writes) {
                if (CueAssignmentResolver.Key.fixture(write.targetKey, write.attribute) in release) {
                    releaseLocate(write.targetKey, write.attribute)
                } else {
                    programmerStore.reown(ProgrammerOwner.LOCATE, ProgrammerOwner.WEB, write.targetKey, write.attribute)
                }
            }
        },
    )
    if (committed.isEmpty()) return false
    // As `toggleLocate`'s backstop: once nothing is located, no LOCATE slot is legitimate.
    if (locateManager.activeTargets.value.isEmpty()) programmerStore.clearOwner(ProgrammerOwner.LOCATE)
    return true
}

/**
 * [ProgrammerLayerStack]'s `onLanded`: commit the Locate of every located fixture a layer just landed
 * on — on any of its attributes, as an operator write does — letting Locate go on the landed keys it
 * holds so the layer shows. Answers those keys, for the layer's recook to publish.
 */
internal fun Show.landOnLocates(keys: Set<CueAssignmentResolver.Key>): Set<CueAssignmentResolver.Key> {
    if (locateManager.activeTargets.value.isEmpty()) return emptySet()
    val located = locateManager.writes()
    val held = keys.filterTo(HashSet()) { LocateManager.LocateWrite(it.targetKey, it.attribute) in located }
    commitLocates(keys.mapTo(HashSet()) { it.targetKey }, release = held)
    return held
}

/** Drop Locate's slot on one key, and the operator values beneath it that it was hiding. */
private fun Show.releaseLocate(fixtureKey: String, attribute: String) {
    val slots = programmerStore.slotsFor(fixtureKey, attribute)
    val locate = slots.indexOfFirst { it.owner == ProgrammerOwner.LOCATE }
    if (locate < 0) return
    for (slot in slots.drop(locate + 1)) {
        if (slot.owner in ProgrammerWriter.OPERATOR_OWNERS) programmerStore.clear(slot.owner, fixtureKey, attribute)
    }
    programmerStore.clear(ProgrammerOwner.LOCATE, fixtureKey, attribute)
}

private fun Show.patchKeyOrNull(fixtureKey: String): String? = try {
    patchKeyOf(fixtures.untypedGroupableFixture(fixtureKey))
} catch (_: IllegalStateException) {
    null
}
