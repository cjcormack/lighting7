package uk.me.cormack.lighting7.midi

/**
 * Which half of the dispatch a control part feeds, and which a function takes ([Spec.inputs]): a
 * fader's or an encoder's turn is continuous, a button's press and an encoder's push are buttons
 * ([inputFor]).
 */
enum class ControlKind { CONTINUOUS, BUTTON }

/**
 * A binding write the service refused, carrying the machine-readable [code] the client branches on.
 *
 * An [IllegalArgumentException] so every caller — every REST route — maps it
 * to a 400; the routes additionally read [code] where they build their
 * [uk.me.cormack.lighting7.routes.ErrorResponse].
 */
class BindingRefused(message: String, val code: String) : IllegalArgumentException(message)

/** A function the control part's input can never reach. */
const val CODE_BINDING_WRONG_CONTROL_KIND = "BINDING_WRONG_CONTROL_KIND"

/** A [ColourAxis] on a property that is not colour-typed, which has no such axis to drive. */
const val CODE_BINDING_AXIS_NEEDS_COLOUR = "BINDING_AXIS_NEEDS_COLOUR"

/** A subject the function's spec does not take — a fan on one fixture, a GO on a cue. */
const val CODE_BINDING_WRONG_SUBJECT = "BINDING_WRONG_SUBJECT"

/** A [How] the function's spec does not take — a fader While held, a GO as a Toggle. */
const val CODE_BINDING_WRONG_HOW = "BINDING_WRONG_HOW"
