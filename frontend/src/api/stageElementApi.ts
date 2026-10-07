import { InternalApiConnection } from './internalApi'
import { parseSceneryState, type SceneryState } from './sceneryApi'
import { Subscription } from './subscription'
import { createChangeSignalApi } from './wsSubscriptionFactory'

/**
 * The scene document's elements (stage-view plan session 2, D2): named pieces of the venue and the
 * set, each with a pose, a size, a finish and its kind's `params`. Coordinates are lighting metres
 * (FOH-relative, Z-up). `positionZ` is the element's base, except a `PLATFORM`'s, which is its top
 * surface — as a region's `centerZ` is.
 *
 * Enumerations are the backend's upper-case names. `params` is left loosely typed here: each kind's
 * builder in `components/stage3d/scene/builders/` reads its own (seating's through
 * `lib/stageSeats.ts`), and a value it cannot read falls back to its default.
 */
export type StageElementKind = 'ROOM' | 'PROSCENIUM' | 'FLAT' | 'DRAPE' | 'PLATFORM' | 'SEATING' | 'OBJECT'
export type StageElementLayer = 'VENUE' | 'SET'

export interface StageElementDto {
  id: number
  uuid: string
  name: string
  kind: StageElementKind
  layer: StageElementLayer
  positionX: number
  positionY: number
  positionZ: number
  yawDeg: number
  widthM: number
  depthM: number
  heightM: number
  finishColour: string | null
  finishPattern: string | null
  emissive: boolean
  params: Record<string, unknown>
  hidden: boolean
  sortOrder: number
}

/** A create; every field but the name and kind defaults. */
export interface CreateStageElementRequest {
  name: string
  kind: StageElementKind
  layer?: StageElementLayer
  positionX?: number
  positionY?: number
  positionZ?: number
  yawDeg?: number
  widthM?: number
  depthM?: number
  heightM?: number
  finishColour?: string | null
  finishPattern?: string | null
  emissive?: boolean
  params?: Record<string, unknown>
  hidden?: boolean
}

/** A partial update: fields present overwrite, fields absent keep. */
export type UpdateStageElementRequest = Partial<CreateStageElementRequest> & { sortOrder?: number }

/**
 * What moves one element (scenery-programmer plan D11): `GET stage-elements/{id}/scenery`, every
 * owner's own stored row for it — the cues that change it (in show order), the stacks whose set
 * holds it and the Looks that show it while live. Never what an owner merely tracks. The Stage
 * popover's and the element form's *Moves with*.
 */
export interface ElementScenery {
  cues: ElementCueScenery[]
  sets: ElementSetScenery[]
  looks: ElementLookScenery[]
}

/** A cue that moves the element on GO; a null [transitionMs] moves with the cue's own fade. */
export interface ElementCueScenery {
  stackId: number
  cueId: number
  /** The cue's number where it has one, else its name. */
  label: string
  state: SceneryState
  transitionMs: number | null
}

export interface ElementSetScenery {
  stackId: number
  /** The stack's name. */
  name: string
  state: SceneryState
}

export interface ElementLookScenery {
  lookId: number
  name: string
  state: SceneryState
}

export const NO_ELEMENT_SCENERY: ElementScenery = { cues: [], sets: [], looks: [] }

/** The read off the wire, keeping only well-formed entries and well-typed states. */
export function parseElementScenery(raw: unknown): ElementScenery {
  if (raw == null || typeof raw !== 'object') return NO_ELEMENT_SCENERY
  const r = raw as Record<string, unknown>
  const list = (v: unknown) => (Array.isArray(v) ? (v as unknown[]).filter((x): x is Record<string, unknown> => x != null && typeof x === 'object') : [])
  return {
    cues: list(r.cues)
      .filter((c) => typeof c.stackId === 'number' && typeof c.cueId === 'number')
      .map((c) => ({
        stackId: c.stackId as number,
        cueId: c.cueId as number,
        label: typeof c.label === 'string' ? c.label : '',
        state: parseSceneryState(c.state),
        transitionMs: typeof c.transitionMs === 'number' ? c.transitionMs : null,
      })),
    sets: list(r.sets)
      .filter((s) => typeof s.stackId === 'number')
      .map((s) => ({ stackId: s.stackId as number, name: typeof s.name === 'string' ? s.name : '', state: parseSceneryState(s.state) })),
    looks: list(r.looks)
      .filter((l) => typeof l.lookId === 'number')
      .map((l) => ({ lookId: l.lookId as number, name: typeof l.name === 'string' ? l.name : '', state: parseSceneryState(l.state) })),
  }
}

export interface StageElementApi {
  subscribe(fn: () => void): Subscription
}

export function createStageElementApi(conn: InternalApiConnection): StageElementApi {
  return createChangeSignalApi(conn, 'stageElementListChanged')
}
