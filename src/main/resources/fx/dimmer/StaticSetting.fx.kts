/*---
id: StaticSetting
name: StaticSetting
category: dimmer
outputType: SLIDER
effectMode: STANDARD
defaultStepTiming: true
compatibleProperties: [dimmer, uv]
parameters:
  - name: level
    type: ubyte
    default: "0"
    description: The DMX level for the setting
---*/

val level = params.ubyte("level")
FxOutput.Slider(level)
