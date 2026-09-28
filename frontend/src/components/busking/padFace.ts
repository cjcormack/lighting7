import type { BuskPad, BuskPadKind } from '@/api/buskApi'
import type { LookSummary } from '@/api/looksApi'
import type { TemplateSummary } from '@/api/templatesApi'
import { FAMILY_LABELS } from '@/lib/attributeFamily'
import { templateRowsSwatch } from '@/lib/templateIntent'
import { effectSpeedLabel } from '@/components/fx/fxConstants'
import { cn } from '@/lib/utils'
import type { EffectPresence } from './buskingTypes'

/**
 * Everything a pad draws, derived from the record the pad embeds.
 *
 * It is a plain object rather than a component's own reading because the **drag overlay needs the
 * same face without any hooks**: the ghost lives for a second, is rendered outside the pad's place
 * in the tree, and must not subscribe to anything. So `detail` here is always a finished string —
 * which is also why an effect template's live speed-master label is *not* in it. The pad itself
 * renders `EffectPadDetail` over the top; the ghost shows the frozen line and is none the worse.
 */
export interface PadFace {
  kind: BuskPadKind
  name: string
  /** The second line. Static — see the note above about the effect case. */
  detail: string
  /** A colour, for a generic single-row colour template. Null for everything else. */
  swatch: string | null
  isEffect: boolean
  /** Tooltip only: the mock has room for a name and one line, and a third pushed the pad taller. */
  notes: string | null
  cueNumber: string | null
  stackName: string | null
}

/**
 * A generic colour template's colour.
 *
 * Keeps `isOfferable`'s per-fixture exclusion, for its reason: such a template holds one colour
 * *per head*, so there is no single one to show. The row-count exclusion is gone with the rule that
 * made it necessary — [templateRowsSwatch] reads the *colour* row rather than `rows[0]`, so a
 * template holding a hex plus an explicit amber draws its hex instead of whichever row sorted first.
 * An effect template holds no rows at all and falls out of that function as null.
 */
export function templateSwatch(template: TemplateSummary): string | null {
  if (!template.isGeneric) return null
  return templateRowsSwatch(template.rows)
}

/** `2 effects · 3 values`, or `empty`. */
export function describeLookContents(look: LookSummary): string {
  const parts: string[] = []
  if (look.effectCount > 0) {
    parts.push(`${look.effectCount} ${look.effectCount === 1 ? 'effect' : 'effects'}`)
  }
  if (look.rowCount > 0) {
    parts.push(`${look.rowCount} ${look.rowCount === 1 ? 'value' : 'values'}`)
  }
  return parts.length === 0 ? 'empty' : parts.join(' · ')
}

export function describeTemplate(template: TemplateSummary): string {
  if (template.kind === 'effect') {
    if (template.effect == null) return 'Effect'
    // No master label: that reads a live bank through a hook, and this string has to be renderable
    // by the overlay ghost. The pad draws `EffectPadDetail` over it and gains the `· M2`.
    const speed = effectSpeedLabel(template.effect.beatDivision, template.effect.timingSource)
    return [template.effect.effectType, speed].filter(Boolean).join(' · ')
  }
  if (!template.isGeneric) {
    const count = template.rows?.length ?? 0
    return `${count} ${count === 1 ? 'head' : 'heads'}`
  }
  return template.family != null ? FAMILY_LABELS[template.family].singular : 'value'
}

const EMPTY_FACE: PadFace = {
  kind: 'TEMPLATE',
  name: 'Missing',
  detail: 'this record is gone',
  swatch: null,
  isEffect: false,
  notes: null,
  cueNumber: null,
  stackName: null,
}

/**
 * The face for a pad.
 *
 * The record is embedded in the pad by the server, so this needs no lookup and no second fetch —
 * and an unresolvable pad (which the server drops rather than serves, but an optimistic local one
 * could be mid-flight) still draws something rather than throwing.
 */
export function padFaceOf(pad: BuskPad): PadFace {
  if (pad.kind === 'TEMPLATE' && pad.template != null) {
    return {
      kind: 'TEMPLATE',
      name: pad.template.name,
      detail: describeTemplate(pad.template),
      swatch: templateSwatch(pad.template),
      isEffect: pad.template.kind === 'effect',
      notes: pad.template.notes,
      cueNumber: null,
      stackName: null,
    }
  }
  if (pad.kind === 'LOOK' && pad.look != null) {
    return {
      kind: 'LOOK',
      name: pad.look.name,
      detail: describeLookContents(pad.look),
      swatch: null,
      isEffect: false,
      notes: pad.look.notes,
      cueNumber: null,
      stackName: null,
    }
  }
  if (pad.kind === 'CUE' && pad.cue != null) {
    return {
      kind: 'CUE',
      name: pad.cue.name,
      detail: pad.cue.cueStackName,
      swatch: null,
      isEffect: false,
      notes: null,
      cueNumber: pad.cue.cueNumber,
      stackName: pad.cue.cueStackName,
    }
  }
  return { ...EMPTY_FACE, kind: pad.kind }
}

/**
 * One pad's geometry, so the four surfaces that draw a pad cannot drift apart.
 *
 * Here rather than in `BuskPad.tsx` for [padPresenceClass]'s reason, and with one more caller than
 * it has: the programmer's template picker draws pads too, and must not import the busk pad itself
 * — that component is a draggable with an address on a page, which a picker's pad has neither of.
 * A cue pad keeps its own left-aligned shell in `BuskPad.tsx`; only a picker can be sure it is
 * drawing no cues.
 */
export const PAD_SHELL =
  'relative flex min-h-[56px] rounded-lg border p-2 transition-all select-none touch-manipulation'

/** The face a template or Look pad wears inside [PAD_SHELL] — centred name over a detail line. */
export const PAD_FACE_SHELL = 'flex-col items-center justify-center text-center'

/**
 * How an **effect** is marked where a swatch would otherwise be: `AudioWaveform` at this size.
 *
 * An effect template holds no rows, so there is no value to preview — the glyph the whole desk uses
 * for FX stands in its place. The four surfaces that draw it (`BuskPad`, `LibraryPalette`,
 * `TemplateStrip`'s chip and `TemplatePicker`'s pad) had four hand-written copies of this class
 * string, which is the drift this module exists to prevent; the icon is `AudioWaveform` at every one
 * of them, and that half is stated here rather than enforced, because a lucide import cannot live in
 * a `.ts` file. Other sizes elsewhere (`size-3.5` in a menu, `size-3 text-white` on a coloured tile)
 * are deliberately not this — they are not a pad's swatch slot.
 */
export const EFFECT_GLYPH_CLASS = 'size-3 shrink-0 text-muted-foreground'

/**
 * The presence ladder: how much of the selection a record covers, as a shell.
 *
 * Here rather than in `BuskPad.tsx` because three surfaces draw it and must not drift: the pad, its
 * drag ghost (so a lifted pad looks like itself), and the FX cue-slot overlay's Look tile
 * (`slotLitClass`). A tweak to the ring belongs in this one function.
 */
export function padPresenceClass(presence: EffectPresence): string {
  return cn(
    presence === 'none' && 'border-border bg-card',
    presence === 'some' && 'border-primary/40 bg-primary/10',
    presence === 'all' && 'border-primary bg-primary/20 ring-1 ring-primary/50',
  )
}
