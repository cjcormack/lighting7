/*---
id: SlotSteps
name: Slot Steps
family: steps
outputType: STEPS
fxMode: STANDARD
stepsFrom: slots
compatibleAttributes: [setting]
parameters:
  - name: slots
    type: slotList
    default: ""
    description: Comma-separated option names of the setting — each head goes to its own level for each, and holds where it has no such slot
    label: Slots
  - name: between
    type: choice
    default: "CUT"
    description: How each slot gives way to the next — on a colour wheel that splits, Split holds their split
    label: Between
    options: [CUT: Cut, SPLIT: Split]
  - name: fade
    type: double
    default: "0.5"
    description: Share of each slot's step spent in its split with the next
    label: Split for
    min: 0
    max: 1
    step: 0.01
    unit: ratio
    shownWhen: between = [SPLIT]
---*/

// A slot has no halfway, so a step snaps; Split holds a colour wheel's split of two neighbours instead
// (wheel-colour-chase plan D6), and any other setting changes at the split's middle.
val slots = params.slotSteps("slots")

when (params.choice("between")) {
    "SPLIT" -> stepsAt(phase, slots, params.double("fade"), split = true)
    else -> stepsAt(phase, slots)
}
