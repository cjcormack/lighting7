import type { FxDraft } from './fxEditorModel'

/**
 * **Shapes** (FX editor plan D11): nine named spreads, each the five fields that make it — Order,
 * Spread, Wings, Segments and Parts. A tap writes all five in one `updateFx`; a Shape is lit only
 * while every field matches it, else the Spread section says *Custom*. Nothing is stored: a Shape is
 * a name for five fields the FX already has.
 */
export type ShapeFields = Pick<FxDraft, 'order' | 'spread' | 'wings' | 'segments' | 'parts'>

export interface FxShape {
  name: string
  fields: ShapeFields
}

const plain = (order: string, over: Partial<ShapeFields> = {}): ShapeFields => ({
  order,
  spread: 1,
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
    draft.spread === f.spread &&
    draft.wings === f.wings &&
    draft.segments === f.segments &&
    draft.parts === f.parts
  )
}

/** The Shape every field matches, or null — *Custom*. */
export function shapeOf(draft: ShapeFields): FxShape | null {
  return FX_SHAPES.find((shape) => matchesShape(draft, shape)) ?? null
}

/** The five fields of [from], for *Same spread as…* (D13): copied once, linking nothing. */
export function spreadFieldsOf(from: ShapeFields): ShapeFields {
  return {
    order: from.order,
    spread: from.spread,
    wings: from.wings,
    segments: from.segments,
    parts: from.parts,
  }
}
