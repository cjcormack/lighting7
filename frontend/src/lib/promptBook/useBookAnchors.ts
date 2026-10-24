import { useCallback, useMemo, useRef, useState } from 'react'
import type { RefObject } from 'react'
import type { CueStack } from '../../api/cueStacksApi'
import type { PromptBookDetails, Region } from '../../api/promptBooksApi'
import { regionsOverlap, type FlatCue } from './desync'
import { nearestAnchoredCue, orderedEntryIdsForInsert } from './geometry'
import type { ScriptViewerHandle } from '../../components/promptbook/ScriptViewer'
import type { NewCueStackChoice } from '../../components/promptbook/CueAnchorPickerSheet'
import {
  useUpsertAnchorMutation,
  useDeleteAnchorMutation,
} from '../../store/promptBooks'
import { useCreateProjectCueMutation } from '../../store/cues'
import {
  useCreateProjectCueStackMutation,
  useReorderCueStackCuesMutation,
} from '../../store/cueStacks'

/** The one-deep undo slot a re-anchor fills: where the anchor was, and the label it wore. */
interface AnchorUndoSnapshot {
  entryId: number
  cueId: number
  region: Region
  label: string | null
}

/**
 * Cue anchors: the mapping between stack entries and places in the script, and everything that
 * follows from it — where the book scrolls for an entry, what an unanchored entry borrows, the
 * one-deep undo a re-anchor arms, and the click-to-place / pick-a-cue flows that create anchors in
 * the first place.
 *
 * Anchors are the **entry's** (lighting7 cue model plan D4): a cue shared by two stacks is two places
 * in the show and may be anchored at two places in the script. So everything here is keyed by entry,
 * and the cue id rides along only where the desk's anchor row carries it.
 *
 * These belong together because every one of them reads `anchorByEntry`, and because the fallback
 * rule ("an unanchored entry borrows its neighbour's position") has to be the same in the navigation
 * and in the hint text the rail prints, or the book scrolls somewhere the rail did not promise.
 *
 * Two things are deliberately *not* here:
 *
 *  - **The lock.** `handleCueClick`'s "unlocked also arms placing" rule is composed by the caller,
 *    which is what keeps this hook out of the ordering knot between the lock, the transport and the
 *    playhead. Nothing here needs to know whether editing is allowed; the affordances that reach it
 *    are already gated.
 *  - **The idle re-lock.** No `noteEdit()` calls: the page catches edit interaction once at its
 *    boundary with capture handlers, so a new affordance added here cannot forget to feed it.
 */
export function useBookAnchors({
  projectId,
  book,
  stacks,
  cueOrder,
  labelByEntry,
  viewerRef,
}: {
  projectId: number
  book: PromptBookDetails | undefined
  stacks: CueStack[] | undefined
  cueOrder: FlatCue[]
  labelByEntry: Map<number, string>
  viewerRef: RefObject<ScriptViewerHandle | null>
}) {
  const [upsertAnchor] = useUpsertAnchorMutation()
  const [deleteAnchor] = useDeleteAnchorMutation()
  const [createCue] = useCreateProjectCueMutation()
  const [createStack] = useCreateProjectCueStackMutation()
  const [reorderCues] = useReorderCueStackCuesMutation()

  const [placingEntryId, setPlacingEntryId] = useState<number | null>(null)
  // Region awaiting a cue choice — set when "Anchor cue" is clicked on a selection.
  const [anchorPicker, setAnchorPicker] = useState<{ region: Region } | null>(null)
  const [undoSnapshot, setUndoSnapshot] = useState<AnchorUndoSnapshot | null>(null)

  const anchorByEntry = useMemo(
    () => new Map((book?.anchors ?? []).map((a) => [a.entryId, a])),
    [book],
  )
  // The cue an entry places — what a new anchor row names beside its entry.
  const cueIdByEntry = useMemo(() => new Map(cueOrder.map((c) => [c.entryId, c.cueId])), [cueOrder])

  // Where the book should scroll for an entry: its own anchor, else a best-effort borrow
  // from the nearest anchored entry (normally the one before it). An unanchored cue —
  // pre-show, house lights, an auto-follow — is a legitimate state, not a dead end.
  const resolveScrollRegion = useCallback(
    (entryId: number): Region | null => {
      const anchor = anchorByEntry.get(entryId)
      if (anchor) return anchor.region
      return nearestAnchoredCue(entryId, cueOrder, anchorByEntry)?.region ?? null
    },
    [anchorByEntry, cueOrder],
  )

  // The resolver lives in a ref so `scrollToEntry` keeps one identity for the whole session: it is
  // passed to the memoized viewer and read from an effect on the playhead, and neither should
  // re-run because an unrelated book refetch (edit, WS echo) rebuilt the resolver — that would
  // yank the viewport while the operator reads ahead.
  const resolveScrollRegionRef = useRef(resolveScrollRegion)
  resolveScrollRegionRef.current = resolveScrollRegion
  const scrollToEntry = useCallback(
    (entryId: number) => {
      const region = resolveScrollRegionRef.current(entryId)
      if (region) viewerRef.current?.scrollToRegion(region)
    },
    [viewerRef],
  )

  // "follows Q12" / "before Q14" for each unanchored entry — names what the book scrolls
  // to. Built from the same helper as the navigation, so the two can't disagree.
  const anchorHintByEntry = useMemo(() => {
    const m = new Map<number, string>()
    for (const cue of cueOrder) {
      if (anchorByEntry.has(cue.entryId)) continue
      const fallback = nearestAnchoredCue(cue.entryId, cueOrder, anchorByEntry)
      if (fallback) {
        m.set(cue.entryId, `${fallback.direction === 'before' ? 'follows' : 'before'} ${fallback.cue.label}`)
      }
    }
    return m
  }, [cueOrder, anchorByEntry])

  // When the picker opens on a selection that lands on an existing anchor, surface that
  // entry (preselect + edit affordance) so "edit this cue" is one tap.
  const overlapEntryId = useMemo(() => {
    if (!anchorPicker) return null
    return (book?.anchors ?? []).find((a) => regionsOverlap(a.region, anchorPicker.region))?.entryId ?? null
  }, [anchorPicker, book])

  const clearPlacing = useCallback(() => setPlacingEntryId(null), [])
  /** Arm (or disarm) click-to-place for an entry. Toggling the armed entry cancels. */
  const togglePlacing = useCallback(
    (entryId: number) => setPlacingEntryId((prev) => (prev === entryId ? null : entryId)),
    [],
  )

  const writeAnchor = useCallback(
    (entryId: number, region: Region, label: string | null | undefined) => {
      const cueId = anchorByEntry.get(entryId)?.cueId ?? cueIdByEntry.get(entryId)
      if (cueId == null) return
      upsertAnchor({ projectId, entryId, cueId, region, label: label ?? undefined })
    },
    [anchorByEntry, cueIdByEntry, upsertAnchor, projectId],
  )

  const moveAnchor = useCallback(
    (entryId: number, region: Region, prevRegion: Region) => {
      const anchor = anchorByEntry.get(entryId)
      if (anchor) setUndoSnapshot({ entryId, cueId: anchor.cueId, region: prevRegion, label: anchor.label ?? null })
      writeAnchor(entryId, region, anchor?.label)
    },
    [anchorByEntry, writeAnchor],
  )

  const undo = useCallback(() => {
    if (!undoSnapshot) return
    upsertAnchor({
      projectId,
      entryId: undoSnapshot.entryId,
      cueId: undoSnapshot.cueId,
      region: undoSnapshot.region,
      label: undoSnapshot.label ?? undefined,
    })
    setUndoSnapshot(null)
  }, [undoSnapshot, upsertAnchor, projectId])

  const placeAnchor = useCallback(
    (region: Region) => {
      if (placingEntryId == null) return
      // Re-anchoring an existing entry → snapshot the old region so it can be undone.
      const existing = anchorByEntry.get(placingEntryId)
      if (existing) {
        setUndoSnapshot({ entryId: placingEntryId, cueId: existing.cueId, region: existing.region, label: existing.label ?? null })
      }
      writeAnchor(placingEntryId, region, labelByEntry.get(placingEntryId))
      setPlacingEntryId(null)
    },
    [placingEntryId, labelByEntry, anchorByEntry, writeAnchor],
  )

  // Anchor a chosen entry to a selected region (from the cue picker). Overwriting an
  // existing anchor re-anchors it; snapshot the old region so it can be undone.
  const anchorCue = useCallback(
    (entryId: number, region: Region) => {
      const existing = anchorByEntry.get(entryId)
      if (existing) setUndoSnapshot({ entryId, cueId: existing.cueId, region: existing.region, label: existing.label ?? null })
      writeAnchor(entryId, region, labelByEntry.get(entryId))
      setAnchorPicker(null)
      setPlacingEntryId(null)
    },
    [labelByEntry, anchorByEntry, writeAnchor],
  )

  const removeAnchor = useCallback(
    (entryId: number) => {
      deleteAnchor({ projectId, entryId })
    },
    [deleteAnchor, projectId],
  )

  // Stable identity so the memoized ScriptViewer isn't re-rendered every fade frame.
  const requestAnchor = useCallback((region: Region) => setAnchorPicker({ region }), [])
  const closePicker = useCallback(() => setAnchorPicker(null), [])

  // Create a brand-new cue from the current selection: resolve the target stack (creating
  // one inline when asked), make the cue, anchor its entry to the region, then slot it into an
  // existing stack in reading order. Reused create-stack/create-cue/anchor/reorder mutations
  // — no new backend surface. The sheet stays open on failure to retry.
  const createCueFromSelection = useCallback(
    async ({
      name,
      cueNumber,
      notes,
      stack,
    }: {
      name: string
      cueNumber: string | null
      notes: string | null
      stack: NewCueStackChoice
    }) => {
      if (!anchorPicker) return
      const region = anchorPicker.region
      try {
        // A brand-new stack has no other cues, so there's nothing to reorder against.
        let stackId: number
        if (stack.kind === 'new') {
          const created = await createStack({
            projectId,
            name: stack.name,
            loop: false,
            type: 'STACK',
          }).unwrap()
          stackId = created.id
        } else {
          stackId = stack.id
        }
        const newCue = await createCue({
          projectId,
          name,
          cueNumber,
          notes,
          layers: [],
          adHocFx: [],
          triggers: [],
          fadeDurationMs: 3000,
          fadeCurve: 'LINEAR',
          cueStackId: stackId,
        }).unwrap()
        // The new cue's one entry is what is anchored and what moves; a desk that predates
        // entries sends none, and there is nothing to anchor by.
        const newEntryId = newCue.entryId
        if (newEntryId != null) {
          await upsertAnchor({
            projectId,
            entryId: newEntryId,
            cueId: newCue.id,
            region,
            label: cueNumber ? `Q${cueNumber}` : name,
          }).unwrap()
        }
        // Reading-order placement only matters when joining an existing stack that has cues;
        // reorder only when it differs from the natural append the create already performed.
        if (stack.kind === 'existing') {
          const existing = (stacks ?? []).find((s) => s.id === stackId)
          if (existing && newEntryId != null) {
            const entryIds = orderedEntryIdsForInsert(existing, anchorByEntry, region, newEntryId)
            const appended = [...existing.cues.map((c) => c.entryId), newEntryId]
            const isAppend = entryIds.length === appended.length && entryIds.every((id, i) => id === appended[i])
            if (!isAppend) await reorderCues({ projectId, stackId, entryIds }).unwrap()
          }
        }
        setAnchorPicker(null)
      } catch {
        // Leave the sheet open so the operator can retry.
      }
    },
    [anchorPicker, createStack, createCue, upsertAnchor, reorderCues, projectId, stacks, anchorByEntry],
  )

  return {
    anchorByEntry,
    anchorHintByEntry,
    scrollToEntry,
    placingEntryId,
    clearPlacing,
    togglePlacing,
    anchorPicker,
    overlapEntryId,
    requestAnchor,
    closePicker,
    undoSnapshot,
    undo,
    moveAnchor,
    placeAnchor,
    anchorCue,
    removeAnchor,
    createCueFromSelection,
  }
}
