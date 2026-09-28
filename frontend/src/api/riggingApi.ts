import { InternalApiConnection } from './internalApi'
import { Subscription } from './subscription'
import { createChangeSignalApi } from './wsSubscriptionFactory'

export interface RiggingDto {
  id: number;
  uuid: string;
  name: string;
  kind: string | null;
  positionX: number | null;
  positionY: number | null;
  positionZ: number | null;
  yawDeg: number | null;
  pitchDeg: number | null;
  rollDeg: number | null;
  lengthM: number | null;
  sortOrder: number;
}

export interface CreateRiggingRequest {
  name: string;
  kind?: string | null;
  positionX?: number | null;
  positionY?: number | null;
  positionZ?: number | null;
  yawDeg?: number | null;
  pitchDeg?: number | null;
  rollDeg?: number | null;
  lengthM?: number | null;
}

export interface UpdateRiggingRequest {
  name?: string;
  kind?: string | null;
  positionX?: number | null;
  positionY?: number | null;
  positionZ?: number | null;
  yawDeg?: number | null;
  pitchDeg?: number | null;
  rollDeg?: number | null;
  lengthM?: number | null;
  sortOrder?: number;
}

export interface RiggingApi {
  subscribe(fn: () => void): Subscription;
}

export function createRiggingApi(conn: InternalApiConnection): RiggingApi {
  return createChangeSignalApi(conn, 'riggingListChanged')
}
