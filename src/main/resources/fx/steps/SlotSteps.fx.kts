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
---*/

// A slot has no halfway, so there is no fade: each step snaps.
stepsAt(phase, params.slotSteps("slots"))
