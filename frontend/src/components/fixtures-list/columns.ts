import {
  findColourSource,
  findDimmerProperty,
  findFocusProperty,
  findGoboProperties,
  findIrisProperty,
  findPanProperty,
  findPrismProperty,
  findTiltProperty,
  findWheel,
  findZoomProperty,
} from '../../store/fixtures'
import { familyForCategory } from '../../lib/attributeFamily'
import type { AttributeFamily } from '../../lib/attributeFamily'
import type {
  ChannelRef,
  ColourPropertyDescriptor,
  PositionPropertyDescriptor,
  PropertyCategory,
  PropertyDescriptor,
  SettingPropertyDescriptor,
  SliderPropertyDescriptor,
} from '../../store/fixtures'

/**
 * The list view's column set. Columns are keyed by property *category* rather
 * than property name — names are fixture-type-specific ("Dimmer" vs "Master
 * dimmer"), categories are the only cross-type vocabulary.
 */
export type ColumnKey =
  | 'dimmer'
  | 'colour'
  | 'position'
  | 'gobo'
  | 'zoom'
  | 'focus'
  | 'iris'
  | 'prism'
  | 'strobe'
  | 'speed'

export interface ColumnDef {
  key: ColumnKey
  label: string
  defaultVisible: boolean
}

export const COLUMN_DEFS: ColumnDef[] = [
  { key: 'dimmer', label: 'Dimmer', defaultVisible: true },
  { key: 'colour', label: 'Colour', defaultVisible: true },
  { key: 'position', label: 'Position', defaultVisible: true },
  { key: 'gobo', label: 'Gobo', defaultVisible: true },
  { key: 'zoom', label: 'Zoom', defaultVisible: true },
  { key: 'strobe', label: 'Strobe', defaultVisible: true },
  { key: 'focus', label: 'Focus', defaultVisible: false },
  { key: 'iris', label: 'Iris', defaultVisible: false },
  { key: 'prism', label: 'Prism', defaultVisible: false },
  { key: 'speed', label: 'Speed', defaultVisible: false },
]

const COLUMN_LABEL_BY_KEY = new Map(COLUMN_DEFS.map((d) => [d.key, d.label]))

/**
 * A column's display name — the header's, and the title a cell editor takes when it draws itself
 * as a bottom sheet (`EditorSurface`). One lookup, so the two cannot disagree.
 */
export function columnLabel(col: ColumnKey): string {
  return COLUMN_LABEL_BY_KEY.get(col) ?? col
}

export const DEFAULT_COLUMN_VISIBILITY: Record<ColumnKey, boolean> = Object.fromEntries(
  COLUMN_DEFS.map((d) => [d.key, d.defaultVisible]),
) as Record<ColumnKey, boolean>

/**
 * The canonical property category behind each column — the cross-type vocabulary the column was
 * built from in the first place (see this file's header).
 *
 * Exists so a column *header* can be classified without a fixture to ask, which is what layer
 * scope needs to grey out the columns outside a layer's `propertyMask`. Per-cell classification
 * still goes through the resolution's own descriptor, because one column can carry different
 * property kinds across fixture types.
 *
 * `attributeFamily.ts` records that a `FAMILY_COLUMNS` constant here was deliberately **deleted**
 * once, so this deserves a word on why it is back and different. That one mapped a family to its
 * columns — the wrong direction, and a second place to state which columns exist. This maps a
 * column to the category it already is, and `columns.test.ts` pins it against `familyForCategory`
 * so the two cannot drift.
 */
export const COLUMN_CATEGORY: Record<ColumnKey, PropertyCategory> = {
  dimmer: 'dimmer',
  colour: 'colour',
  // The synthetic pan/tilt pair; `familyForCategory` answers it ahead of its own lookup.
  position: 'position' as PropertyCategory,
  gobo: 'gobo',
  zoom: 'zoom',
  focus: 'focus',
  iris: 'iris',
  prism: 'prism',
  strobe: 'strobe',
  speed: 'speed',
}

/** The attribute family a column belongs to, for a header with no fixture to classify against. */
export function columnFamily(col: ColumnKey): AttributeFamily {
  return familyForCategory(COLUMN_CATEGORY[col])
}

/**
 * The attribute families a marquee names.
 *
 * The *asked* families — what the operator pointed at — never what the selected heads happen to
 * have: a marquee across the Colour column means colour and nothing else.
 *
 * Shared because two callers a few pixels apart on one line of the selection bar must not answer
 * differently — `TemplateStrip` filters its chips by it, and the bar badges it beside the counts,
 * and a disagreement between them would read as a bug. What is shared is the **rule**, not one
 * evaluation of it: each call site still runs its own pass. Cheap enough (a `Set` over the
 * marquee's cells) that the bar's own `SelectionBar` memoises it once and hands it down rather
 * than the two deriving it independently.
 *
 * It lives here rather than in `cellSelectionModel`, which is where a function of `CellRef[]`
 * would otherwise belong, because that module is pure and node-tested — pulling `COLUMN_CATEGORY`
 * into it drags `store/fixtures` and the WebSocket API in behind it, and its suite fails on
 * `window is not defined`. `columns.ts` already reaches both sides.
 *
 * Empty for an empty selection, which is not the same as "every family" — the callers decide what
 * no question means.
 */
export function cellFamilies(cells: readonly { col: ColumnKey }[]): AttributeFamily[] {
  return [...new Set(cells.map((cell) => columnFamily(cell.col)))]
}

/**
 * How one fixture's descriptors satisfy one column. Position is synthesised to
 * plain channel refs + ranges so cells don't care whether the fixture exposes a
 * `position`-type descriptor or separate pan/tilt sliders.
 *
 * Position additionally keeps the *descriptors* it was built from. Cells don't need them,
 * but the programmer does: it is keyed by (target, property name), not by channel, and the
 * two position shapes must be written differently — a real `position` descriptor takes one
 * `programmer.setPosition`, while a pan/tilt slider pair takes one `programmer.set` per
 * axis (lifting a single axis into a `position` entry would freeze the other). See
 * [resolutionPropertyNames].
 */
export type CellResolution =
  | { kind: 'slider'; property: SliderPropertyDescriptor }
  | { kind: 'colour'; property: ColourPropertyDescriptor }
  /** A colour *wheel* — a setting whose options carry colour previews. Takes
   *  option levels, not RGB. */
  | { kind: 'colour-setting'; property: SettingPropertyDescriptor }
  | {
      kind: 'position'
      pan: ChannelRef
      tilt: ChannelRef
      panMin: number
      panMax: number
      tiltMin: number
      tiltMax: number
      /** Set when the fixture exposes a real `position` descriptor. */
      property?: PositionPropertyDescriptor
      /** Set instead when the position was paired from two axis sliders. */
      panProperty?: SliderPropertyDescriptor
      tiltProperty?: SliderPropertyDescriptor
    }
  | { kind: 'setting'; property: SettingPropertyDescriptor }
  | null

/** A wheel-like channel (slider or setting) as a cell resolution. */
function findWheelLike(
  properties: PropertyDescriptor[],
  category: PropertyCategory,
): CellResolution {
  const prop = findWheel(properties, category)
  if (!prop) return null
  return prop.type === 'slider' ? { kind: 'slider', property: prop } : { kind: 'setting', property: prop }
}

/**
 * Resolve which of a fixture's properties backs the given column, or null when
 * the fixture has nothing in that category (the cell renders empty).
 *
 * Deliberate cuts:
 * - Gobo shows the *first* wheel only. Two-wheel fixtures (Robe ColorSpot 575)
 *   keep wheel 2 in the fixture card view; a second rarely-populated column
 *   isn't worth the width.
 * - Pan/tilt fine channels fold into Position: display and write the coarse
 *   channels only. The fine channels are a sub-step refinement; leaving them
 *   untouched is harmless.
 */
export function resolveCell(properties: PropertyDescriptor[], col: ColumnKey): CellResolution {
  switch (col) {
    case 'dimmer': {
      const prop = findDimmerProperty(properties)
      return prop ? { kind: 'slider', property: prop } : null
    }
    case 'colour': {
      const source = findColourSource(properties)
      if (!source) return null
      return source.type === 'colour'
        ? { kind: 'colour', property: source.property }
        : { kind: 'colour-setting', property: source.property }
    }
    case 'position': {
      // Prefer a real position descriptor; otherwise pair the pan/tilt sliders.
      const posProp = properties.find((p) => p.type === 'position')
      if (posProp) {
        return {
          kind: 'position',
          pan: posProp.panChannel,
          tilt: posProp.tiltChannel,
          panMin: posProp.panMin,
          panMax: posProp.panMax,
          tiltMin: posProp.tiltMin,
          tiltMax: posProp.tiltMax,
          property: posProp,
        }
      }
      const pan = findPanProperty(properties)
      const tilt = findTiltProperty(properties)
      if (!pan || !tilt) return null
      return {
        kind: 'position',
        pan: pan.channel,
        tilt: tilt.channel,
        panMin: pan.min,
        panMax: pan.max,
        tiltMin: tilt.min,
        tiltMax: tilt.max,
        panProperty: pan,
        tiltProperty: tilt,
      }
    }
    case 'gobo': {
      const wheel = findGoboProperties(properties)[0]
      if (!wheel) return null
      return wheel.type === 'slider'
        ? { kind: 'slider', property: wheel }
        : { kind: 'setting', property: wheel }
    }
    case 'zoom': {
      const prop = findZoomProperty(properties)
      return prop ? { kind: 'slider', property: prop } : null
    }
    case 'focus': {
      const prop = findFocusProperty(properties)
      return prop ? { kind: 'slider', property: prop } : null
    }
    case 'iris': {
      const prop = findIrisProperty(properties)
      return prop ? { kind: 'slider', property: prop } : null
    }
    case 'prism': {
      const wheel = findPrismProperty(properties)
      if (!wheel) return null
      return wheel.type === 'slider'
        ? { kind: 'slider', property: wheel }
        : { kind: 'setting', property: wheel }
    }
    case 'strobe':
      return findWheelLike(properties, 'strobe')
    case 'speed':
      return findWheelLike(properties, 'speed')
  }
}

/** Every ChannelRef a resolution reads/writes — the row's subscription list. */
export function resolutionChannels(res: CellResolution): ChannelRef[] {
  if (!res) return []
  switch (res.kind) {
    case 'slider':
      return [res.property.channel]
    case 'colour': {
      const p = res.property
      const refs = [p.redChannel, p.greenChannel, p.blueChannel]
      if (p.whiteChannel) refs.push(p.whiteChannel)
      if (p.amberChannel) refs.push(p.amberChannel)
      if (p.uvChannel) refs.push(p.uvChannel)
      return refs
    }
    case 'colour-setting':
    case 'setting':
      return [res.property.channel]
    case 'position':
      return [res.pan, res.tilt]
  }
}

/**
 * Every backend property name a resolution covers — the programmer/provenance keys for the
 * cell, paired with `WriteTarget.key` to form a `(targetKey, propertyName)` lookup.
 *
 * Usually one name. Position is the exception: a fixture with separate pan/tilt sliders has
 * two independent properties behind a single cell, and both must be consulted (for ownership
 * colouring) and written (for edits).
 *
 * Names come straight off the descriptors, which carry the backend's own vocabulary —
 * `dimmer`, `rgbColour`, `position`, `pan`, `tilt`.
 */
export function resolutionPropertyNames(res: CellResolution): string[] {
  if (!res) return []
  switch (res.kind) {
    case 'slider':
    case 'colour':
    case 'colour-setting':
    case 'setting':
      return [res.property.name]
    case 'position': {
      if (res.property) return [res.property.name]
      const names: string[] = []
      if (res.panProperty) names.push(res.panProperty.name)
      if (res.tiltProperty) names.push(res.tiltProperty.name)
      return names
    }
  }
}
