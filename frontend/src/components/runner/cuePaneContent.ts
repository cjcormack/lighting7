import { useRef } from 'react'
import { isCueRow, type CueStackCueEntry } from '@/api/cueStacksApi'

/**
 * What the cue sheet's and the Timeline's bottom pane shows for a selection (show-view-fixes plan
 * D4, D5): the one selected cue, or which blank state. Kept apart from `CueEditorPane`, which
 * reaches the whole editor, so the rules test without a DOM.
 */

/** What the pane shows for a selection. */
export type CuePaneContent =
  | { kind: 'cue'; rowId: string; cueId: number; entryId: number }
  | { kind: 'none' }
  | { kind: 'several'; count: number }
  | { kind: 'markers'; count: number }

/** The pane's content for the selected rows: one cue row is that cue, markers aside. */
export function paneContentOf(rows: readonly { id: string; entry: CueStackCueEntry }[]): CuePaneContent {
  const cues = rows.filter((row) => isCueRow(row.entry))
  if (cues.length > 1) return { kind: 'several', count: cues.length }
  const [cue] = cues
  if (cue != null && isCueRow(cue.entry)) return { kind: 'cue', rowId: cue.id, cueId: cue.entry.id, entryId: cue.entry.entryId }
  return rows.length > 0 ? { kind: 'markers', count: rows.length } : { kind: 'none' }
}

/**
 * The content to draw: [content], except **during a marquee**, when it keeps what it had and follows
 * the selection on release (D5) — mounting `CueEditor` per frame would fetch each cue it crosses.
 */
export function useHeldPaneContent(content: CuePaneContent, marqueeDragging: boolean): CuePaneContent {
  // Written during render, as `SelectionBar`'s latch is: idempotent, and nothing outside reads it.
  const held = useRef(content)
  if (!marqueeDragging) held.current = content
  return held.current
}

const COUNT_WORDS = ['', 'one', 'both', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten']

/** The blank state's two lines. */
export function paneBlankText(content: Exclude<CuePaneContent, { kind: 'cue' }>): { title: string; detail: string } {
  switch (content.kind) {
    case 'none':
      return { title: 'No cue selected', detail: 'Select a cue to edit it here.' }
    case 'several': {
      const all = content.count === 2 ? 'both' : `all ${COUNT_WORDS[content.count] ?? content.count}`
      return { title: `${content.count} cues selected`, detail: `The editor shows one cue at a time. The bar above acts on ${all}.` }
    }
    case 'markers':
      return content.count === 1
        ? { title: 'A separator', detail: 'Rename it in its row.' }
        : { title: `${content.count} separators`, detail: 'Rename each in its row.' }
  }
}
