import { InternalApiConnection } from './internalApi'
import { Subscription } from './subscription'
import { createChangeSignalApi } from './wsSubscriptionFactory'

/**
 * A saved viewpoint (stage-view plan session 2, D6): portable, because "Row F" is the venue's. Plan,
 * Front and Side are built in and never rows.
 *
 * - `ORBIT` and `EYE` carry an eye and a target in lighting metres; an eye view also its lens.
 * - `SEAT` names a seat of a seating element instead of an eye (`seatElementUuid`, `seatId` such as
 *   `F6`), and may carry a target and a lens. Its eye is the seat's (`lib/stageSeats.ts`), so a
 *   moved seating moves the view; a seating that is gone leaves the view dangling, drawn disabled.
 */
export type StageViewpointKind = 'ORBIT' | 'EYE' | 'SEAT'

export interface StageViewpointDto {
  id: number
  uuid: string
  name: string
  kind: StageViewpointKind
  eyeX: number | null
  eyeY: number | null
  eyeZ: number | null
  targetX: number | null
  targetY: number | null
  targetZ: number | null
  fovDeg: number | null
  seatElementUuid: string | null
  seatId: string | null
  sortOrder: number
}

export interface CreateStageViewpointRequest {
  name: string
  kind: StageViewpointKind
  eyeX?: number | null
  eyeY?: number | null
  eyeZ?: number | null
  targetX?: number | null
  targetY?: number | null
  targetZ?: number | null
  fovDeg?: number | null
  seatElementUuid?: string | null
  seatId?: string | null
}

export type UpdateStageViewpointRequest = Partial<CreateStageViewpointRequest> & { sortOrder?: number }

export interface StageViewpointApi {
  subscribe(fn: () => void): Subscription
}

export function createStageViewpointApi(conn: InternalApiConnection): StageViewpointApi {
  return createChangeSignalApi(conn, 'stageViewpointListChanged')
}
