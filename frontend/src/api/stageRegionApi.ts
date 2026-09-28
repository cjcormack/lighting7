import { InternalApiConnection } from './internalApi'
import { Subscription } from './subscription'
import { createChangeSignalApi } from './wsSubscriptionFactory'

export interface StageRegionDto {
  id: number;
  uuid: string;
  name: string;
  centerX: number | null;
  centerY: number | null;
  centerZ: number | null;
  widthM: number | null;
  depthM: number | null;
  heightM: number | null;
  yawDeg: number | null;
  sortOrder: number;
}

export interface CreateStageRegionRequest {
  name: string;
  centerX?: number | null;
  centerY?: number | null;
  centerZ?: number | null;
  widthM?: number | null;
  depthM?: number | null;
  heightM?: number | null;
  yawDeg?: number | null;
}

export interface UpdateStageRegionRequest {
  name?: string;
  centerX?: number | null;
  centerY?: number | null;
  centerZ?: number | null;
  widthM?: number | null;
  depthM?: number | null;
  heightM?: number | null;
  yawDeg?: number | null;
  sortOrder?: number;
}

export interface StageRegionApi {
  subscribe(fn: () => void): Subscription;
}

export function createStageRegionApi(conn: InternalApiConnection): StageRegionApi {
  return createChangeSignalApi(conn, 'stageRegionListChanged')
}
