import { useSyncExternalStore } from 'react'
import { createSyncStore, sessionStorageArea } from '../../../lib/syncStore'
import type { StageElementDto } from '../../../api/stageElementApi'
import { DEFAULT_LIGHT_BUDGET, LIGHT_BUDGETS } from './lightTable'

/**
 * What of the scene this window draws — the View menu's **Venue**, **Set**, **Seating** and **Haze**
 * (stage-view plan session 3, `Stage.dc.html`'s View menu) — and how much light its surfaces take.
 *
 * - **The four layers are per window**, in `sessionStorage`, as the viewpoint is: the hall screen can
 *   show the empty room while the desk screen shows the set. They are not announced — nothing on
 *   another window sets them.
 * - **The light budget is per browser**, in `localStorage`: it is what this machine's GPU can
 *   afford (§10 of the plan), not what one window is looking at.
 *
 * A seating element follows **Seating** whatever its layer; every other element follows its layer.
 * **Haze** is whether the air shows the beams at all; the level itself is still `washConfig.ts`'s
 * constant until the rig can say which fixture is its hazer (`FU-STAGE-HAZE-FOLLOWS-HAZER`).
 */
export interface SceneLayers {
  venue: boolean
  set: boolean
  seating: boolean
  haze: boolean
}

export type SceneLayer = keyof SceneLayers

export const DEFAULT_SCENE_LAYERS: SceneLayers = { venue: true, set: true, seating: true, haze: true }

export const SCENE_LAYERS_KEY = 'stage.sceneLayers'
export const LIGHT_BUDGET_KEY = 'stage.lightBudget'

const layersStore = createSyncStore<SceneLayers>({
  key: SCENE_LAYERS_KEY,
  fallback: DEFAULT_SCENE_LAYERS,
  // Field by field over the defaults: a value an older or later build wrote must not switch a layer
  // off by omission, nor carry a key this build has no toggle for.
  parse: (parsed) => {
    const p = (parsed ?? {}) as Partial<Record<string, unknown>>
    const pick = (key: SceneLayer) => (typeof p[key] === 'boolean' ? (p[key] as boolean) : DEFAULT_SCENE_LAYERS[key])
    return { venue: pick('venue'), set: pick('set'), seating: pick('seating'), haze: pick('haze') }
  },
  storage: sessionStorageArea,
})

export function useSceneLayers(): SceneLayers {
  return useSyncExternalStore(layersStore.subscribe, layersStore.getSnapshot, layersStore.getServerSnapshot)
}

export function setSceneLayer(layer: SceneLayer, on: boolean): void {
  layersStore.set({ ...layersStore.getSnapshot(), [layer]: on })
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

/** Whether [element] is drawn under [layers]: a seating by **Seating**, anything else by its layer. */
export function elementInLayers(element: Pick<StageElementDto, 'kind' | 'layer'>, layers: SceneLayers): boolean {
  if (element.kind === 'SEATING') return layers.seating
  return element.layer === 'SET' ? layers.set : layers.venue
}

/** Test seam: both stores back to their defaults, with no listeners. */
export function resetSceneViewStores(): void {
  layersStore.reset()
  budgetStore.reset()
}
