import { useCallback, useMemo, useRef, useState } from 'react'
import type { RefObject } from 'react'
import type { CueStack } from '../../api/cueStacksApi'
import type { PromptBookDetails, Region } from '../../api/promptBooksApi'
import { regionsOverlap, type FlatCue } from './desync'
import { nearestAnchoredCue, orderedCueIdsForInsert } from './geometry'
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
  cueId: number
  region: Region
  label: string | null
}

/**
 * Cue anchors: the mapping between cues and places in the script, and everything that follows from
 * it — where the book scrolls for a cue, what an unanchored cue borrows, the one-deep undo a
 * re-anchor arms, and the click-to-place / pick-a-cue flows that create anchors in the first place.
 *
 * These belong together because every one of them reads `anchorByCue`, and because the fallback
 * rule ("an unanchored cue borrows its neighbour's position") has to be the same in the navigation
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
  cueLabelByCue,
  viewerRef,
}: {
  projectId: number
  book: PromptBookDetails | undefined
  stacks: CueStack[] | undefined
  cueOrder: FlatCue[]
  cueLabelByCue: Map<number, string>
  viewerRef: RefObject<ScriptViewerHandle | null>
}) {
  const [upsertAnchor] = useUpsertAnchorMutation()
  const [deleteAnchor] = useDeleteAnchorMutation()
  const [createCue] = useCreateProjectCueMutation()
  const [createStack] = useCreateProjectCueStackMutation()
  const [reorderCues] = useReorderCueStackCuesMutation()

  const [placingCueId, setPlacingCueId] = useState<number | null>(null)
  // Region awaiting a cue choice — set when "Anchor cue" is clicked on a selection.
  const [anchorPicker, setAnchorPicker] = useState<{ region: Region } | null>(null)
  const [undoSnapshot, setUndoSnapshot] = useState<AnchorUndoSnapshot | null>(null)

  const anchorByCue = useMemo(
    () => new Map((book?.anchors ?? []).map((a) => [a.cueId, a])),
    [book],
  )

  // Where the book should scroll for a cue: its own anchor, else a best-effort borrow
  // from the nearest anchored cue (normally the one before it). An unanchored cue —
  // pre-show, house lights, an auto-follow — is a legitimate state, not a dead end.
  const resolveScrollRegion = useCallback(
    (cueId: number): Region | null => {
      const anchor = anchorByCue.get(cueId)
      if (anchor) return anchor.region
      return nearestAnchoredCue(cueId, cueOrder, anchorByCue)?.region ?? null
    },
    [anchorByCue, cueOrder],
  )

  // The resolver lives in a ref so `scrollToCue` keeps one identity for the whole session: it is
  // passed to the memoized viewer and read from an effect on the playhead, and neither should
  // re-run because an unrelated book refetch (edit, WS echo) rebuilt the resolver — that would
  // yank the viewport while the operator reads ahead.
  const resolveScrollRegionRef = useRef(resolveScrollRegion)
  resolveScrollRegionRef.current = resolveScrollRegion
  const scrollToCue = useCallback(
    (cueId: number) => {
      const region = resolveScrollRegionRef.current(cueId)
      if (region) viewerRef.current?.scrollToRegion(region)
    },
    [viewerRef],
  )

  // "follows Q12" / "before Q14" for each unanchored cue — names what the book scrolls
  // to. Built from the same helper as the navigation, so the two can't disagree.
  const anchorHintByCue = useMemo(() => {
    const m = new Map<number, string>()
    for (const cue of cueOrder) {
      if (anchorByCue.has(cue.cueId)) continue
      const fallback = nearestAnchoredCue(cue.cueId, cueOrder, anchorByCue)
      if (fallback) {
        m.set(cue.cueId, `${fallback.direction === 'before' ? 'follows' : 'before'} ${fallback.cue.label}`)
      }
    }
    return m
  }, [cueOrder, anchorByCue])

  // When the picker opens on a selection that lands on an existing anchor, surface that
  // cue (preselect + edit affordance) so "edit this cue" is one tap.
  const overlapCueId = useMemo(() => {
    if (!anchorPicker) return null
    return (book?.anchors ?? []).find((a) => regionsOverlap(a.region, anchorPicker.region))?.cueId ?? null
  }, [anchorPicker, book])

  const clearPlacing = useCallback(() => setPlacingCueId(null), [])
  /** Arm (or disarm) click-to-place for a cue. Toggling the armed cue cancels. */
  const togglePlacing = useCallback(
    (cueId: number) => setPlacingCueId((prev) => (prev === cueId ? null : cueId)),
    [],
  )

  const moveAnchor = useCallback(
    (cueId: number, region: Region, prevRegion: Region) => {
      const anchor = anchorByCue.get(cueId)
      setUndoSnapshot({ cueId, region: prevRegion, label: anchor?.label ?? null })
      upsertAnchor({
        projectId,
        cueId,
        region,
        label: anchor?.label ?? undefined,
      })
    },
    [anchorByCue, upsertAnchor, projectId],
  )

  const undo = useCallback(() => {
    if (!undoSnapshot) return
    upsertAnchor({
      projectId,
      cueId: undoSnapshot.cueId,
      region: undoSnapshot.region,
      label: undoSnapshot.label ?? undefined,
    })
    setUndoSnapshot(null)
  }, [undoSnapshot, upsertAnchor, projectId])

  const placeAnchor = useCallback(
    (region: Region) => {
      if (placingCueId == null) return
      // Re-anchoring an existing cue → snapshot the old region so it can be undone.
      const existing = anchorByCue.get(placingCueId)
      if (existing) setUndoSnapshot({ cueId: placingCueId, region: existing.region, label: existing.label ?? null })
      upsertAnchor({
        projectId,
        cueId: placingCueId,
        region,
        label: cueLabelByCue.get(placingCueId),
      })
      setPlacingCueId(null)
    },
    [placingCueId, cueLabelByCue, anchorByCue, upsertAnchor, projectId],
  )

  // Anchor a chosen cue to a selected region (from the cue picker). Overwriting an
  // existing anchor re-anchors it; snapshot the old region so it can be undone.
  const anchorCue = useCallback(
    (cueId: number, region: Region) => {
      const existing = anchorByCue.get(cueId)
      if (existing) setUndoSnapshot({ cueId, region: existing.region, label: existing.label ?? null })
      upsertAnchor({ projectId, cueId, region, label: cueLabelByCue.get(cueId) })
      setAnchorPicker(null)
      setPlacingCueId(null)
    },
    [cueLabelByCue, anchorByCue, upsertAnchor, projectId],
  )

  const removeAnchor = useCallback(
    (cueId: number) => {
      deleteAnchor({ projectId, cueId })
    },
    [deleteAnchor, projectId],
  )

  // Stable identity so the memoized ScriptViewer isn't re-rendered every fade frame.
  const requestAnchor = useCallback((region: Region) => setAnchorPicker({ region }), [])
  const closePicker = useCallback(() => setAnchorPicker(null), [])

  // Create a brand-new cue from the current selection: resolve the target stack (creating
  // one inline when asked), make the cue, anchor it to the region, then slot it into an
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
          adHocEffects: [],
          triggers: [],
          fadeDurationMs: 3000,
          fadeCurve: 'LINEAR',
          cueStackId: stackId,
        }).unwrap()
        await upsertAnchor({
          projectId,
          cueId: newCue.id,
          region,
          label: cueNumber ? `Q${cueNumber}` : name,
        }).unwrap()
        // Reading-order placement only matters when joining an existing stack that has cues;
        // reorder only when it differs from the natural append the create already performed.
        if (stack.kind === 'existing') {
          const existing = (stacks ?? []).find((s) => s.id === stackId)
          if (existing) {
            const cueIds = orderedCueIdsForInsert(existing, anchorByCue, region, newCue.id)
            const appended = [...existing.cues.map((c) => c.id), newCue.id]
            const isAppend = cueIds.length === appended.length && cueIds.every((id, i) => id === appended[i])
            if (!isAppend) await reorderCues({ projectId, stackId, cueIds }).unwrap()
          }
        }
        setAnchorPicker(null)
      } catch {
        // Leave the sheet open so the operator can retry.
      }
    },
    [anchorPicker, createStack, createCue, upsertAnchor, reorderCues, projectId, stacks, anchorByCue],
  )

  return {
    anchorByCue,
    anchorHintByCue,
    scrollToCue,
    placingCueId,
    clearPlacing,
    togglePlacing,
    anchorPicker,
    overlapCueId,
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
