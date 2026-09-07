package uk.me.cormack.lighting7.midi

/**
 * Which half of [SurfaceInputRouter]'s dispatch a control reaches, and which half a target needs.
 *
 * The pair of functions below is the whole of `FU-MIDI-BIND-CONTROL-KIND`'s vocabulary, and both are
 * read off the router rather than guessed from a descriptor's name — the two surprises are in
 * [dispatchableKinds]. `lib/surfaceDrop.ts` in `lighting-react` mirrors this exactly, and since
 * `ControlSurfaceBindingService.refuseWrongKind` the client copy is the mirror rather than the only
 * copy: before that, `canLand` and the dim it drives were the entire guard, covering one drag
 * gesture in one UI while MIDI Learn, a hand-rolled REST call and a script all reached the same
 * service unchecked.
 */
enum class ControlKind { CONTINUOUS, BUTTON }

/**
 * What the desk will actually dispatch on this control, from the profile alone.
 *
 * - An **encoder with a push note reaches both halves** — its CC is a `Continuous` and its note a
 *   `ButtonPress`, on one control id. So an encoder legitimately takes a cue target as well as a
 *   property one, and the X-Touch declares a push on all sixteen.
 * - A **bank button takes nothing at all**. `route` answers `ResolvedInput.BankButton` and switches
 *   the bank *before* resolving a binding, so a row on one can never fire. Empty is the honest
 *   answer, and it is what makes the refusal say so rather than naming a kind.
 */
fun dispatchableKinds(descriptor: ControlDescriptor): Set<ControlKind> = when (descriptor) {
    is FaderDescriptor -> setOf(ControlKind.CONTINUOUS)
    is EncoderDescriptor ->
        if (descriptor.pushNote != null) setOf(ControlKind.CONTINUOUS, ControlKind.BUTTON)
        else setOf(ControlKind.CONTINUOUS)
    is ButtonDescriptor -> setOf(ControlKind.BUTTON)
    is BankButtonDescriptor -> emptySet()
}

/**
 * Which half of the dispatch a target belongs to, or null for one that is not dispatched at all.
 *
 * [BindingTarget.Strip] addresses a strip id and is resolved to a control's own target by
 * derivation; [BindingTarget.Unknown] is never accepted from a request. Both are refused by name
 * elsewhere ([ControlSurfaceBindingService] `refuseWrongSlot` / `refuseUnknown`), so answering null
 * here keeps one rule per refusal instead of two saying overlapping things.
 */
fun targetControlKind(target: BindingTarget): ControlKind? = when (target) {
    is BindingTarget.FixtureProperty,
    is BindingTarget.GroupProperty,
    is BindingTarget.SelectionProperty,
    is BindingTarget.SpeedMasterBpm,
        -> ControlKind.CONTINUOUS

    is BindingTarget.Flash,
    is BindingTarget.CueStackGo,
    is BindingTarget.CueStackBack,
    is BindingTarget.CueStackPause,
    is BindingTarget.FireCue,
    is BindingTarget.SelectTarget,
    BindingTarget.ClearSelection,
    BindingTarget.LocateSelection,
    is BindingTarget.EncoderBankSet,
    BindingTarget.Blackout,
    BindingTarget.GrandMasterToggle,
    is BindingTarget.SetBank,
    is BindingTarget.SpeedMasterTap,
    is BindingTarget.ApplyLook,
    is BindingTarget.PressTemplate,
    is BindingTarget.PressPad,
    BindingTarget.BuskPageNext,
    BindingTarget.BuskPagePrev,
    is BindingTarget.BuskPageSet,
        -> ControlKind.BUTTON

    is BindingTarget.Strip,
    is BindingTarget.Unknown,
        -> null
}

/**
 * A binding write the service refused, carrying the machine-readable [code] the client branches on.
 *
 * An [IllegalArgumentException] so every existing caller — both REST routes and MIDI Learn's commit
 * — already maps it to a 400 without change; the routes additionally read [code] where they build
 * their [uk.me.cormack.lighting7.routes.ErrorResponse]. This exists so a rule can live on the
 * service *and* answer a coded 400: session 2 had to duplicate the strip rules into the routes to
 * get both, and four more rules would have meant four more copies.
 */
class BindingRefused(message: String, val code: String) : IllegalArgumentException(message)

/** A binding whose target the control's half of the dispatch can never reach. */
const val CODE_BINDING_WRONG_CONTROL_KIND = "BINDING_WRONG_CONTROL_KIND"

/** An `applyLook` binding on a Look with a deferred effect, which has no own targets to press onto. */
const val CODE_BINDING_LOOK_NEEDS_SELECTION = "BINDING_LOOK_NEEDS_SELECTION"

/** A [ColourAxis] on a property that is not colour-typed, which has no such axis to drive. */
const val CODE_BINDING_AXIS_NEEDS_COLOUR = "BINDING_AXIS_NEEDS_COLOUR"
