/*---
id: RandomPosition
name: Random Position
family: position
outputType: POSITION
fxMode: STANDARD
compatibleAttributes: [position]
parameters:
  - name: panCentre
    type: ubyte
    default: "128"
    description: Centre pan position
  - name: tiltCentre
    type: ubyte
    default: "128"
    description: Centre tilt position
  - name: panRange
    type: ubyte
    default: "64"
    description: Range of pan movement
  - name: tiltRange
    type: ubyte
    default: "64"
    description: Range of tilt movement
---*/

val panCentre = params.ubyte("panCentre")
val tiltCentre = params.ubyte("tiltCentre")
val panRange = params.ubyte("panRange")
val tiltRange = params.ubyte("tiltRange")
val panOffset = (sin(phase * 127.0) * panRange.toInt()).toInt()
val tiltOffset = (sin(phase * 211.0) * tiltRange.toInt()).toInt()
val pan = (panCentre.toInt() + panOffset).coerceIn(0, 255).toUByte()
val tilt = (tiltCentre.toInt() + tiltOffset).coerceIn(0, 255).toUByte()
FxOutput.Position(pan, tilt)
