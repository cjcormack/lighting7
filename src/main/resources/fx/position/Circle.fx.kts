/*---
id: Circle
name: Circle
family: position
outputType: POSITION
fxMode: STANDARD
compatibleAttributes: [position]
parameters:
  - name: panCentre
    type: ubyte
    default: "128"
    description: Centre pan position
    label: Pan
    min: 0
    max: 255
    step: 1
    unit: position
  - name: tiltCentre
    type: ubyte
    default: "128"
    description: Centre tilt position
    label: Tilt
    min: 0
    max: 255
    step: 1
    unit: position
  - name: panRadius
    type: ubyte
    default: "64"
    description: Radius of pan movement
    label: Pan size
    min: 0
    max: 255
    step: 1
    unit: dmx
  - name: tiltRadius
    type: ubyte
    default: "64"
    description: Radius of tilt movement
    label: Tilt size
    min: 0
    max: 255
    step: 1
    unit: dmx
---*/

val panCentre = params.ubyte("panCentre")
val tiltCentre = params.ubyte("tiltCentre")
val panRadius = params.ubyte("panRadius")
val tiltRadius = params.ubyte("tiltRadius")
val angle = phase * 2 * PI
val pan = (panCentre.toInt() + panRadius.toInt() * cos(angle)).toInt().coerceIn(0, 255).toUByte()
val tilt = (tiltCentre.toInt() + tiltRadius.toInt() * sin(angle)).toInt().coerceIn(0, 255).toUByte()
FxOutput.Position(pan, tilt)
