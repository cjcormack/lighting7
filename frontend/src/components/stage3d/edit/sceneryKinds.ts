import type {
  CreateStageElementRequest,
  StageElementDto,
  StageElementKind,
  StageElementLayer,
} from '../../../api/stageElementApi'
import type { PlacementPoint } from '../../stage/stageEditing'

/**
 * `+ Scenery`'s menu (`Edit.dc.html` §1, stage-view plan session 5): the seven element kinds, plus
 * tabs (a drawn drape) and a flown piece (a flying object), each placed like a region — armed, then
 * a click on the stage — with sizes a person would start from. Every field is the element's own and
 * goes through the desk's `validateStageElement` on the way in, the check `set_scene` makes.
 */
export type SceneryPreset =
  | 'room'
  | 'proscenium'
  | 'flat'
  | 'drape'
  | 'tabs'
  | 'platform'
  | 'seating'
  | 'object'
  | 'flown'

export interface SceneryPresetInfo {
  id: SceneryPreset
  label: string
  /** The menu's second line. */
  hint: string
  kind: StageElementKind
  layer: StageElementLayer
  /** How high the click plane stands where a click cannot say (plan, the orbit camera). */
  placementZ: number
}

/** Where a new flown piece hangs when the click cannot say: above a small stage's lanterns' reach. */
const FLOWN_DEFAULT_Z = 4

export const SCENERY_PRESETS: readonly SceneryPresetInfo[] = [
  { id: 'room', label: 'Room', hint: 'hall, stage house', kind: 'ROOM', layer: 'VENUE', placementZ: 0 },
  { id: 'proscenium', label: 'Proscenium', hint: 'wall with an arch', kind: 'PROSCENIUM', layer: 'VENUE', placementZ: 0 },
  { id: 'flat', label: 'Flat', hint: 'doors, windows', kind: 'FLAT', layer: 'SET', placementZ: 0 },
  { id: 'drape', label: 'Drape', hint: 'legs, borders, cyc', kind: 'DRAPE', layer: 'SET', placementZ: 0 },
  { id: 'tabs', label: 'Tabs', hint: 'draw or fly', kind: 'DRAPE', layer: 'VENUE', placementZ: 0 },
  { id: 'platform', label: 'Platform', hint: 'deck, rostrum, balcony', kind: 'PLATFORM', layer: 'SET', placementZ: 0 },
  { id: 'seating', label: 'Seating', hint: 'rows → seats', kind: 'SEATING', layer: 'VENUE', placementZ: 0 },
  { id: 'object', label: 'Object', hint: 'furniture, props', kind: 'OBJECT', layer: 'SET', placementZ: 0 },
  { id: 'flown', label: 'Flown piece', hint: 'trims in and out', kind: 'OBJECT', layer: 'SET', placementZ: FLOWN_DEFAULT_Z },
]

export function sceneryPreset(id: SceneryPreset): SceneryPresetInfo {
  return SCENERY_PRESETS.find((p) => p.id === id) ?? SCENERY_PRESETS[0]
}

/** The noun the stage's hint and a new element's name use: `flat`, `flown piece`. */
export function sceneryNoun(id: SceneryPreset): string {
  return sceneryPreset(id).label.toLowerCase()
}

/**
 * The create for [preset] landing at [at], named [name]. A platform's Z is its top, so it stands on
 * the click with its deck [heightM] up, as a new region does; every other kind stands its base on
 * the click.
 */
export function sceneryRequest(preset: SceneryPreset, at: PlacementPoint, name: string): CreateStageElementRequest {
  const info = sceneryPreset(preset)
  const base = { name, kind: info.kind, layer: info.layer, positionX: at.x, positionY: at.y, positionZ: at.z, yawDeg: 0 }
  switch (preset) {
    case 'room':
      return { ...base, widthM: 16, depthM: 24, heightM: 8, params: {} }
    case 'proscenium':
      return { ...base, widthM: 10, depthM: 0.5, heightM: 6, params: { openingWidthM: 8, openingHeightM: 5 } }
    case 'flat':
      return { ...base, widthM: 2.4, depthM: 0.1, heightM: 2.4, params: {} }
    case 'drape':
      return { ...base, widthM: 1.5, depthM: 0.3, heightM: 6, params: { role: 'LEG' } }
    case 'tabs':
      return { ...base, widthM: 8, depthM: 0.3, heightM: 6, params: { role: 'TABS', operation: 'DRAW', states: { open: 1 } } }
    case 'platform':
      return { ...base, positionZ: at.z + 0.4, widthM: 2, depthM: 2, heightM: 0.4, params: {} }
    case 'seating':
      // A seating's size is its rows and seats; the desk refuses one given a box.
      return { ...base, widthM: 0, depthM: 0, heightM: 0, params: { rows: 6, seatsPerRow: 12, rowPitchM: 0.9, seatPitchM: 0.5 } }
    case 'object':
      return { ...base, widthM: 0.8, depthM: 0.8, heightM: 0.8, params: { shape: 'BOX' } }
    case 'flown':
      return { ...base, widthM: 3, depthM: 0.1, heightM: 1.5, params: { shape: 'BOX', flies: true } }
  }
}

const KIND_LABELS: Record<StageElementKind, string> = {
  ROOM: 'Room',
  PROSCENIUM: 'Proscenium',
  FLAT: 'Flat',
  DRAPE: 'Drape',
  PLATFORM: 'Platform',
  SEATING: 'Seating',
  OBJECT: 'Object',
}

export function kindLabel(kind: StageElementKind): string {
  return KIND_LABELS[kind] ?? kind
}

/** What an element is, as the form's Kind line says it: `Drape · tabs · draw`, `Object · flown`. */
export function elementKindLabel(element: Pick<StageElementDto, 'kind' | 'params'>): string {
  const parts = [kindLabel(element.kind)]
  if (element.kind === 'DRAPE') {
    const role = typeof element.params.role === 'string' ? element.params.role.toLowerCase() : null
    const operation = typeof element.params.operation === 'string' ? element.params.operation.toLowerCase() : null
    if (role) parts.push(role)
    if (operation && operation !== 'dead') parts.push(operation)
  } else if (element.kind === 'OBJECT') {
    if (element.params.flies === true) parts.push('flown')
  }
  return parts.join(' · ')
}
