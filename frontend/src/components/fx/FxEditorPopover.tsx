import { useCallback, useEffect, useMemo, useRef, useState, type RefObject } from 'react'
import { Button } from '@/components/ui/button'
import type { CueTarget } from '@/api/cuesApi'
import type { UpdateFxRequest } from '@/api/fxApi'
import { lightingApi } from '@/api/lightingApi'
import { useWindowPressPair } from '@/hooks/usePress'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { isEffectSplit, useSplitFx, type ActiveEffect } from '@/store/fixtureFx'
import type { Fixture } from '@/store/fixtures'
import { useIsDeskConnected } from '@/store/status'
import { EditorSurface } from '../editor/EditorSurface'
import { FxEditor } from './FxEditor'
import { splitScopeOf } from './effectSplit'
import { useStopEffect } from './useEffectControls'

/**
 * `FxEditor` in `EditorSurface` (fixture-fx-sheets plan D17): the programmer rail's *Edit…*, the
 * applied FX table's, `FxSheet`'s chips and a cell popup's Effects band open the live editor beside
 * what was pressed — a popover anchored at the row or the chip on a desk, a bottom sheet on an
 * upright phone, a side sheet where the viewport is short, the three forms every cell editor takes.
 *
 * Mounted once by its host with no trigger of its own: [anchorRef] is the element the gesture was
 * made at, set before [effect] is, so the popover is positioned at it (`EditorSurface`'s virtual
 * anchor). [effect] is the **live** instance the host reads from the running list each render; a
 * host closes the editor when its effect stops, rather than editing an id the desk no longer has.
 *
 * **Edits follow the selection** (apply-model plan D5, §4.5's split editor): see [SplitEditor].
 */
export function FxEditorPopover({
  effect,
  anchorRef,
  onClose,
  onStop,
  onFollow,
  selection,
}: {
  effect: ActiveEffect | null
  anchorRef: RefObject<HTMLElement | null>
  onClose: () => void
  onStop: (effect: ActiveEffect) => void
  /** A split replaced the effect: the host reads this id from now on — the edited copy it follows. */
  onFollow?: (effectId: number) => void
  /** The heads an edit is for — a cell's batch. Absent, the window's selection pair's targets. */
  selection?: readonly CueTarget[]
}) {
  // One editing session across a split: the copies it follows keep the editor's own state. A close
  // ends it, so a copy opened again later is edited alone.
  const [follow, setFollow] = useState<{ session: number; ids: readonly number[] } | null>(null)
  const open = effect != null
  const close = () => {
    setFollow(null)
    onClose()
  }
  const session = effect == null ? null : follow != null && follow.ids.includes(effect.id) ? follow.session : effect.id
  const followed = follow != null && follow.session === session ? follow.ids : null
  const onSplit = useCallback(
    (ids: readonly number[]) => {
      if (session == null || ids.length === 0) return
      setFollow({ session, ids })
      onFollow?.(ids[0])
    },
    [session, onFollow],
  )
  const anchor = useStableAnchor(anchorRef)
  return (
    <EditorSurface
      open={open}
      onOpenChange={(next) => !next && close()}
      title={effect ? `Edit ${effect.effectType}` : 'Edit effect'}
      anchorRef={anchor}
      // Bounded by the room Radix measured beside the anchor and scrolled inside it: an Absolute
      // Circle is ~530px of controls, and a popover taller than its room renders above the viewport
      // with its × and Speed unreachable (`EditorSurface`'s note on a popover that cannot fit).
      contentClassName="w-80 max-h-[var(--radix-popover-content-available-height)] overflow-y-auto p-3"
      align="start"
    >
      {effect != null && (
        <SplitEditor
          key={session ?? undefined}
          effect={effect}
          selection={selection}
          followed={followed}
          onSplit={onFollow != null ? onSplit : undefined}
          onDone={close}
          onStop={() => {
            setFollow(null)
            onStop(effect)
          }}
        />
      )}
    </EditorSurface>
  )
}

/**
 * The anchor a host set, kept where it last was once it leaves the page: a split replaces the effect
 * whose row or chip the editor hangs from, and a detached element measures as the viewport's corner.
 */
function useStableAnchor(anchorRef: RefObject<HTMLElement | null>): RefObject<{ getBoundingClientRect(): DOMRect }> {
  return useMemo(() => {
    let last = new DOMRect()
    return {
      current: {
        getBoundingClientRect: () => {
          const element = anchorRef.current
          if (element?.isConnected) last = element.getBoundingClientRect()
          return last
        },
      },
    }
  }, [anchorRef])
}

/** [SplitEditor] over the window's selection, for a host that names none of its own. */
function useEditSelection(selection: readonly CueTarget[] | undefined): readonly CueTarget[] {
  const windowPair = useWindowPressPair()
  return selection ?? windowPair.targets
}

/**
 * The live editor, following the selection (apply-model plan §3.4, D5 — MagicQ's rule): with every
 * head the effect paints selected — or none of them — every move edits it in place, phase kept. With
 * **some** of them it says so — *Hex 1–2 of Hex 1–4* — and **holds** the edit: *Apply to Hex 1–2*
 * sends it once, over REST with the heads as `targets`, and the desk splits them off at the phase
 * they had (only REST answers the copies, `EffectSplitResponse`; the `updateFx` frame answers only
 * `fxChanged` REMOVED and ADDED). The editor then **follows the edited copies** — one per head, each
 * keeping one phase offset — and is live again, every move sent to each. *Edit all 4* sends what is
 * held to the whole effect in place and carries on live.
 *
 * Only a Local effect splits: a palette layer's is edited as its layer's (apply-model plan P5) and a
 * cue's or a manual one's would be refused, so they are edited in place whatever is selected. Which
 * heads the selection covers is a reading for the words ([splitScopeOf]); the split itself is the
 * desk's, from the heads named.
 */
function SplitEditor({
  effect,
  selection,
  followed,
  onSplit,
  onDone,
  onStop,
}: {
  effect: ActiveEffect
  selection: readonly CueTarget[] | undefined
  /** The copies an earlier split of this session left the edit on. */
  followed: readonly number[] | null
  /** Absent: the host cannot follow a new id, so no split is offered. */
  onSplit?: (ids: readonly number[]) => void
  onDone: () => void
  onStop: () => void
}) {
  const { fixtures } = useFixtureLookup()
  const connected = useIsDeskConnected()
  const editSelection = useEditSelection(selection)
  const stopEffect = useStopEffect()
  const { splitFx, splitting } = useSplitFx()
  const [whole, setWhole] = useState(false)
  const [held, setHeld] = useState<UpdateFxRequest | null>(null)
  // Bumped to draw the editor afresh from the effect, dropping a held edit nobody applied.
  const [generation, setGeneration] = useState(0)
  const alive = useRef(true)
  useEffect(
    () => () => {
      alive.current = false
    },
    [],
  )

  const scope = useMemo(() => splitScopeOf(effect, editSelection, fixtures ?? NO_FIXTURES), [effect, editSelection, fixtures])
  const following = followed != null && followed.includes(effect.id)
  const split = onSplit != null && !whole && !following && scope.covered != null ? scope : null
  const covered = split?.covered ?? null
  const alsoIds = useMemo(() => (following ? followed.filter((id) => id !== effect.id) : undefined), [following, followed, effect.id])

  // The selection moved off the subset while an edit was held: it was never applied, so it goes, and
  // the editor shows the effect as it runs rather than sending the held move to every head.
  const holding = split != null
  useEffect(() => {
    if (holding || held == null) return
    setHeld(null)
    setGeneration((g) => g + 1)
  }, [holding, held])

  const apply = async () => {
    if (held == null || covered == null) return
    const targets = covered.map((key) => ({ type: 'fixture' as const, key }))
    // A refusal is reported by errorToastMiddleware; the held edit stays, to be applied again or
    // reverted. Closed while the split was on its way: the copies run, and the editor stays closed.
    const answer = await splitFx(effect.id, { ...held, targets })
    if (answer == null || !alive.current) return
    setHeld(null)
    if (isEffectSplit(answer)) onSplit?.(answer.edited.map((e) => e.id))
    else setWhole(true)
  }

  const editAll = () => {
    if (held != null) lightingApi.fx.updateFx(effect.id, held)
    setHeld(null)
    setWhole(true)
  }

  // × stops what the edit was for: after a split, every copy it follows.
  const stop = () => {
    for (const id of alsoIds ?? []) stopEffect({ ...effect, id })
    onStop()
  }

  return (
    <div className="flex flex-col gap-2">
      {split != null && (
        <div
          data-fx-split
          className="flex flex-wrap items-center gap-1.5 rounded-md border border-violet-500/40 bg-violet-500/[0.06] px-2 py-1.5 text-[11px]"
        >
          <span className="min-w-0 flex-1 truncate" title={`The selection holds ${split.coveredLabel} of the ${split.heads.length} heads this effect runs on`}>
            <span className="font-medium">{split.coveredLabel}</span>
            <span className="text-muted-foreground"> of {split.totalLabel}</span>
          </span>
          <Button
            type="button"
            size="sm"
            className="h-6 px-2 text-[11px]"
            disabled={held == null || splitting || !connected}
            title={held == null ? 'Change something first — the edit is held until it is applied' : `Split ${split.coveredLabel} off with this edit, each where it was`}
            onClick={() => void apply()}
          >
            Apply to {split.coveredLabel}
          </Button>
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="h-6 px-2 text-[11px]"
            disabled={splitting || !connected}
            title={`Edit all ${split.heads.length} heads in place, live`}
            onClick={editAll}
          >
            Edit all {split.heads.length}
          </Button>
        </div>
      )}
      {/* Inert while the split is on its way: a move now would land on an effect about to go. */}
      <div inert={splitting} className={splitting ? 'opacity-60' : undefined}>
        <FxEditor
          key={`${effect.id}:${effect.effectType}:${generation}`}
          effect={effect}
          hold={split != null ? setHeld : undefined}
          alsoIds={alsoIds}
          onDone={onDone}
          onStop={stop}
        />
      </div>
    </div>
  )
}

const NO_FIXTURES: readonly Fixture[] = []
