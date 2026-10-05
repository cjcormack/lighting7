import type { StageElementDto, StageElementLayer, UpdateStageElementRequest } from '@/api/stageElementApi'

/**
 * `EditSceneElementForm`'s draft and the write it makes — pure, so what a save sends is pinned by a
 * node test. The draft holds every field of the element and its `params` whole, and a save is a
 * partial `PUT` of the fields that moved, `params` whole where anything in it did: the desk lays a
 * partial update over the stored row and checks the result whole.
 */

export type Params = Record<string, unknown>

export interface Draft {
  name: string
  layer: StageElementLayer
  positionX: number | null
  positionY: number | null
  positionZ: number | null
  yawDeg: number | null
  widthM: number | null
  depthM: number | null
  heightM: number | null
  finishColour: string
  finishPattern: string
  emissive: boolean
  hidden: boolean
  params: Params
}

export function draftOf(element: StageElementDto): Draft {
  return {
    name: element.name,
    layer: element.layer,
    positionX: element.positionX,
    positionY: element.positionY,
    positionZ: element.positionZ,
    yawDeg: element.yawDeg,
    widthM: element.widthM,
    depthM: element.depthM,
    heightM: element.heightM,
    finishColour: element.finishColour ?? '',
    finishPattern: element.finishPattern ?? '',
    emissive: element.emissive,
    hidden: element.hidden,
    params: structuredClone(element.params ?? {}),
  }
}

/** [params] with [key] set, or taken out where [value] is null, undefined or empty. */
export function withParam(params: Params, key: string, value: unknown): Params {
  const next = { ...params }
  if (value == null || value === '') delete next[key]
  else next[key] = value
  return next
}

export function statesOf(params: Params): Params {
  const s = params.states
  return s != null && typeof s === 'object' ? (s as Params) : {}
}

/** [params] with one of its `states` set; `states` itself goes when nothing is left in it. */
export function withState(params: Params, key: string, value: unknown): Params {
  const states = withParam(statesOf(params), key, value)
  return withParam(params, 'states', Object.keys(states).length > 0 ? states : null)
}

/**
 * [params] with [key] set, then with the states that key's new value takes away taken out too: a
 * drape that stops drawing loses its `open`, a piece that stops flying its `trimM`. Their fields go
 * from the form as the kind stops offering them, and a value left behind would be one the desk
 * refuses and the operator has no field to clear.
 */
export function withKindParam(kind: StageElementDto['kind'], params: Params, key: string, value: unknown): Params {
  let next = withParam(params, key, value)
  const operation = typeof next.operation === 'string' ? next.operation.toUpperCase() : ''
  const drawn = kind === 'DRAPE' && operation === 'DRAW'
  const flies = (kind === 'OBJECT' && next.flies === true) || (kind === 'DRAPE' && operation === 'FLY')
  const states = statesOf(next)
  if (!drawn && 'open' in states) next = withState(next, 'open', null)
  if (!flies && 'trimM' in statesOf(next)) next = withState(next, 'trimM', null)
  return next
}

/** The pose and size fields left empty, which a save must not send: every one is required. */
export function emptyNumbers(kind: StageElementDto['kind'], draft: Draft): Array<keyof Draft> {
  const keys: Array<keyof Draft> = ['positionX', 'positionY', 'positionZ', 'yawDeg']
  if (kind !== 'SEATING') keys.push('widthM', 'depthM', 'heightM')
  return keys.filter((key) => draft[key] == null)
}

/**
 * A partial update: the fields that moved, and `params` whole where anything in it did. An empty
 * number is never written — as 0 it would move the element to the origin, or flatten it; the form
 * refuses to save one ([emptyNumbers]).
 */
export function elementUpdate(element: StageElementDto, draft: Draft): UpdateStageElementRequest {
  const body: UpdateStageElementRequest = {}
  const name = draft.name.trim()
  if (name !== element.name) body.name = name
  if (draft.layer !== element.layer) body.layer = draft.layer
  const numbers = ['positionX', 'positionY', 'positionZ', 'yawDeg', 'widthM', 'depthM', 'heightM'] as const
  for (const key of numbers) {
    const value = draft[key]
    if (value != null && value !== element[key]) body[key] = value
  }
  const colour = draft.finishColour.trim() || null
  if (colour !== (element.finishColour ?? null)) body.finishColour = colour
  const pattern = draft.finishPattern || null
  if (pattern !== (element.finishPattern ?? null)) body.finishPattern = pattern
  if (draft.emissive !== element.emissive) body.emissive = draft.emissive
  if (draft.hidden !== element.hidden) body.hidden = draft.hidden
  if (JSON.stringify(sorted(draft.params)) !== JSON.stringify(sorted(element.params ?? {}))) body.params = draft.params
  return body
}

function sorted(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sorted)
  if (value != null && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Params)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([k, v]) => [k, sorted(v)]),
    )
  }
  return value
}


/**
 * Where a new aisle goes in a row of [seatsPerRow]: the middle, else the free gap nearest it, or
 * null when every gap between two seats already has one (an aisle after the last seat is refused).
 */
export function nextAisleSeat(seatsPerRow: number | null, aisles: readonly Params[]): number | null {
  const seats = seatsPerRow ?? 0
  const taken = new Set(aisles.map((a) => a.afterSeat))
  const middle = Math.floor(seats / 2)
  for (let d = 0; d < seats; d++) {
    for (const after of [middle - d, middle + d]) {
      if (after >= 1 && after < seats && !taken.has(after)) return after
    }
  }
  return null
}
