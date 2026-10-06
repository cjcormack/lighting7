import { useSyncExternalStore } from 'react'
import { createSyncStore, sessionStorageArea } from '../../../lib/syncStore'
import type { SurfaceUniforms } from './surfaceShader'

/**
 * **Work lights** (stage-view menu plan D6–D8; `stage-view-menu-design/WorkLights.dc.html`): the
 * View popover's *Work lights · Off | On*, the stage-light plan's *Realistic / Readable*, named for
 * what it imitates. On lifts the dark — the room's ambient rises and every surface's directional
 * fill gains a **lift** — so the house, the set and the rig show their shape, while every pool keeps
 * its exposure: `SURFACE_LIGHT_GAIN`, the roll-off, the haze, the lenses and the canvas's background
 * do not move. Off is the room as lit, and draws exactly what it drew before there was a switch.
 *
 * **Uniforms, never a define**, so a switch recompiles nothing and costs one frame: the ambient and
 * the lift are the surfaces' shared uniforms (`surfaceShader.ts`), the housings' own fill rises with
 * the room so the rig does not vanish against a newly lit floor (`bodies/StageBodies.tsx`), and the
 * billboard housings follow through `litByFill`.
 *
 * **Per window and announced** (D8): `sessionStorage`, default off, riding `viewOptions` as
 * `workLights` beside the source, so a Screens row can set it — the desk screen plots with work
 * lights on while the hall screen shows the room as lit. Every canvas in the window follows it: the
 * Stage view and the Positions plan. A `render_view` capture never reads it: its own comes with the
 * request (D9).
 *
 * **Free of three.js on purpose**: `lib/stageViewpoint.ts` applies and announces the switch, and it is
 * on the app shell's import path (the windows bridge, the Screens sheet), which must not pull the
 * renderer in. So the off row restates `surfaceShader.ts`'s `SURFACE_AMBIENT` and `bodies/palette.ts`'s
 * `HOUSING_FILL` as literals, and `workLights.test.ts` pins the two equal.
 */
export const WORK_LIGHTS = ['off', 'on'] as const
export type WorkLights = (typeof WORK_LIGHTS)[number]

export const DEFAULT_WORK_LIGHTS: WorkLights = 'off'

export const WORK_LIGHTS_KEY = 'stage.workLights'

/** The key work lights ride under in a Stage window's `viewOptions`, beside `viewpoint` and `source`. */
export const VIEW_OPTION_WORK_LIGHTS = 'workLights'

export function isWorkLights(value: unknown): value is WorkLights {
  return typeof value === 'string' && (WORK_LIGHTS as readonly string[]).includes(value)
}

/**
 * One row of the table: the room's ambient, the lift every surface's directional fill gains, and
 * the housings' own fill.
 */
export interface WorkLightLevels {
  ambient: number
  lift: number
  housing: number
}

/**
 * The two rows (D6, level *a*). Measured on the desk's *Balcony · desk* at this level: the floor
 * pool 119 → 121 of 255 and the black backcloth's not at all, while an unlit seat went 1 → 17 and
 * the pale ceiling stayed below the floor pool (95 against 121) — brighter levels put the ceiling
 * over it. On the Plan the housings needed their own fill at 0.8 to read against the lit floor.
 * Off is today's constants: `SURFACE_AMBIENT` and `HOUSING_FILL`.
 */
export const WORK_LIGHT_LEVELS: Readonly<Record<WorkLights, WorkLightLevels>> = {
  off: { ambient: 0.003, lift: 0, housing: 0.225 },
  on: { ambient: 0.02, lift: 0.04, housing: 0.8 },
}

/**
 * Write [levels]' room into a canvas's shared surface uniforms: the ambient and the lift. Every
 * surface material spreads the same uniform objects, so one write reaches them all.
 */
export function applyWorkLights(uniforms: Pick<SurfaceUniforms, 'uAmbient' | 'uLift'>, levels: WorkLightLevels): void {
  uniforms.uAmbient.value = levels.ambient
  uniforms.uLift.value = levels.lift
}

const store = createSyncStore<WorkLights>({
  key: WORK_LIGHTS_KEY,
  fallback: DEFAULT_WORK_LIGHTS,
  parse: (parsed) => (isWorkLights(parsed) ? parsed : DEFAULT_WORK_LIGHTS),
  storage: sessionStorageArea,
})

export function useWorkLights(): WorkLights {
  return useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot)
}

/** This window's work lights, read outside React. */
export function workLights(): WorkLights {
  return store.getSnapshot()
}

/** Switch this window's work lights. A value outside the vocabulary is ignored. */
export function setWorkLights(next: WorkLights): void {
  if (isWorkLights(next)) store.set(next)
}

/** Test seam: the store back to off, with no listeners. */
export function resetWorkLightsStore(): void {
  store.reset()
}
