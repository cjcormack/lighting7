package uk.me.cormack.lighting7.midi

/**
 * What one dispatched [BindingTarget] did. A MIDI press has no reply channel, so the router only
 * logs what is not [Done]; an OSC message's outcome is its row in the address log
 * (`docs/osc-engineering.md` §"The log").
 */
sealed interface ActionOutcome {
    /** It happened, or the state asked for already held (D4's no-op). [detail] names what it reached. */
    data class Done(val detail: String? = null) : ActionOutcome

    /** The record exists but the action cannot be made now: [code] says why. */
    data class Refused(val code: String, val message: String) : ActionOutcome

    /** The record it names is not in the current project. */
    data class NotFound(val message: String) : ActionOutcome

    /** It threw — a project switch in flight among the causes — and [SurfaceDispatch.guarded] caught it. */
    data class Failed(val message: String) : ActionOutcome

    companion object {
        val DONE: ActionOutcome = Done()

        /** A continuous target pressed as a button, or the reverse. */
        const val WRONG_KIND = "WRONG_KIND"

        /** A stack target naming a marker, or a cue entry that is not in the stack. */
        const val NOT_A_CUE = "NOT_A_CUE"

        /** The stack has no cue entry to start at. */
        const val STACK_EMPTY = "STACK_EMPTY"

        /** The stack manager refused a GO or a GO BACK; the message says why. */
        const val GO_REFUSED = "GO_REFUSED"

        /** A tempo write on a master that follows another. */
        const val SPEED_MASTER_FOLLOWER = "SPEED_MASTER_FOLLOWER"

        /** A tempo write that would leave 20–300 BPM. */
        const val SPEED_MASTER_RANGE = "SPEED_MASTER_RANGE"
    }
}
