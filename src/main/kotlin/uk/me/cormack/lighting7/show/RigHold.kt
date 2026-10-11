package uk.me.cormack.lighting7.show

import uk.me.cormack.lighting7.dmx.OutputHold

/**
 * A show's hold as its loader sees it (venue plan P5, session 6): the [output] every controller the
 * loader builds shares, and [judge], which decides — before any controller exists — whether the
 * project being loaded is held, and which channels it leaves reserved. One per show
 * ([Show.rigHold]); the deciding is `state/VenueHoldService.kt`'s.
 */
class RigHold(val output: OutputHold, private val judgeProject: (Int) -> Unit) {
    /** Judge [projectId]'s hold now. Blocking: it reads the database, and may export a version once. */
    fun judge(projectId: Int) = judgeProject(projectId)
}
