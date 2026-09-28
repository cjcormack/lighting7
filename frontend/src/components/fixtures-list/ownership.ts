import { computeCombinedCss } from '../../lib/colourMath'
import { resolveSettingOption } from '../../hooks/usePropertyValues'
import type { CellResolution } from './columns'
import type { CellState } from './scopedCellValue'
import type { CellValue } from './useRowValues'
import type {
  CellLayer,
  CellOwnership,
  CellOwnershipSource,
  StagedValue,
} from './useRowOwnership'

/**
 * Ownership styling for a programmer-sheet cell.
 *
 * Deliberately ring + text tint only, layered *around* the existing cell renderers rather
 * than inside them: the four cell editors already encode value shape (fill bars, swatches,
 * crosshairs), and re-tinting their internals would fight that. What the operator needs
 * here is "who owns this", not a second value language.
 *
 * The vocabulary follows the console convention the redesign is built on:
 * - **parked** is loud, because it is the one state where the rig ignores every edit;
 * - **programmer** is the accent colour — this is yours, and it is what Record will take;
 * - **effect** and **cue** are informational tints for values you are sitting on top of;
 * - **baseline** is dimmed, signalling "nothing asserts this".
 */
export function ownershipCellClass(ownership?: CellOwnership): string {
  if (!ownership) return ''
  // A destructive ring used to override the ownership colour when a cell's `ref:` no longer
  // resolved — the cell was showing the last literal the palette gave it, indistinguishable from a
  // healthy one until something said so. The `ref:` grammar retired in session 4; a layer naming a
  // deleted Look simply contributes nothing, so there is no stale-but-plausible value to warn about.
  const mixed = ownership.isUniform ? '' : ' border-dashed'
  switch (ownership.source) {
    case 'parked':
      return 'rounded-sm ring-1 ring-inset ring-amber-500 bg-amber-500/15' + mixed
    case 'programmer':
      return ownership.touched
        ? 'rounded-sm ring-1 ring-inset ring-primary bg-primary/10' + mixed
        : 'rounded-sm ring-1 ring-inset ring-primary/40' + mixed
    case 'effect':
      return 'rounded-sm ring-1 ring-inset ring-violet-500/50' + mixed
    case 'cue':
      return 'rounded-sm ring-1 ring-inset ring-sky-500/40' + mixed
    case 'baseline':
      return 'opacity-55'
  }
}

/**
 * Cell styling for **layer scope**, where the ownership vocabulary above does not apply.
 *
 * A sibling function rather than a branch inside `ownershipCellClass`, and rather than a fork of
 * `useRowOwnership`: what changes between scopes is only how a cell is *painted*, so that is the
 * one place the fork belongs. The subscription machinery stays single-implementation.
 *
 * Three states, and none of them is about the rig — they are about this layer:
 * - **inert**: the column's family sits outside the layer's `propertyMask`, so the layer would
 *   never write it whatever the Look holds;
 * - **untargeted**: the fixture sits outside the layer's `targets`. The value still shows when the
 *   Look has one, because "this Look has a value for it, this layer filters it out" is the useful
 *   reading — but the cell is not editable, and widening the targets is an explicit affordance on
 *   the row rather than a side effect of an edit;
 * - **set here**: the accent ring, the same signal `programmer` carries in Output — this value is
 *   the one you are editing.
 *
 * An unset, in-mask, targeted cell gets nothing: the em-dash carries it on its own.
 */
export function layerCellClass(state: CellState | undefined): string {
  if (!state) return ''
  if (state.tone === 'inert') return 'opacity-40'
  if (state.tone === 'untargeted') return 'rounded-sm border border-dashed border-border opacity-55'
  if (state.value) return 'rounded-sm ring-1 ring-inset ring-primary/70 bg-primary/10'
  return ''
}

/**
 * The one name for each ownership source.
 *
 * `ownershipTitle` below and the on-screen legend (`OwnershipLegend`) both read this table, so a
 * colour can never end up labelled two different ways in the hover text and the key beneath the
 * grid. Bare nouns: the hover appends context (the group, the layer, "mixed across this row") and
 * the legend glosses them for an operator meeting the colours for the first time.
 */
export const OWNERSHIP_LABELS: Record<CellOwnershipSource, string> = {
  parked: 'Parked — the rig ignores every layer here',
  programmer: 'Programmer',
  effect: 'Effect',
  cue: 'Cue',
  baseline: 'Baseline — nothing asserts this',
}

/** Hover text naming the owner, so the colours are learnable rather than decorative. */
export function ownershipTitle(ownership?: CellOwnership): string | undefined {
  if (!ownership) return undefined
  const base =
    ownership.source === 'programmer' && ownership.owners.length > 0
      ? `${OWNERSHIP_LABELS.programmer} (${ownership.owners.join(', ')})`
      : OWNERSHIP_LABELS[ownership.source]
  const parts = [base]
  if (ownership.sourceGroup) parts.push(`via group ${ownership.sourceGroup}`)
  if (!ownership.isUniform) parts.push('mixed across this row')
  const layer = ownership.layer
  if (layer) parts.push(describeCellLayer(layer))
  return parts.join(' · ')
}

/**
 * One clause naming the Look layer that won a cell.
 *
 * This is the sentence `source` cannot say. "Cue" answers *which layer of the engine*; an operator
 * asking why a fixture is this colour wants the name of the look they built it from, and the cook
 * step knows it. It used to sit beside a `describePaletteRef` clause, worded to match, so a cell
 * could report both a value-level reference and the layer that won it; the reference half retired
 * with the `ref:` grammar in session 4.
 */
export function describeCellLayer(layer: CellLayer): string {
  if (layer.mixed) {
    return layer.name
      ? `partly from layer “${layer.name}” across this row`
      : 'from more than one look layer across this row'
  }
  return layer.name ? `from layer “${layer.name}”` : 'from a look layer'
}

/**
 * Substitute the programmer's staged value while blind is engaged.
 *
 * Blind gates the programmer out of the merge, so the live DMX the sheet normally shows is
 * whatever the cues and effects underneath are painting — not what the operator is building.
 * Without this, blind busking in the sheet would be invisible, which is the whole point of
 * the gesture.
 *
 * The switch is exhaustive over the four value kinds, and stays that way: there was never a fifth
 * `kind: 'ref'`, because a reference was decoration on a value rather than a value — it resolved to
 * one of the same four shapes and `useRowOwnership` staged the resolved literal. Moot since the
 * `ref:` grammar retired, but the reasoning is why the switch is shaped as it is.
 *
 * Only shapes that match the cell's own kind are substituted; a mismatch (which would mean
 * the programmer holds something the column doesn't render) falls through to the live value.
 * Derived fields — the combined CSS swatch, the normalised pad axes, the resolved wheel
 * option — are recomputed here rather than carried over, so the staged rendering is a real
 * preview and not the old value wearing new numbers.
 */
export function applyStagedValue(
  value: CellValue,
  staged: StagedValue | undefined,
  resolutions: readonly NonNullable<CellResolution>[],
): CellValue {
  if (!staged) return value
  const first = resolutions[0]
  switch (value.kind) {
    case 'slider':
      return staged.kind === 'level'
        ? { kind: 'slider', min: staged.value, max: staged.value, isUniform: true }
        : value
    case 'setting': {
      if (staged.kind !== 'level') return value
      const options =
        first && (first.kind === 'setting' || first.kind === 'colour-setting')
          ? first.property.options
          : []
      return {
        kind: 'setting',
        isUniform: true,
        level: staged.value,
        option: resolveSettingOption(options, staged.value),
      }
    }
    case 'colour': {
      if (staged.kind !== 'colour') return value
      // Keep undefined for components the fixture has no channel for, so the picker still
      // hides the W/A/UV sliders it would hide for the live value.
      const w = value.w !== undefined ? staged.w : undefined
      const a = value.a !== undefined ? staged.a : undefined
      const uv = value.uv !== undefined ? staged.uv : undefined
      return {
        kind: 'colour',
        isUniform: true,
        r: staged.r,
        g: staged.g,
        b: staged.b,
        w,
        a,
        uv,
        combinedCss: computeCombinedCss(staged.r, staged.g, staged.b, w, a, uv),
      }
    }
    case 'position': {
      if (staged.kind !== 'position' || !first || first.kind !== 'position') return value
      const panRange = first.panMax - first.panMin
      const tiltRange = first.tiltMax - first.tiltMin
      return {
        kind: 'position',
        isUniform: true,
        pan: staged.pan,
        tilt: staged.tilt,
        panNormalized: panRange > 0 ? (staged.pan - first.panMin) / panRange : 0.5,
        tiltNormalized: tiltRange > 0 ? (staged.tilt - first.tiltMin) / tiltRange : 0.5,
      }
    }
  }
}
