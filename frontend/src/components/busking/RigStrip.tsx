import type { ReactNode } from 'react'
import { Badge } from '@/components/ui/badge'
import { DeskChip } from '@/components/desk/DeskChip'
import { BlindPill } from './BlindMarks'
import { COMPACT_FOCUS_WORD_CLASS } from './RigBand'
import { formatFamilyList, type AttributeFamily } from '@/lib/attributeFamily'
import { cn } from '@/lib/utils'
import { EditorLabel } from '../editor/EditorLabel'
import { summariseSelection, type BuskingTarget } from './buskingTypes'

/**
 * The rig **folded** to a strip — Pads focus off the desk board (busk-further plan D5,
 * `Focus.dc.html`). **It is the band's compact row without the rows under it** (2026-09-22): the
 * same `px-4 pt-2.5 pb-2` and the same `min-h-7` row, so the row sits at the same height and the
 * Focus control at the same place whether the band or this strip is drawn — it was a 36px, then
 * 40px, chrome row of its own, whose row centre sat 4px off the band's, and it carried a chevron
 * before the Focus control that pushed the control along on this strip only. Both are gone:
 * **there is no chevron** — the Focus control beside it is the one way between the shapes, on
 * every board (D17's rule for the desk board's Pads, now everywhere) — and the label reads
 * **Pads**, since in Pads this row is the body's top row exactly as the desk board's pad row is,
 * and a row labelled *Rig* over a page of pads named the region that had just been folded away.
 *
 * It keeps what a press needs to be honest about: the selection summary, the family pill and the
 * desk chip (the link badge while this window follows the desk selection, which in Pads — this
 * strip's only focus — it always does, desk-follow plan D2 and D8) — and whatever the host hands in as [controls]: on the compact boards, the Focus
 * control and the edit toggle, which on the desk board live on the rig band's one row. It
 * cannot select — that is the other screen's job. Built here so the band and its strip are one
 * file apart and share one summary; `BuskingView` mounts it **only off the desk board** while
 * `busk.focus` is `pads` — on the desk board Pads draws no rig row at all, and the pad row is the
 * body's top row (D17, `BuskPageStrip`).
 *
 * **On the short board the strip has no row of its own** (`Phones.dc.html`, landscape): the rig
 * strip and the page strip merge into one 32px row, so the pieces are [RigStripContent] and the
 * row is here only for the boards where the strip stands alone. One set of pieces, placed on one
 * of two rows — never a third strip.
 */
export interface RigStripProps {
  selectedTargets: Map<string, BuskingTarget>
  families: AttributeFamily[] | null
  /** Drawn at the row's end: the Focus control, at the top-right of the body as on the band. */
  controls?: ReactNode
  className?: string
}

export function RigStrip({ className, ...content }: RigStripProps) {
  return (
    // `@container`, like the band: the Focus labels the host hands in fold on a container query,
    // and a container query with no query container never matches — the labels were glyph-only
    // at every width here until this strip became one.
    // The band's own box and row (`RigBand`'s compact arm): `pt-2.5 pb-2` around a `min-h-7` row,
    // so the row's centre is where the band's is and nothing moves when the shape changes.
    <div data-rig-strip className={cn('@container shrink-0 border-b px-4 pt-2.5 pb-2', className)}>
      <div className="flex min-h-7 items-center gap-x-2">
        <RigStripContent {...content} />
      </div>
    </div>
  )
}

/** The strip's pieces — label, summary, family pill, blind pill, desk chip — without a row. */
export function RigStripContent({ selectedTargets, families, controls }: Omit<RigStripProps, 'className'>) {
  const summary = summariseSelection([...selectedTargets.values()])
  return (
    <>
      {/* *Pads*, not *Rig*: in Pads this is the body's top row, as the desk board's pad row is. */}
      <EditorLabel>Pads</EditorLabel>
      <span className="min-w-0 flex-1 truncate text-[11px] text-muted-foreground">{summary}</span>
      {families != null && families.length > 0 && (
        <Badge
          variant="outline"
          className="shrink-0 whitespace-nowrap border-primary/40 bg-primary/10 px-2 py-0 text-[10px] text-primary"
        >
          {formatFamilyList(families, ' · ')}
        </Badge>
      )}
      {/* Blind, beside the mask: off the desk board this strip and the short board's merged row
          are the only rig chrome in Pads — the resting focus there — and the sheet is an overlay
          with no fold, so without it a blind programmer was reported nowhere (`BlindMarks.tsx`). */}
      <BlindPill wordClass={COMPACT_FOCUS_WORD_CLASS} />
      {/* Pads is this strip's only focus, and Pads always follows (D2): the badge is a mark here. */}
      <DeskChip showSubject forcedBy="pads" />
      {controls}
    </>
  )
}
