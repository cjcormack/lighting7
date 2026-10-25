import {
  findColourSource,
  findDimmerAttribute,
  findFocusAttribute,
  findGoboAttributes,
  findIrisAttribute,
  findPanAttribute,
  findPrismAttribute,
  findTiltAttribute,
  findWheel,
  findWhiteMixAttribute,
  findZoomAttribute,
} from '../../store/fixtures'
import { familyForCategory } from '../../lib/attributeFamily'
import type { AttributeFamily } from '../../lib/attributeFamily'
import type {
  ChannelRef,
  ColourAttributeDescriptor,
  PositionAttributeDescriptor,
  AttributeCategory,
  AttributeDescriptor,
  SettingAttributeDescriptor,
  SliderAttributeDescriptor,
  WhiteMixAttributeDescriptor,
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
 * scope needs to grey out the columns outside a layer's `attributeMask`. Per-cell classification
 * still goes through the resolution's own descriptor, because one column can carry different
 * property kinds across fixture types.
 *
 * `attributeFamily.ts` records that a `FAMILY_COLUMNS` constant here was deliberately **deleted**
 * once, so this deserves a word on why it is back and different. That one mapped a family to its
 * columns — the wrong direction, and a second place to state which columns exist. This maps a
 * column to the category it already is, and `columns.test.ts` pins it against `familyForCategory`
 * so the two cannot drift.
 */
export const COLUMN_CATEGORY: Record<ColumnKey, AttributeCategory> = {
  dimmer: 'dimmer',
  colour: 'colour',
  // The synthetic pan/tilt pair; `familyForCategory` answers it ahead of its own lookup.
  position: 'position' as AttributeCategory,
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
 * differently — `PaletteStrip` filters its chips by it, and the bar badges it beside the counts,
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
 * Position additionally keeps descriptors. Cells don't need them, but the programmer does: it
 * is keyed by (target, property name), not by channel, and the two position shapes must be
 * written differently — a real `position` descriptor takes one `programmer.setPosition`, while
 * a pan/tilt slider pair takes one `programmer.set` per axis (lifting a single axis into a
 * `position` entry would freeze the other). `attribute` alone says which shape it is; the axis
 * sliders ride on both, for their degree annotations. See [resolutionAttributeNames].
 */
export type CellResolution =
  | { kind: 'slider'; attribute: SliderAttributeDescriptor }
  | { kind: 'colour'; attribute: ColourAttributeDescriptor }
  /** A colour *wheel* — a setting whose options carry colour previews. Takes
   *  option levels, not RGB. */
  | { kind: 'colour-setting'; attribute: SettingAttributeDescriptor }
  /** A warm/cold white pair (venue-visit plan D8). Reads and takes a colour like an RGB head —
   *  the desk lands it as the nearest mix — and its editor is a warmth control. */
  | { kind: 'white-mix'; attribute: WhiteMixAttributeDescriptor }
  | {
      kind: 'position'
      pan: ChannelRef
      tilt: ChannelRef
      panMin: number
      panMax: number
      tiltMin: number
      tiltMax: number
      /** Set when the fixture exposes a real `position` descriptor; writes then go to it. */
      attribute?: PositionAttributeDescriptor
      /**
       * The axis sliders, whenever the fixture declares them — what the position was paired from
       * when there is no descriptor, and where the degree annotations live either way.
       */
      panAttribute?: SliderAttributeDescriptor
      tiltAttribute?: SliderAttributeDescriptor
    }
  | { kind: 'setting'; attribute: SettingAttributeDescriptor }
  | null

function sameChannel(a: ChannelRef, b: ChannelRef): boolean {
  return a.universe === b.universe && a.channelNo === b.channelNo
}

/** A wheel-like channel (slider or setting) as a cell resolution. */
function findWheelLike(
  attributes: AttributeDescriptor[],
  category: AttributeCategory,
): CellResolution {
  const prop = findWheel(attributes, category)
  if (!prop) return null
  return prop.type === 'slider' ? { kind: 'slider', attribute: prop } : { kind: 'setting', attribute: prop }
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
export function resolveCell(attributes: AttributeDescriptor[], col: ColumnKey): CellResolution {
  switch (col) {
    case 'dimmer': {
      const prop = findDimmerAttribute(attributes)
      return prop ? { kind: 'slider', attribute: prop } : null
    }
    case 'colour': {
      const source = findColourSource(attributes)
      if (!source) {
        const whiteMix = findWhiteMixAttribute(attributes)
        return whiteMix ? { kind: 'white-mix', attribute: whiteMix } : null
      }
      return source.type === 'colour'
        ? { kind: 'colour', attribute: source.attribute }
        : { kind: 'colour-setting', attribute: source.attribute }
    }
    case 'position': {
      // Prefer a real position descriptor; otherwise pair the pan/tilt sliders.
      const posProp = attributes.find((p) => p.type === 'position')
      if (posProp) {
        // The descriptor carries no degrees, so the axes ride along for their annotations — only
        // when they are the descriptor's own channels.
        const panAxis = findPanAttribute(attributes)
        const tiltAxis = findTiltAttribute(attributes)
        return {
          kind: 'position',
          pan: posProp.panChannel,
          tilt: posProp.tiltChannel,
          panMin: posProp.panMin,
          panMax: posProp.panMax,
          tiltMin: posProp.tiltMin,
          tiltMax: posProp.tiltMax,
          attribute: posProp,
          ...(panAxis && sameChannel(panAxis.channel, posProp.panChannel) ? { panAttribute: panAxis } : {}),
          ...(tiltAxis && sameChannel(tiltAxis.channel, posProp.tiltChannel) ? { tiltAttribute: tiltAxis } : {}),
        }
      }
      const pan = findPanAttribute(attributes)
      const tilt = findTiltAttribute(attributes)
      if (!pan || !tilt) return null
      return {
        kind: 'position',
        pan: pan.channel,
        tilt: tilt.channel,
        panMin: pan.min,
        panMax: pan.max,
        tiltMin: tilt.min,
        tiltMax: tilt.max,
        panAttribute: pan,
        tiltAttribute: tilt,
      }
    }
    case 'gobo': {
      // The first wheel in DMX channel order (`findGoboAttributes`, fixture-optics session 4) — the
      // Robe ColorSpot 575's static wheel, where reflection order used to give its rotating one.
      const wheel = findGoboAttributes(attributes)[0]
      if (!wheel) return null
      return wheel.type === 'slider'
        ? { kind: 'slider', attribute: wheel }
        : { kind: 'setting', attribute: wheel }
    }
    case 'zoom': {
      // A slider, or a stepped zoom's setting (the Robe ColorSpot 575's three angles).
      const prop = findZoomAttribute(attributes)
      if (!prop) return null
      return prop.type === 'slider' ? { kind: 'slider', attribute: prop } : { kind: 'setting', attribute: prop }
    }
    case 'focus': {
      const prop = findFocusAttribute(attributes)
      return prop ? { kind: 'slider', attribute: prop } : null
    }
    case 'iris': {
      const prop = findIrisAttribute(attributes)
      return prop ? { kind: 'slider', attribute: prop } : null
    }
    case 'prism': {
      const wheel = findPrismAttribute(attributes)
      if (!wheel) return null
      return wheel.type === 'slider'
        ? { kind: 'slider', attribute: wheel }
        : { kind: 'setting', attribute: wheel }
    }
    case 'strobe':
      return findWheelLike(attributes, 'strobe')
    case 'speed':
      return findWheelLike(attributes, 'speed')
  }
}

/** Every ChannelRef a resolution reads/writes — the row's subscription list. */
export function resolutionChannels(res: CellResolution): ChannelRef[] {
  if (!res) return []
  switch (res.kind) {
    case 'slider':
      return [res.attribute.channel]
    case 'colour': {
      const p = res.attribute
      const refs = [p.redChannel, p.greenChannel, p.blueChannel]
      if (p.whiteChannel) refs.push(p.whiteChannel)
      if (p.amberChannel) refs.push(p.amberChannel)
      if (p.uvChannel) refs.push(p.uvChannel)
      return refs
    }
    case 'white-mix':
      return [res.attribute.warmChannel, res.attribute.coldChannel]
    case 'colour-setting':
    case 'setting':
      return [res.attribute.channel]
    case 'position':
      return [res.pan, res.tilt]
  }
}

/**
 * Every backend property name a resolution covers — the programmer/provenance keys for the
 * cell, paired with `WriteTarget.key` to form a `(targetKey, attribute)` lookup.
 *
 * Usually one name. Position is the exception: a fixture with separate pan/tilt sliders has
 * two independent properties behind a single cell, and both must be consulted (for ownership
 * colouring) and written (for edits).
 *
 * Names come straight off the descriptors, which carry the backend's own vocabulary —
 * `dimmer`, `rgbColour`, `position`, `pan`, `tilt`.
 */
export function resolutionAttributeNames(res: CellResolution): string[] {
  if (!res) return []
  switch (res.kind) {
    case 'slider':
    case 'colour':
    case 'white-mix':
    case 'colour-setting':
    case 'setting':
      return [res.attribute.name]
    case 'position': {
      if (res.attribute) return [res.attribute.name]
      const names: string[] = []
      if (res.panAttribute) names.push(res.panAttribute.name)
      if (res.tiltAttribute) names.push(res.tiltAttribute.name)
      return names
    }
  }
}
