import { familyForCategory, type AttributeFamily } from '@/lib/attributeFamily'
import { annotatesDegrees } from '@/lib/axisDegrees'
import { findDimmerProperty, type ColourPropertyDescriptor, type PropertyDescriptor, type SettingPropertyDescriptor, type SliderPropertyDescriptor } from '@/store/fixtures'
import { resolveCell, type CellResolution } from '../fixtures-list/columns'

/**
 * The fixture sheet's five row groups (fixture-fx-sheets plan D3): the desk's attribute families —
 * `lib/attributeFamily.ts`'s vocabulary, the one templates, the mask and Spread speak — plus
 * **Controls**, for the channels that are not a look at all (a macro, a speed, a mode, a reset-free
 * `setting`), which the family vocabulary files under Beam as its catch-all.
 */
export type SheetFamily = AttributeFamily | 'CONTROLS'

export const SHEET_FAMILY_ORDER: readonly SheetFamily[] = ['INTENSITY', 'COLOUR', 'POSITION', 'BEAM', 'CONTROLS']

export const SHEET_FAMILY_LABELS: Record<SheetFamily, string> = {
  INTENSITY: 'Intensity',
  COLOUR: 'Colour',
  POSITION: 'Position',
  BEAM: 'Beam',
  CONTROLS: 'Controls',
}

/** Categories a sheet files under Controls rather than the family vocabulary's Beam catch-all. */
const CONTROL_CATEGORIES = new Set(['setting', 'other', 'speed', 'led_macro', 'movement_macro'])

/** The position row's resolution — the grid's own (`resolveCell(…, 'position')`). */
export type PositionResolution = Extract<NonNullable<CellResolution>, { kind: 'position' }>

/**
 * One row of the sheet. [keys] are the property names the row's source mark, its × and Release's
 * count read — the programmer is keyed by `(head, propertyName)`. The first is the one the row
 * writes and asks the stack about.
 */
export type SheetRow =
  | { kind: 'slider'; id: string; family: SheetFamily; label: string; keys: string[]; property: SliderPropertyDescriptor }
  | { kind: 'setting'; id: string; family: SheetFamily; label: string; keys: string[]; property: SettingPropertyDescriptor }
  /** [dimmer] is what its swatch is dimmed by: the set's own dimmer, else the fixture's for a head. */
  | { kind: 'colour'; id: string; family: SheetFamily; label: string; keys: string[]; property: ColourPropertyDescriptor; dimmer?: SliderPropertyDescriptor }
  /**
   * A colour head with no dimmer: one Intensity row reading max(R, G, B) and scaling the colour —
   * `useVirtualDimmer`. Keyed by the colour property, since that is the entry it writes. It used to
   * carry a "Virtual" badge; a row that sets the level is a dimmer to the operator (Main board, 3).
   */
  | { kind: 'virtual-dimmer'; id: string; family: SheetFamily; label: string; keys: string[]; property: ColourPropertyDescriptor }
  /**
   * Pan and tilt as **one** row (D8), written as one `programmer.setPosition` — never the two raw
   * axes. Keyed `position` first: a Circle is keyed `position`, so that is the key whose entry holds
   * it back (session 1 amendment); the axis names follow because an older write may hold them.
   */
  | { kind: 'position'; id: string; family: SheetFamily; label: string; keys: string[]; resolution: PositionResolution; degrees: boolean }

export interface SheetRowGroup {
  family: SheetFamily
  rows: SheetRow[]
}

function familyOf(property: PropertyDescriptor): SheetFamily {
  if (property.type === 'slider' && property.timing != null) return 'CONTROLS'
  if (CONTROL_CATEGORIES.has(property.category)) return 'CONTROLS'
  return familyForCategory(property.category)
}

const AXIS_CATEGORIES = new Set(['pan', 'tilt', 'pan_fine', 'tilt_fine'])

/**
 * A set of properties (a fixture's own, or one head's) as the sheet's rows, grouped by family in
 * D3's order and, within a family, in the desk's descriptor order. Empty families are dropped.
 *
 * Left out: triggers and commands (not controls — the cannon's panel and the Commands menu), the
 * fine half of a 16-bit pair (`fineOf`, `pan_fine` / `tilt_fine` — the coarse row writes the
 * property), and the pan / tilt sliders once they make a Position row.
 */
export function buildSheetRows(
  properties: readonly PropertyDescriptor[] | undefined,
  options: {
    /**
     * A dimmer the set does not carry still drives these heads — a multi-head fixture's all-heads
     * dimmer (`elementGroupProperties`). Then a colour here gets no Intensity row of its own, as
     * `FixtureContent`'s `hasRealDimmer` had it.
     */
    dimmerElsewhere?: boolean
    /** A head's fixture-level dimmer, for its colour swatch when the head has none of its own. */
    fallbackDimmer?: SliderPropertyDescriptor
  } = {},
): SheetRowGroup[] {
  const props = (properties ?? []) as PropertyDescriptor[]
  const rows: SheetRow[] = []

  const position = resolveCell(props, 'position') as PositionResolution | null
  const ownDimmer = findDimmerProperty(props)
  const hasDimmer = options.dimmerElsewhere === true || ownDimmer != null
  let positionPlaced = false

  for (const p of props) {
    if (p.type === 'trigger' || p.type === 'command') continue
    if (p.type === 'slider' && p.fineOf != null) continue
    if (p.type === 'slider' && (p.category === 'pan_fine' || p.category === 'tilt_fine')) continue
    const isAxis = p.type === 'slider' && (p.axis != null || AXIS_CATEGORIES.has(p.category))
    if (position && (p.type === 'position' || isAxis)) {
      if (positionPlaced) continue
      positionPlaced = true
      const axisKeys = [position.panProperty?.name, position.tiltProperty?.name].filter((n): n is string => n != null)
      rows.push({
        kind: 'position',
        id: 'position',
        family: 'POSITION',
        label: 'Position',
        keys: ['position', ...axisKeys.filter((n) => n !== 'position')],
        resolution: position,
        degrees: annotatesDegrees(position.panProperty) && annotatesDegrees(position.tiltProperty),
      })
      continue
    }
    switch (p.type) {
      case 'colour':
        if (!hasDimmer) {
          rows.push({ kind: 'virtual-dimmer', id: `virtual-dimmer:${p.name}`, family: 'INTENSITY', label: 'Dimmer', keys: [p.name], property: p })
        }
        rows.push({
          kind: 'colour',
          id: p.name,
          family: 'COLOUR',
          label: p.displayName,
          keys: [p.name],
          property: p,
          dimmer: ownDimmer ?? options.fallbackDimmer,
        })
        break
      case 'slider':
        rows.push({ kind: 'slider', id: p.name, family: familyOf(p), label: p.displayName, keys: [p.name], property: p })
        break
      case 'setting':
        rows.push({ kind: 'setting', id: p.name, family: familyOf(p), label: p.displayName, keys: [p.name], property: p })
        break
      case 'position':
        // Only reached with no position resolution, which a real descriptor always makes.
        break
    }
  }

  return SHEET_FAMILY_ORDER.map((family) => ({ family, rows: rows.filter((r) => r.family === family) })).filter(
    (g) => g.rows.length > 0,
  )
}

/** Every property name a set of groups reads — what Release's count and the held line walk. */
export function sheetRowKeys(groups: readonly SheetRowGroup[]): string[] {
  const out = new Set<string>()
  for (const g of groups) for (const r of g.rows) for (const k of r.keys) out.add(k)
  return [...out]
}
