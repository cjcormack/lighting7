/*---
id: RandomPosition
name: Random Position
category: position
outputType: POSITION
effectMode: STANDARD
compatibleProperties: [pan, tilt]
parameters:
  - name: panCenter
    type: ubyte
    default: "128"
    description: Center pan position
  - name: tiltCenter
    type: ubyte
    default: "128"
    description: Center tilt position
  - name: panRange
    type: ubyte
    default: "64"
    description: Range of pan movement
  - name: tiltRange
    type: ubyte
    default: "64"
    description: Range of tilt movement
---*/

val panCenter = params.ubyte("panCenter")
val tiltCenter = params.ubyte("tiltCenter")
val panRange = params.ubyte("panRange")
val tiltRange = params.ubyte("tiltRange")
val panOffset = (sin(phase * 127.0) * panRange.toInt()).toInt()
val tiltOffset = (sin(phase * 211.0) * tiltRange.toInt()).toInt()
val pan = (panCenter.toInt() + panOffset).coerceIn(0, 255).toUByte()
val tilt = (tiltCenter.toInt() + tiltOffset).coerceIn(0, 255).toUByte()
FxOutput.Position(pan, tilt)
