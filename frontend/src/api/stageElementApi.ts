import { InternalApiConnection } from './internalApi'
import { Subscription } from './subscription'
import { createChangeSignalApi } from './wsSubscriptionFactory'

/**
 * The scene document's elements (stage-view plan session 2, D2): named pieces of the venue and the
 * set, each with a pose, a size, a finish and its kind's `params`. Coordinates are lighting metres
 * (FOH-relative, Z-up). `positionZ` is the element's base, except a `PLATFORM`'s, which is its top
 * surface — as a region's `centerZ` is.
 *
 * Enumerations are the backend's upper-case names. `params` is left loosely typed here: session 2
 * reads only seating's (`lib/stageSeats.ts`) and draws every element as a box; session 3's builders
 * read the rest.
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

export interface StageElementApi {
  subscribe(fn: () => void): Subscription
}

export function createStageElementApi(conn: InternalApiConnection): StageElementApi {
  return createChangeSignalApi(conn, 'stageElementListChanged')
}
