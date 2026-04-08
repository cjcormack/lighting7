/*---
id: Figure8
name: Figure 8
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
  - name: panRadius
    type: ubyte
    default: "64"
    description: Horizontal extent
  - name: tiltRadius
    type: ubyte
    default: "32"
    description: Vertical extent
---*/

val panCenter = params.ubyte("panCenter")
val tiltCenter = params.ubyte("tiltCenter")
val panRadius = params.ubyte("panRadius")
val tiltRadius = params.ubyte("tiltRadius")
val angle = phase * 2 * PI
val pan = (panCenter.toInt() + panRadius.toInt() * sin(angle)).toInt().coerceIn(0, 255).toUByte()
val tilt = (tiltCenter.toInt() + tiltRadius.toInt() * sin(2 * angle)).toInt().coerceIn(0, 255).toUByte()
FxOutput.Position(pan, tilt)
