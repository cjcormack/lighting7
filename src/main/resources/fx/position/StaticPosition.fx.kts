/*---
id: StaticPosition
name: Static Position
category: position
outputType: POSITION
effectMode: STANDARD
defaultStepTiming: true
compatibleProperties: [pan, tilt]
parameters:
  - name: pan
    type: ubyte
    default: "128"
    description: Pan position
  - name: tilt
    type: ubyte
    default: "128"
    description: Tilt position
---*/

val pan = params.ubyte("pan")
val tilt = params.ubyte("tilt")

if (context.groupSize <= 1 || !context.hasDistributionSpread) {
    FxOutput.Position(pan, tilt)
} else {
    val window = 1.0 / context.numDistinctSlots
    val base = context.basePhase(phase)

    val active = if (context.trianglePhase) {
        abs(base - context.distributionOffset) < window / 2.0
    } else {
        val dist = (base - context.distributionOffset + 1.0) % 1.0
        dist < window
    }

    if (active) FxOutput.Position(pan, tilt) else FxOutput.Position(128u, 128u)
}
