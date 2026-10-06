import { useSyncExternalStore } from 'react'
import { createSyncStore, sessionStorageArea } from '../../../lib/syncStore'
import type { StageElementDto } from '../../../api/stageElementApi'
import { DEFAULT_LIGHT_BUDGET, LIGHT_BUDGETS } from './lightTable'
import { MAX_LIGHT_COLLIDERS } from './occlusion'

/**
 * What of the scene this window draws — the View menu's **Venue**, **Set**, **Seating** and **Haze**
 * (stage-view plan session 3, `Stage.dc.html`'s View menu) — and how much light its surfaces take.
 *
 * - **The four layers are per window**, in `sessionStorage`, as the viewpoint is: the hall screen can
 *   show the empty room while the desk screen shows the set. They are not announced — nothing on
 *   another window sets them.
 * - **The light budget is per browser**, in `localStorage`: it is what this machine's GPU can
 *   afford (§10 of the plan), not what one window is looking at.
 * - **So is where gobos land** (fixture-optics plan session 4, §10): every gobo light samples the
 *   gobo atlas on every surface it reaches by default; *Selected heads* limits that to the
 *   selection, and every other head keeps its plain pool (its gobo still shows in the air). It is
 *   the fallback the plan names if a machine's budget runs short, so it is the machine's too.
 * - **And how many boxes a light may be shadowed by** (stage-light plan session 3): every lit pixel
 *   tests its light's list of colliders (`occlusion.ts`), which an iPad pays for six times over what
 *   the desk Mac does. A light whose cone reaches more than the cap keeps its landing planes, so a
 *   lower cap takes the shadows of the widest cones first; *Off* puts every light on its planes.
 *
 * A seating element follows **Seating** whatever its layer; every other element follows its layer.
 * **Haze** is how far the air shows the beams: not at all, only upstage of the proscenium (the stage
 * edge without one — `hazeClipFor` in `stageSurfaces.ts`), or the house too. The level itself is
 * still `washConfig.ts`'s constant until the rig can say which fixture is its hazer
 * (`FU-STAGE-HAZE-FOLLOWS-HAZER`).
 */
export interface SceneLayers {
  venue: boolean
  set: boolean
  seating: boolean
  haze: HazeExtent
}

/** The layers that are simply drawn or not. */
export type SceneLayer = 'venue' | 'set' | 'seating'

export const HAZE_EXTENTS = ['off', 'stage', 'everywhere'] as const
export type HazeExtent = (typeof HAZE_EXTENTS)[number]

export function isHazeExtent(value: unknown): value is HazeExtent {
  return typeof value === 'string' && (HAZE_EXTENTS as readonly string[]).includes(value)
}

export const DEFAULT_SCENE_LAYERS: SceneLayers = { venue: true, set: true, seating: true, haze: 'stage' }

export const SCENE_LAYERS_KEY = 'stage.sceneLayers'
export const LIGHT_BUDGET_KEY = 'stage.lightBudget'
export const GOBO_SURFACES_KEY = 'stage.goboSurfaces'
export const BOX_SHADOWS_KEY = 'stage.boxShadows'

/** How many boxes a light may be shadowed by, as the View menu offers it. */
export const BOX_SHADOWS = ['all', 'some', 'off'] as const
export type BoxShadows = (typeof BOX_SHADOWS)[number]
/** Every light up to the list's width: the desk Mac pays ~0.1–0.27 ms a frame an entry at the bench's load. */
export const DEFAULT_BOX_SHADOWS: BoxShadows = 'all'

/** The cap each setting puts on a light's collider list; past it, a light keeps its landing planes. */
export const BOX_SHADOW_CAPS: Readonly<Record<BoxShadows, number>> = { all: MAX_LIGHT_COLLIDERS, some: 16, off: 0 }

export function isBoxShadows(value: unknown): value is BoxShadows {
  return typeof value === 'string' && (BOX_SHADOWS as readonly string[]).includes(value)
}

/** Which heads' gobos land on surfaces: every gobo light's, or only the selected heads'. */
export const GOBO_SURFACES = ['all', 'selected'] as const
export type GoboSurfaces = (typeof GOBO_SURFACES)[number]
/** Every gobo light: SwiftShader gave no reason to narrow it (stage-vis doc §"Gobos on surfaces"). */
export const DEFAULT_GOBO_SURFACES: GoboSurfaces = 'all'

export function isGoboSurfaces(value: unknown): value is GoboSurfaces {
  return typeof value === 'string' && (GOBO_SURFACES as readonly string[]).includes(value)
}

/** Whether a head's gobos land on surfaces under [mode]: always under `all`, else only if selected. */
export function goboLandsOnSurfaces(mode: GoboSurfaces, selected: boolean): boolean {
  return mode === 'all' || selected
}

/**
 * Stored layers, field by field over the defaults: a value an older or later build wrote must not
 * switch a layer off by omission, nor carry a key this build has no toggle for.
 */
export function parseSceneLayers(parsed: unknown): SceneLayers {
  const p = (parsed ?? {}) as Partial<Record<string, unknown>>
  const pick = (key: SceneLayer) => (typeof p[key] === 'boolean' ? (p[key] as boolean) : DEFAULT_SCENE_LAYERS[key])
  // Haze was a boolean; an old `false` still means none.
  const haze = isHazeExtent(p.haze) ? p.haze : p.haze === false ? 'off' : DEFAULT_SCENE_LAYERS.haze
  return { venue: pick('venue'), set: pick('set'), seating: pick('seating'), haze }
}

const layersStore = createSyncStore<SceneLayers>({
  key: SCENE_LAYERS_KEY,
  fallback: DEFAULT_SCENE_LAYERS,
  parse: parseSceneLayers,
  storage: sessionStorageArea,
})

export function useSceneLayers(): SceneLayers {
  return useSyncExternalStore(layersStore.subscribe, layersStore.getSnapshot, layersStore.getServerSnapshot)
}

export function setSceneLayer(layer: SceneLayer, on: boolean): void {
  layersStore.set({ ...layersStore.getSnapshot(), [layer]: on })
}

export function setHazeExtent(haze: HazeExtent): void {
  if (isHazeExtent(haze)) layersStore.set({ ...layersStore.getSnapshot(), haze })
}

export function isLightBudget(value: unknown): value is number {
  return typeof value === 'number' && LIGHT_BUDGETS.includes(value)
}

const budgetStore = createSyncStore<number>({
  key: LIGHT_BUDGET_KEY,
  fallback: DEFAULT_LIGHT_BUDGET,
  parse: (parsed) => (isLightBudget(parsed) ? parsed : DEFAULT_LIGHT_BUDGET),
})

export function useLightBudget(): number {
  return useSyncExternalStore(budgetStore.subscribe, budgetStore.getSnapshot, budgetStore.getServerSnapshot)
}

export function setLightBudget(budget: number): void {
  if (isLightBudget(budget)) budgetStore.set(budget)
}

const goboSurfacesStore = createSyncStore<GoboSurfaces>({
  key: GOBO_SURFACES_KEY,
  fallback: DEFAULT_GOBO_SURFACES,
  parse: (parsed) => (isGoboSurfaces(parsed) ? parsed : DEFAULT_GOBO_SURFACES),
})

export function useGoboSurfaces(): GoboSurfaces {
  return useSyncExternalStore(goboSurfacesStore.subscribe, goboSurfacesStore.getSnapshot, goboSurfacesStore.getServerSnapshot)
}

export function setGoboSurfaces(mode: GoboSurfaces): void {
  if (isGoboSurfaces(mode)) goboSurfacesStore.set(mode)
}

const boxShadowsStore = createSyncStore<BoxShadows>({
  key: BOX_SHADOWS_KEY,
  fallback: DEFAULT_BOX_SHADOWS,
  parse: (parsed) => (isBoxShadows(parsed) ? parsed : DEFAULT_BOX_SHADOWS),
})

export function useBoxShadows(): BoxShadows {
  return useSyncExternalStore(boxShadowsStore.subscribe, boxShadowsStore.getSnapshot, boxShadowsStore.getServerSnapshot)
}

export function setBoxShadows(mode: BoxShadows): void {
  if (isBoxShadows(mode)) boxShadowsStore.set(mode)
}

/** Whether [element] is drawn under [layers]: a seating by **Seating**, anything else by its layer. */
export function elementInLayers(element: Pick<StageElementDto, 'kind' | 'layer'>, layers: SceneLayers): boolean {
  if (element.kind === 'SEATING') return layers.seating
  return element.layer === 'SET' ? layers.set : layers.venue
}

/** Test seam: every store back to its default, with no listeners. */
export function resetSceneViewStores(): void {
  layersStore.reset()
  budgetStore.reset()
  goboSurfacesStore.reset()
  boxShadowsStore.reset()
}
