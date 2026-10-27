import type { FxDraft } from './fxEditorModel'

/**
 * **Patterns** (FX editor plan D11; fx-settings plan D10): nine named orderings of the heads, each the
 * four fields that make it — Order, Wings, Segments and Parts. A tap writes all four in one
 * `updateFx`; a pattern is lit only while every field matches it, else *Custom*. How far apart the
 * heads start is *Each head later by*'s, so a pattern leaves Spread and its unit alone. Nothing is
 * stored: a pattern is a name for four fields the FX already has.
 */
export type ShapeFields = Pick<FxDraft, 'order' | 'wings' | 'segments' | 'parts'>

/** What *Same spread as…* copies: a pattern's fields and Spread with its unit. */
export type SpreadFields = Pick<FxDraft, 'order' | 'spread' | 'spreadUnit' | 'wings' | 'segments' | 'parts'>

export interface FxShape {
  name: string
  fields: ShapeFields
}

const plain = (order: string, over: Partial<ShapeFields> = {}): ShapeFields => ({
  order,
  wings: 1,
  segments: 1,
  parts: 0,
  ...over,
})

export const FX_SHAPES: readonly FxShape[] = [
  { name: 'Unison', fields: plain('UNIFIED') },
  { name: 'Chase', fields: plain('NORMAL') },
  { name: 'Back', fields: plain('REVERSE') },
  { name: 'Centre out', fields: plain('CENTRE_OUT') },
  { name: 'Centre in', fields: plain('CENTRE_IN') },
  { name: 'Pairs', fields: plain('NORMAL', { segments: 2 }) },
  { name: 'Odd/even', fields: plain('NORMAL', { parts: 2 }) },
  { name: 'Mirror', fields: plain('NORMAL', { wings: 2 }) },
  { name: 'Random', fields: plain('RANDOM') },
]

export function matchesShape(draft: ShapeFields, shape: FxShape): boolean {
  const f = shape.fields
  return (
    draft.order === f.order &&
    draft.wings === f.wings &&
    draft.segments === f.segments &&
    draft.parts === f.parts
  )
}

/** The pattern every field matches, or null — *Custom*. */
export function shapeOf(draft: ShapeFields): FxShape | null {
  return FX_SHAPES.find((shape) => matchesShape(draft, shape)) ?? null
}

/** The fields of [from], for *Same spread as…* (D13): copied once, linking nothing. */
export function spreadFieldsOf(from: SpreadFields): SpreadFields {
  return {
    order: from.order,
    spread: from.spread,
    spreadUnit: from.spreadUnit,
    wings: from.wings,
    segments: from.segments,
    parts: from.parts,
  }
}
