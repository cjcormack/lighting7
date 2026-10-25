import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import type { CueTarget } from '@/api/cuesApi'
import type { UpdateFxRequest } from '@/api/fxApi'
import { lightingApi } from '@/api/lightingApi'
import { useWindowPressPair } from '@/hooks/usePress'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { isFxSplit, useSplitFx, type ActiveFx } from '@/store/fixtureFx'
import type { Fixture } from '@/store/fixtures'
import { useIsDeskConnected } from '@/store/status'
import { FxEditor } from './FxEditor'
import { splitScopeOf } from './fxSplit'
import { useStopFx } from './useFxControls'

/**
 * One editing session across a split: the copies an edit follows. The **host** holds it, beside the
 * id it edits, because the editor may remount under it — the busk Effects tab draws it under the
 * row of the effect being edited, and a split moves that to the copy's row — and a host clears it
 * when the editor closes, so a copy opened again later is edited alone.
 */
export type SplitSession = { session: number; ids: readonly number[] } | null

/**
 * The live effect editor that **follows the selection** (apply-model plan D5, §4.5's split editor),
 * wherever it is drawn: inside `FxEditorPopover` — an FX lens chip, a band's running chip — in the
 * FX editor's inspector, and inline under a row of the busk Effects tab.
 *
 * The desk answers a split with new ids: the host is told the one to read from now on ([onFollow])
 * and the session the copies share ([onSessionChange]), and reads [effect] live from the running
 * list, which the split's answer has already updated. See [SplitEditor] for what an edit does.
 */
export function SplitFxEditor({
  effect,
  selection,
  session: follow,
  onSessionChange,
  onClose,
  onStop,
  onFollow,
  layout,
}: {
  effect: ActiveFx
  /** The heads an edit is for — a cell's batch, the busk pair's targets. Absent, the window's pair's. */
  selection?: readonly CueTarget[]
  session: SplitSession
  onSessionChange: (session: SplitSession) => void
  onClose: () => void
  /** Absent: no stop is offered — a pad's effect, which the pad releases. */
  onStop?: (effect: ActiveFx) => void
  /** A split replaced the effect: the host reads this id from now on — the edited copy it follows. */
  onFollow?: (fxId: number) => void
  /** `FxFields`' section order: the FX editor's inspector draws its own. */
  layout?: 'editor' | 'inspector'
}) {
  const session = follow != null && follow.ids.includes(effect.id) ? follow.session : effect.id
  const followed = follow != null && follow.session === session ? follow.ids : null
  const onSplit = useCallback(
    (ids: readonly number[]) => {
      if (ids.length === 0) return
      onSessionChange({ session, ids })
      onFollow?.(ids[0])
    },
    [session, onSessionChange, onFollow],
  )
  return (
    <SplitEditor
      key={session}
      effect={effect}
      selection={selection}
      followed={followed}
      onSplit={onFollow != null ? onSplit : undefined}
      onDone={onClose}
      onStop={onStop == null ? undefined : () => onStop(effect)}
      layout={layout}
    />
  )
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
 * they had (only REST answers the copies, `FxSplitResponse`; the `updateFx` frame answers only
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
  layout,
}: {
  effect: ActiveFx
  selection: readonly CueTarget[] | undefined
  /** The copies an earlier split of this session left the edit on. */
  followed: readonly number[] | null
  /** Absent: the host cannot follow a new id, so no split is offered. */
  onSplit?: (ids: readonly number[]) => void
  onDone: () => void
  onStop?: () => void
  layout?: 'editor' | 'inspector'
}) {
  const { fixtures } = useFixtureLookup()
  const connected = useIsDeskConnected()
  const editSelection = useEditSelection(selection)
  const stopFx = useStopFx()
  const { splitFx, splitting } = useSplitFx()
  const [whole, setWhole] = useState(false)
  const [held, setHeld] = useState<UpdateFxRequest | null>(null)
  // Bumped to draw the editor afresh from the effect, dropping a held edit nobody applied.
  const [generation, setGeneration] = useState(0)
  // Set in the effect, not only at creation: StrictMode runs it twice on one ref.
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])

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
    if (isFxSplit(answer)) onSplit?.(answer.edited.map((e) => e.id))
    else setWhole(true)
  }

  const editAll = () => {
    if (held != null) lightingApi.fx.updateFx(effect.id, held)
    setHeld(null)
    setWhole(true)
  }

  // × stops what the edit was for: after a split, every copy it follows.
  const stop =
    onStop == null
      ? undefined
      : () => {
          for (const id of alsoIds ?? []) stopFx({ ...effect, id })
          onStop()
        }

  return (
    <div className="flex flex-col gap-2">
      {split != null && (
        <div
          data-fx-split
          className="flex flex-wrap items-center gap-1.5 rounded-md border border-violet-500/40 bg-violet-500/[0.06] px-2 py-1.5 text-[11px]"
        >
          <span className="min-w-0 flex-1 truncate" title={`The selection holds ${split.coveredLabel} of the ${split.heads.length} heads this FX runs on`}>
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
          key={`${effect.id}:${effect.fxType}:${generation}`}
          effect={effect}
          hold={split != null ? setHeld : undefined}
          alsoIds={alsoIds}
          onDone={onDone}
          onStop={stop}
          layout={layout}
        />
      </div>
    </div>
  )
}

const NO_FIXTURES: readonly Fixture[] = []
