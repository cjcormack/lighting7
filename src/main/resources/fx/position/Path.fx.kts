/*---
id: Path
name: Path
family: position
outputType: POSITION
fxMode: STANDARD
compatibleAttributes: [position]
parameters:
  - name: shape
    type: choice
    default: "CIRCLE"
    description: The path each head traces over one cycle
    label: Shape
    options: [CIRCLE: Circle, FIGURE_8: Figure 8, LINE: Line, RANDOM: Random]
  - name: panCentre
    type: ubyte
    default: "128"
    description: Centre pan position
    label: Pan
    min: 0
    max: 255
    step: 1
    unit: position
    shownWhen: shape = [CIRCLE, FIGURE_8, RANDOM]
  - name: tiltCentre
    type: ubyte
    default: "128"
    description: Centre tilt position
    label: Tilt
    min: 0
    max: 255
    step: 1
    unit: position
    shownWhen: shape = [CIRCLE, FIGURE_8, RANDOM]
  - name: panSize
    type: ubyte
    default: "64"
    description: How far the path reaches either side of the centre in pan
    label: Pan size
    min: 0
    max: 255
    step: 1
    unit: dmx
    shownWhen: shape = [CIRCLE, FIGURE_8, RANDOM]
  - name: tiltSize
    type: ubyte
    default: "64"
    description: How far the path reaches either side of the centre in tilt
    label: Tilt size
    min: 0
    max: 255
    step: 1
    unit: dmx
    shownWhen: shape = [CIRCLE, FIGURE_8, RANDOM]
  - name: startPan
    type: ubyte
    default: "64"
    description: Pan at the line's start
    label: Start pan
    min: 0
    max: 255
    step: 1
    unit: position
    shownWhen: shape = [LINE]
  - name: startTilt
    type: ubyte
    default: "128"
    description: Tilt at the line's start
    label: Start tilt
    min: 0
    max: 255
    step: 1
    unit: position
    shownWhen: shape = [LINE]
  - name: endPan
    type: ubyte
    default: "192"
    description: Pan at the line's end
    label: End pan
    min: 0
    max: 255
    step: 1
    unit: position
    shownWhen: shape = [LINE]
  - name: endTilt
    type: ubyte
    default: "128"
    description: Tilt at the line's end
    label: End tilt
    min: 0
    max: 255
    step: 1
    unit: position
    shownWhen: shape = [LINE]
  - name: curve
    type: easingCurve
    default: "SINE_IN_OUT"
    description: Easing along the line
    label: Curve
    role: detail
    shownWhen: shape = [LINE]
presets:
  - name: Sweep
    attribute: position
    parameters: {shape: LINE}
    cycle: 2.0
    direction: BOUNCE
  - name: Pan Sweep
    attribute: position
    parameters: {shape: LINE, startPan: 64, endPan: 192, startTilt: 128, endTilt: 128}
    cycle: 2.0
    direction: BOUNCE
  - name: Tilt Sweep
    attribute: position
    parameters: {shape: LINE, startPan: 128, endPan: 128, startTilt: 64, endTilt: 192}
    cycle: 2.0
    direction: BOUNCE
---*/

val shape = params.choice("shape")

fun axis(centre: UByte, size: UByte, wave: Double): UByte =
    (centre.toInt() + size.toInt() * wave).toInt().coerceIn(0, 255).toUByte()

fun along(start: UByte, end: UByte, eased: Double): UByte =
    (start.toInt() + (end.toInt() - start.toInt()) * eased).toInt().coerceIn(0, 255).toUByte()

when (shape) {
    "LINE" -> {
        val eased = params.easingCurve("curve").apply(phase)
        FxOutput.Position(
            along(params.ubyte("startPan"), params.ubyte("endPan"), eased),
            along(params.ubyte("startTilt"), params.ubyte("endTilt"), eased),
        )
    }
    "RANDOM" -> {
        val panCentre = params.ubyte("panCentre").toInt()
        val tiltCentre = params.ubyte("tiltCentre").toInt()
        val panOffset = (sin(phase * 127.0) * params.ubyte("panSize").toInt()).toInt()
        val tiltOffset = (sin(phase * 211.0) * params.ubyte("tiltSize").toInt()).toInt()
        FxOutput.Position((panCentre + panOffset).coerceIn(0, 255).toUByte(), (tiltCentre + tiltOffset).coerceIn(0, 255).toUByte())
    }
    else -> {
        val angle = phase * 2 * PI
        val panCentre = params.ubyte("panCentre")
        val tiltCentre = params.ubyte("tiltCentre")
        val panSize = params.ubyte("panSize")
        val tiltSize = params.ubyte("tiltSize")
        if (shape == "FIGURE_8") {
            FxOutput.Position(axis(panCentre, panSize, sin(angle)), axis(tiltCentre, tiltSize, sin(2 * angle)))
        } else {
            FxOutput.Position(axis(panCentre, panSize, cos(angle)), axis(tiltCentre, tiltSize, sin(angle)))
        }
    }
}
