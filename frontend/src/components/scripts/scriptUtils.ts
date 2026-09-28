import {
  Braces,
  Sparkles,
  AudioWaveform,
  Calculator,
  Database,
  Layers,
  type LucideIcon,
} from 'lucide-react'
import type { ScriptType } from '@/store/scripts'

export const SCRIPT_TYPE_LABELS: Record<ScriptType, string> = {
  GENERAL: 'General',
  FX_DEFINITION: 'FX Definition',
  FX_APPLICATION: 'FX Application',
  FX_CALC: 'FX Calc',
  FX_CALC_STATEFUL: 'FX Calc Stateful',
  FX_CALC_COMPOSITE: 'FX Calc Composite',
}

export const SCRIPT_TYPE_DESCRIPTIONS: Record<ScriptType, string> = {
  GENERAL: 'General-purpose scripts for lighting automation. Has access to fixtures, the show, FX engine, and coroutines.',
  FX_DEFINITION: 'Defines a reusable effect type that appears in the FX Library. Specifies parameters, properties, and the calculation logic.',
  FX_APPLICATION: 'Applies effects to fixtures. Used by cue triggers to set up and run effect instances on the FX engine.',
  FX_CALC: 'Stateless effect calculation. Receives a phase value (0-1) and parameters, returns output values. Runs every tick.',
  FX_CALC_STATEFUL: 'Stateful effect calculation with a persistent state map. Receives clock ticks and delta time for time-based effects.',
  FX_CALC_COMPOSITE: 'Composite effect calculation that can combine or layer multiple sub-effects. Receives phase and parameters.',
}

export const ALL_SCRIPT_TYPES: ScriptType[] = [
  'GENERAL',
  'FX_DEFINITION',
  'FX_APPLICATION',
  'FX_CALC',
  'FX_CALC_STATEFUL',
  'FX_CALC_COMPOSITE',
]

export const SCRIPT_TYPE_ICONS: Record<ScriptType, LucideIcon> = {
  GENERAL: Braces,
  FX_DEFINITION: Sparkles,
  FX_APPLICATION: AudioWaveform,
  FX_CALC: Calculator,
  FX_CALC_STATEFUL: Database,
  FX_CALC_COMPOSITE: Layers,
}

export const SCRIPT_TYPE_TEMPLATES: Record<ScriptType, string> = {
  GENERAL: `// Available: show, fixtures, fxEngine, settings, coroutineScope
// step, scriptName

// Set all fixtures to a colour
for (fixture in fixtures) {
    fixture.setDimmer()
    if (fixture is FixtureWithColor) {
        fixture.setColor(Color.BLUE)
    }
}
`,
  FX_DEFINITION: `// Define an effect type for the FX Library
// Available: show, settings, scriptId

// Register parameters that users can configure
val dimParam = registerFloatParam("dim", "Dimmer", 0.0, 1.0, 1.0)

// Register the effect properties
registerProperty("intensity", FixturePropertyType.INTENSITY)

// Set the calculation script (by name or ID)
// setCalcScript("MyCalcScript")

`,
  FX_APPLICATION: `// Apply effects to fixtures
// Available: show, fxEngine, settings, step

// Look up fixtures
// val group = show.fixtureGroups["My Group"]

// Apply an effect from the library
// fxEngine.applyEffect("MyEffect", fixtures, mapOf())

`,
  FX_CALC: `// Stateless effect calculation — runs every tick
// Available: phase (0.0–1.0), context, params

// Read parameters
// val dim = params.getFloat("dim")

// Calculate output based on phase
val value = sin(phase * 2 * PI)

// Set output properties
context.setIntensity(((value + 1) / 2).toFloat())

`,
  FX_CALC_STATEFUL: `// Stateful effect calculation with persistent state
// Available: tick, deltaMs, context, params, state

// Initialise state on first tick
if ("pos" !in state) {
    state["pos"] = 0.0
}

// Update state based on time
val speed = params.getFloat("speed")
val pos = (state["pos"] as Double) + speed * deltaMs / 1000.0
state["pos"] = pos % 1.0

// Set output
context.setIntensity(pos.toFloat())

`,
  FX_CALC_COMPOSITE: `// Composite effect calculation — combine or layer sub-effects
// Available: phase, context, params

// Layer multiple wave shapes together
val sine = sin(phase * 2 * PI)
val triangle = 1 - 4 * abs(phase - 0.5)

// Mix the two shapes using a parameter
val mix = params.getFloat("mix")
val combined = sine * (1 - mix) + triangle * mix

context.setIntensity(((combined + 1) / 2).toFloat())

`,
}
