package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID

/**
 * Which one-shot tubes on this machine are **spent** (stage-view plan session 9, §3.2): a row per
 * fired tube, keyed by the patch's uuid and the trigger's name, gone again when the tube is
 * reloaded. Loaded is the absence of a row.
 *
 * **Machine-local**, never synced and never cloned: whether a confetti tube on this rig has been
 * fired is a fact about the physical cannon in this hall, not about the show. Keyed by the patch's
 * **uuid**, not its id, so a row survives a re-import of the project that owns it (an import keeps
 * uuids) and simply stops mattering for a patch that is gone. Read into memory when a show starts and
 * written off the firing path (`state/EffectsService.kt`), so a fire never waits on SQLite.
 */
object DaoEffectTubeStates : IntIdTable("effect_tube_state") {
    val patchUuid = javaUUID("patch_uuid")
    /** The trigger's name on its fixture type (`output1`). */
    val trigger = varchar("trigger", 64)
    val spentAt = utcInstant("spent_at")

    init {
        uniqueIndex(patchUuid, trigger)
    }
}
