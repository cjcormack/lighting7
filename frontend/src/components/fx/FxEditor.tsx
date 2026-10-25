import { useCallback, useEffect, useMemo, useRef, useState, type MutableRefObject } from 'react'
import { toast } from 'sonner'
import { Pause, Play, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { lightingApi } from '@/api/lightingApi'
import type { UpdateFxRequest } from '@/api/fxApi'
import { useFxLibraryQuery, useResetFxToPaletteMutation, type ActiveFx } from '@/store/fixtureFx'
import { useSavePaletteMutation } from '@/store/palettes'
import type { PaletteSummary } from '@/api/palettesApi'
import { useMaster1Uuid } from '@/store/speedMasters'
import { useIsDeskConnected } from '@/store/status'
import { EditorReadout } from '../editor/EditorReadout'
import { EditorFooter } from '../editor/EditorFooter'
import { useLivePush } from '../editor/useLivePush'
import { findFxEntry } from '../busking/buskingTypes'
import { FxFields } from './FxFields'
import { useSpawningPalette } from './useSpawningPalette'
import { useToggleFxPause } from './useFxControls'
import {
  defaultParameters,
  differsFromPalette,
  draftOf,
  isLevelFx,
  withPaletteDraft,
  OPTIONAL_FIELDS,
  sameRequest,
  updateRequestOf,
  type FxDraft,
  type OptionalField,
} from './fxEditorModel'
import { useFxScope } from './useFxScope'
import { useFxGraphLink } from './fxGraphLink'

/**
 * The live effect editor (fixture-fx-sheets plan D11, D12, D12a, D15; §3.3) — the old
 * `EffectParameterForm`'s parameter inputs rebuilt on the editor kit, and the one editor every
 * running effect is edited in: the FX tray's rows (inline), the FX editor's inspector, the FX
 * lens's chips (both through `EditorSurface`), the add sheet after the picker, and the busk
 * view's Effects tab. Its fields (`FxFields`) are `PaletteEditor`'s too, over a draft.
 *
 * **There is no Apply.** Every control writes as it moves through `useLivePush` — a 50 ms floor, a
 * dedupe on the whole write, the release always landing — into `updateFx`, which swaps the
 * parameters and keeps the effect's id and phase. A typed field commits on Enter. **Revert** sends
 * the settings the editor opened with, in one write; **Done** goes back to the host's list and
 * changes nothing; **×** stops the effect.
 *
 * The draft is the editor's own, seeded when it opens: the operator is the only writer while it is
 * open, and a refetch of the effect list (every write answers one) must not pull a slider back
 * under a finger. A host keys the editor on the effect's id *and* type, so the picker's type swap
 * opens a fresh one.
 *
 * Two questions replace the blend for the effects they are about: a movement effect's *Centre*
 * (D12 — Around is Additive with the centre pinned at 128 and hidden, Absolute is Override with the
 * centre shown) and a level effect's *Over the level underneath* (D12a — Replace is Override, Within
 * is Multiply). The blend itself stays under Advanced for every effect, so nothing it could reach
 * before is out of reach now.
 */
export function FxEditor({
  effect,
  onDone,
  onStop,
  flushRef,
  hold,
  alsoIds,
  className,
  layout,
}: {
  effect: ActiveFx
  /** Back to the host's list. */
  onDone: () => void
  /**
   * × — the host stops the effect (and asks first where that is its rule). Absent, no × is drawn:
   * a pad's instance is stopped by pressing its pad off (the busk Effects tab), since stopping the
   * instance alone would leave the layer to respawn it on its next recook.
   */
  onStop?: () => void
  /**
   * Filled with "land whatever move the floor still holds", for a host that acts on the effect as
   * the editor goes — the add sheet absorbing it into a Look on close must write the last move first,
   * or the absorb takes the instance away and the unmount's flush reaches nothing.
   */
  flushRef?: MutableRefObject<(() => void) | null>
  /**
   * Hold every write rather than send it — the split editor's arm while the selection covers some of
   * the effect's heads, whose edit goes out once, with the heads it is for, on *Apply to …*. Told the
   * whole request after each move, and null once Revert has put the draft back.
   */
  hold?: (request: UpdateFxRequest | null) => void
  /**
   * The other copies this edit is for — after a split, one instance per head the edit landed on, each
   * keeping one phase offset, so every write is sent to each as well as to [effect].
   */
  alsoIds?: readonly number[]
  className?: string
  /** `FxFields`' section order: the FX editor's inspector draws its own (FX editor plan §4.5). */
  layout?: 'editor' | 'inspector'
}) {
  const { data: library } = useFxLibraryQuery()
  const entry = useMemo(() => findFxEntry(library, effect.fxType), [library, effect.fxType])
  const connected = useIsDeskConnected()
  const master1Uuid = useMaster1Uuid()

  const [snapshot] = useState(() => draftOf(effect))
  const [draft, setDraft] = useState<FxDraft>(snapshot)
  // The draft as the last write left it, for the graph's handles, which may write twice between renders.
  const draftRef = useRef(draft)
  draftRef.current = draft
  const link = useFxGraphLink()
  const linkRef = useRef(link)
  linkRef.current = link
  // A gesture is running: moves pushed, the release not yet sent.
  const gesture = useRef(false)
  // The Spread section's dials are drawn over the effect's own heads, as the head filter and element
  // mode the draft now says narrow them.
  const { scope: spreadScope, extendedChannels, settingAttribute } = useFxScope({
    target: effect,
    attribute: effect.attribute,
    entry,
    elementMode: draft.elementMode,
    elementFilter: draft.elementFilter,
  })
  const touched = useRef(false)
  /** The optional fields the operator has set — `updateRequestOf` sends only those. */
  const setFields = useRef(new Set<OptionalField>())

  // A write that never left the browser (the socket closed: `updateFx` toasts and answers false) is
  // forgotten as sent, so the next push or release of the same value goes out rather than being
  // deduplicated against a frame the desk never saw.
  const resetRef = useRef<() => void>(() => {})
  const alsoRef = useRef(alsoIds)
  alsoRef.current = alsoIds
  const send = useCallback((request: UpdateFxRequest) => {
    if (!lightingApi.fx.updateFx(effect.id, request)) resetRef.current()
    for (const id of alsoRef.current ?? []) lightingApi.fx.updateFx(id, request)
  }, [effect.id])
  const live = useLivePush(send, { equals: sameRequest })
  resetRef.current = live.reset
  const latest = useRef<UpdateFxRequest | null>(null)
  const holdRef = useRef(hold)
  holdRef.current = hold
  const flushHeld = useCallback(() => {
    if (holdRef.current == null && touched.current && latest.current) live.flush(latest.current)
  }, [live])
  if (flushRef) flushRef.current = flushHeld

  /** A move from a running gesture (`push`) or a release (`flush`). */
  const write = useCallback(
    (next: FxDraft, release: boolean, fields: readonly OptionalField[] = []) => {
      setDraft(next)
      draftRef.current = next
      touched.current = true
      for (const field of fields) setFields.current.add(field)
      const request = updateRequestOf(next, setFields.current)
      // A held write is never the floor's to land: Done, an unmount or a later move must not send
      // to every head what the operator held for some of them.
      if (holdRef.current != null) {
        latest.current = null
        holdRef.current(request)
        return
      }
      latest.current = request
      gesture.current = !release
      linkRef.current?.preview(effect.id, request)
      if (release) live.flush(request)
      else live.push(request)
    },
    [effect.id, live],
  )

  // The running list refetched (the desk answered a write, or something else moved the FX): the
  // preview reads the FX as it runs again — unless a gesture is still running, whose move is newer
  // than any push the desk has answered.
  useEffect(() => {
    if (!gesture.current) linkRef.current?.preview(effect.id, null)
  }, [effect])

  // A move still held by the floor when the editor goes — a Done mid-drag, the host unmounting it —
  // lands rather than being dropped with `useLivePush`'s timer. Declared after the hook, so its
  // cleanup runs after the hook's has cleared the timer, and `flush` dedupes against what was sent.
  const liveRef = useRef(live)
  liveRef.current = live
  useEffect(
    () => () => {
      if (holdRef.current == null && touched.current && latest.current) liveRef.current.flush(latest.current)
    },
    [],
  )

  const revert = () => {
    if (hold != null) {
      setDraft(snapshot)
      setFields.current.clear()
      touched.current = false
      latest.current = null
      hold(null)
      return
    }
    const request = updateRequestOf(snapshot, setFields.current, master1Uuid)
    // A revert onto *Seconds* sends `seconds: true`, which clears both masters on the desk — so the
    // snapshot's (none) is what the rig holds. Otherwise a master the frame could not put back stays
    // drawn as the desk holds it: a never-assigned speed master needs master 1's uuid, which may not
    // have arrived. Drawing the snapshot there would show a state the rig is not in.
    const keepsDrawn = (field: 'speedMasterUuid' | 'rateSpeedMasterUuid') =>
      request.seconds !== true && setFields.current.has(field) && request[field] == null
    setDraft({
      ...snapshot,
      speedMasterUuid: keepsDrawn('speedMasterUuid') ? draft.speedMasterUuid : snapshot.speedMasterUuid,
      rateSpeedMasterUuid: keepsDrawn('rateSpeedMasterUuid') ? draft.rateSpeedMasterUuid : snapshot.rateSpeedMasterUuid,
    })
    latest.current = request
    gesture.current = false
    linkRef.current?.preview(effect.id, request)
    live.flush(request)
  }

  // Lent to the graph's handles while this editor is open, through the same write its fields use —
  // but not while a split edit is held, which the graph cannot draw.
  const levels = entry != null && isLevelFx(entry) && ['min', 'max'].every((p) => entry.parameters.some((d) => d.name === p))
  const libraryDefaults = useMemo(() => (entry ? defaultParameters(entry) : {}), [entry])
  const holding = hold != null
  useEffect(() => {
    const target = linkRef.current
    if (target == null) return
    target.attach({
      fxId: effect.id,
      // An FX carries only the parameters it was given: the rest are the library's.
      draft: { ...draft, parameters: { ...libraryDefaults, ...draft.parameters } },
      levels,
      wallClock: (entry?.timingSource ?? effect.timingSource) === 'WALL_CLOCK',
      readOnly: !connected || holding,
      set: (patch, release) => write({ ...draftRef.current, ...patch }, release, OPTIONAL_FIELDS.filter((field) => field in patch)),
      setParam: (name, value, release) =>
        write({ ...draftRef.current, parameters: { ...draftRef.current.parameters, [name]: value } }, release),
    })
  }, [connected, draft, effect.id, effect.timingSource, entry?.timingSource, holding, levels, libraryDefaults, write])
  useEffect(
    () => () => {
      linkRef.current?.detach(effect.id)
      linkRef.current?.preview(effect.id, null)
    },
    [effect.id],
  )

  const togglePauseFx = useToggleFxPause()
  // Pause and resume what the edit is for: after a split, every copy it follows.
  const togglePause = () => {
    togglePauseFx(effect)
    for (const id of alsoIds ?? []) togglePauseFx({ ...effect, id })
  }

  const set = (patch: Partial<FxDraft>, release = true) =>
    write(
      { ...draft, ...patch },
      release,
      OPTIONAL_FIELDS.filter((field) => field in patch),
    )
  const setParam = (name: string, value: string, release: boolean) =>
    write({ ...draft, parameters: { ...draft.parameters, [name]: value } }, release)

  const readOnly = !connected
  const name = entry?.name ?? effect.fxType
  const dirty = JSON.stringify(draft) !== JSON.stringify(snapshot)
  // A pad's effect (§3.3, D20): edited against its template rather than against the open, and its
  // footer is the template's two verbs in place of Revert.
  const spawning = useSpawningPalette(effect)
  // The instance says its distribution only where it has one (a group, or heads); the operator
  // setting one here says it too.
  const hasOrder = effect.order != null || setFields.current.has('order')
  const edited =
    spawning?.palette.fx != null &&
    differsFromPalette(draft, spawning.palette.fx, master1Uuid, libraryDefaults, { order: hasOrder })
  /** The desk put the instance back (or re-keyed it): the draft is what it now runs. */
  const reseed = useCallback(
    (next: ActiveFx) => {
      live.reset()
      latest.current = null
      touched.current = false
      setDraft(draftOf(next))
    },
    [live],
  )

  return (
    <div data-fx-editor={effect.id} className={cn('flex flex-col gap-3', className)}>
      <div className="flex items-center gap-1.5">
        <span className="min-w-0 truncate text-[13px] font-medium">{name}</span>
        <span className="shrink-0 font-mono text-[11px] text-muted-foreground">→ {effect.attribute}</span>
        {edited && <EditedMark paletteName={spawning?.palette.name} />}
        <span className="flex-1" />
        {/* Pause and resume in every host: the FX lens's chips have no other. */}
        <button
          type="button"
          aria-label={effect.isRunning ? `Pause ${name}` : `Resume ${name}`}
          disabled={readOnly}
          onClick={togglePause}
          className="grid size-6 shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50"
        >
          {effect.isRunning ? <Pause className="size-3.5" /> : <Play className="size-3.5" />}
        </button>
        {onStop != null && (
          <button
            type="button"
            aria-label={`Stop ${name}`}
            disabled={readOnly}
            onClick={() => {
              // A move the floor still holds goes before the stop, never after it — sent from the
              // unmount it would reach a stopped effect and come back as `FX_NOT_FOUND`.
              flushHeld()
              onStop()
            }}
            className="grid size-6 shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-destructive disabled:opacity-50"
          >
            <X className="size-3.5" />
          </button>
        )}
      </div>

      {entry == null ? (
        <EditorReadout>
          {library == null
            ? 'Reading the FX library…'
            : `“${effect.fxType}” is not in this desk's FX library, so its settings cannot be drawn.`}
        </EditorReadout>
      ) : (
        <FxFields
          entry={entry}
          timingSource={effect.timingSource}
          draft={draft}
          scope={spreadScope}
          extendedChannels={extendedChannels}
          settingAttribute={settingAttribute}
          attribute={effect.attribute}
          readOnly={readOnly}
          set={set}
          setParam={setParam}
          layout={layout}
        />
      )}

      <EditorFooter
        note={
          hold != null
            ? 'Held — Apply sends it to the selected heads'
            : spawning != null
              ? 'Live — this running instance only'
              : 'Live — each change lands as you make it'
        }
        // The template's two verbs need more than a 320px sheet has beside the note: they wrap
        // under it, together, rather than running past the editor's edge.
        className={spawning != null ? 'flex-wrap' : undefined}
      >
        {spawning != null ? (
          <PaletteArm
            effect={effect}
            palette={spawning.palette}
            projectId={spawning.projectId}
            draft={draft}
            hasOrder={hasOrder}
            edited={edited}
            readOnly={readOnly}
            flushHeld={flushHeld}
            dropHeld={live.reset}
            reseed={reseed}
          />
        ) : (
          <Button variant="outline" size="sm" className="h-7" disabled={readOnly || !dirty} onClick={revert}>
            Revert
          </Button>
        )}
        <Button
          size="sm"
          className="h-7"
          onClick={() => {
            flushHeld()
            onDone()
          }}
        >
          Done
        </Button>
      </EditorFooter>
    </div>
  )
}

/**
 * *edited* — a pad's running instance differs from its template (D20). Amber, the dot's colour: a
 * press of the pad off and on would put the template's settings back, which is the thing to know.
 */
export function EditedMark({ paletteName: template }: { paletteName?: string }) {
  return (
    <span
      data-edited
      title={template ? `Differs from “${template}” — this running instance only` : 'Differs from its palette'}
      className="inline-flex h-4 shrink-0 items-center rounded border border-amber-500/55 px-1 text-[9.5px] font-medium text-amber-700 dark:text-amber-400"
    >
      edited
    </span>
  )
}

/**
 * The footer for a pad's effect (fixture-fx-sheets plan §3.3, D20), in place of Revert. The edit
 * is **this running instance's only** — the palette is untouched until **Update palette** writes
 * these settings back to it (`PUT` the palette's effect), and **Reset to palette** puts the
 * palette's settings back on the instance, phase kept (W5).
 *
 * Update palette is the PUT **then** W5 on this instance: the PUT alone reaches an applied
 * instance at the stack's next recook by respawning it, phase restarted (session 1's amendment), and
 * the reset re-keys this one to the palette's new entry so the recook keeps it. Every *other*
 * running instance of the palette still holds the old settings, and reads *edited* until it is
 * reset by hand (`FU-TMPL-FX-EDIT-NO-RETIME`).
 */
function PaletteArm({
  effect,
  palette: template,
  projectId,
  draft,
  hasOrder,
  edited,
  readOnly,
  flushHeld,
  dropHeld,
  reseed,
}: {
  effect: ActiveFx
  palette: PaletteSummary
  projectId: number
  draft: FxDraft
  hasOrder: boolean
  edited: boolean
  readOnly: boolean
  flushHeld: () => void
  dropHeld: () => void
  reseed: (next: ActiveFx) => void
}) {
  const [savePalette, { isLoading: saving }] = useSavePaletteMutation()
  const [resetFx, { isLoading: resetting }] = useResetFxToPaletteMutation()
  const busy = saving || resetting

  const update = async () => {
    if (template.fx == null) return
    // The move the floor still holds lands first, so the template takes what the rig shows.
    flushHeld()
    try {
      await savePalette({ projectId, paletteId: template.id, fx: withPaletteDraft(template.fx, draft, { order: hasOrder }) }).unwrap()
      reseed(await resetFx({ id: effect.id }).unwrap())
      toast.success(`Updated “${template.name}” from this ${effect.fxType}`)
    } catch {
      // Reported by errorToastMiddleware.
    }
  }
  const reset = async () => {
    // A held move would land after the reset and edit the instance again.
    dropHeld()
    try {
      reseed(await resetFx({ id: effect.id }).unwrap())
    } catch {
      // Reported by errorToastMiddleware.
    }
  }

  return (
    // Wraps within itself as well as under the note: at the busk sheet's 320px floor the two worded
    // buttons are wider than the editor card's row, so the second takes a line of its own rather
    // than running into the card's padding.
    <div data-template-arm className="ml-auto flex min-w-0 flex-wrap justify-end gap-1.5">
      <Button
        variant="outline"
        size="sm"
        className="h-7"
        disabled={readOnly || busy || !edited}
        title={`Write these settings to “${template.name}” — every pad and cue layering it follows`}
        onClick={() => void update()}
      >
        Update palette
      </Button>
      <Button
        variant="outline"
        size="sm"
        className="h-7"
        disabled={readOnly || busy || !edited}
        title={`Put “${template.name}”'s settings back on this instance, phase kept`}
        onClick={() => void reset()}
      >
        Reset to palette
      </Button>
    </div>
  )
}
