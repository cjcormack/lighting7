import { useMemo } from 'react'
import type { CueStack, CueStackCueEntry } from '../../api/cueStacksApi'
import type { FlatCue } from './desync'
import { flattenCueOrder, flattenShowRows } from './geometry'

/**
 * Everything the Prompt Book derives from the cue stacks alone — one flattening of the show, and
 * the lookups every surface on the page reads it through.
 *
 * It is a hook rather than a bag of `useMemo`s in the page because these are the page's only
 * *purely upstream* derivations: nothing here depends on the book, the lock, the transport or any
 * local view state, so they can be computed once, together, and handed round. Splitting them back
 * out is how the page ended up recomputing the same flatten under three different names.
 *
 * `cueOrder` is the authoritative reading order; `railRows` is the same order with the separators
 * and per-stack headers the rail draws. Every other member is an index into `cueOrder` or the raw
 * stack entries, so they cannot disagree with it.
 */
export function useCueIndex(stacks: CueStack[] | undefined, activeStackId: number | null) {
  const cueOrder: FlatCue[] = useMemo(() => flattenCueOrder(stacks), [stacks])
  // Rail rows include separators + per-stack headers (multi-stack only).
  const railRows = useMemo(() => flattenShowRows(stacks), [stacks])
  const cueOrderIndex = useMemo(() => new Map(cueOrder.map((c, i) => [c.cueId, i])), [cueOrder])
  // Live cue labels — the pill reads these so an edited cue number reflects at once
  // (the anchor's own cached label only refreshes when the anchor is re-saved).
  const cueLabelByCue = useMemo(() => new Map(cueOrder.map((c) => [c.cueId, c.label])), [cueOrder])

  // Names in play, so the create form can suggest a non-colliding "New Cue N".
  const existingCueNames = useMemo(() => new Set(cueOrder.map((c) => c.name)), [cueOrder])

  // Full stack entries by cue id — each expanded rail card renders the shared Run
  // card, which needs the entry's cueNumber/notes/auto (FlatCue carries only a label).
  const cueEntryByCue = useMemo(() => {
    const m = new Map<number, CueStackCueEntry>()
    for (const s of stacks ?? []) for (const c of s.cues) m.set(c.id, c)
    return m
  }, [stacks])

  // Notes for the script's right gutter. Only cues that actually have one are included, so the
  // viewer can render a bubble per entry without filtering blanks itself. An unanchored cue has
  // nowhere on the page to sit — its note shows on the rail card instead.
  const cueNotesByCue = useMemo(() => {
    const m = new Map<number, string>()
    for (const [id, c] of cueEntryByCue) if (c.notes) m.set(id, c.notes)
    return m
  }, [cueEntryByCue])

  // Default target stack for a cue created from a selection: the live stack (if runnable),
  // else the sole runnable stack, else the first in show order. null → no stack to add to.
  const defaultStackId = useMemo(() => {
    const runnable = (stacks ?? []).filter((s) => s.type === 'STACK')
    if (runnable.length === 0) return null
    if (activeStackId != null && runnable.some((s) => s.id === activeStackId)) return activeStackId
    return [...runnable].sort((a, b) => a.sortOrder - b.sortOrder)[0]?.id ?? null
  }, [stacks, activeStackId])

  return {
    cueOrder,
    railRows,
    cueOrderIndex,
    cueLabelByCue,
    existingCueNames,
    cueEntryByCue,
    cueNotesByCue,
    defaultStackId,
  }
}
