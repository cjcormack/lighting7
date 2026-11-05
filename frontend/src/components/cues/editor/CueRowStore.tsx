import { createContext, useCallback, useContext, useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react'
import { toast } from 'sonner'
import { lookRowKey } from '@/components/programmer/lookRowKey'
import { useDispatch } from 'react-redux'
import type { store } from '@/store'
import { cuesApi, fetchLatestCue, useHardenCueMutation, usePatchProjectCueMutation, usePressCueLayerMutation, useProjectCueCookedQuery, useProjectCueQuery } from '@/store/cues'
import type { HardenResponse } from '@/api/looksApi'
import { PressCueContext, type PressCue } from '@/components/press/pressCue'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { withoutInfrastructure } from '@/lib/infrastructure'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { cueFxInput, cueLayerInput } from '@/lib/cueUtils'
import type {
  CookedRow,
  Cue,
  CueAdHocFx,
  CueLayerDetail,
  CueLayerPressResponse,
  CuePatchInput,
  CueAttributeAssignment,
} from '@/api/cuesApi'
import {
  CueRowDraft,
  applyCueRowEntries,
  canonicalCueAttribute,
  cueRowKey,
  type CueRowTargetType,
} from './cueRowDraft'
import { appendItem, replaceAt } from './cueListEdits'
import { lastIndexOfDraft } from '@/components/fx/draftFx'
import { trackHistoryWrites } from '@/store/restApi'
import type { PaletteReference } from '@/api/paletteReference'
import { onHistoryMoved, undoHistoryRequests } from '@/components/history/historyActions'

/**
 * What one save changed, for its Undo: the rows' keys and the lists it wrote, in the toast's words.
 * The Undo itself is the save's entry in the history, named by its requests (undo plan D11).
 */
interface UndoStep {
  rows?: { keys: readonly string[] }
  layers?: true
  adHocFx?: true
}

/** The writes a save or a layer press sends, which its Undo takes back. */
const CUE_SAVE_ENDPOINTS = ['patchProjectCue', 'pressCueLayer', 'hardenCue']

interface HeldUndo {
  step: UndoStep
  requestIds: string[]
}

/** One of the cue's two other child lists, as the Looks and FX tabs write them. */
export type CueChildList = 'layers' | 'adHocFx'

/**
 * A gesture on one child list: the list as the desk holds it in — a layer with its `id`, an effect
 * with its `uuid`, so a gesture can find the child it was made on — the list to write out (null:
 * nothing to write).
 */
export type CueListEdit<T> = (stored: readonly T[]) => T[] | null

interface ListEdits {
  layers: CueListEdit<CueLayerDetail> | null
  adHocFx: CueListEdit<CueAdHocFx> | null
}

/**
 * Where a composed cell's value came from (show-cue-editing plan D2): one of the cue's **own** rows,
 * or the Look layer that won it. `overrides` names the Look an own value sits over, where this
 * editor has seen one win the cell.
 */
export type CueCellSource =
  | { kind: 'own'; targetType: CueRowTargetType; targetKey: string; overrides?: string; reference?: PaletteReference }
  | { kind: 'layer'; layerId: number | null; name: string | null }

/** One composed value: the canonical assignment string and what said it. */
export interface CueCellValue {
  value: string
  source: CueCellSource
}

export type CueRowSaveState = 'clean' | 'dirty' | 'saving' | 'error'

/**
 * One cue's own rows, in the shape the Values grid reads and writes them (show-cue-editing plan
 * §3.3) — `LookRowStore`'s shape, over a cue.
 *
 * **Server state** is the cue's details (its `attributeAssignments`) and its cook (`/cooked`); the
 * **draft** holds own values the operator set and own rows they took away, notifying per key.
 * Writes go to the draft and schedule a flush — 400 ms of quiet, 2 s at most (D10) — which builds
 * the whole `attributeAssignments` list from the cue **as the desk holds it at flush time** — read
 * past the cache (`fetchLatestCue`), since another client's edit does not invalidate it — keeping
 * each row's fade and sort order, and PATCHes it. A flush sends only the entries no
 * save has carried, and saves run one after another. The PATCH is a stored write and sends nothing
 * to the stage (P2): a live cue lands it on its next first hold.
 *
 * **Undo** is one step per editor, the exact inverse of the last save that landed: the rows it
 * changed put back as they were, every other row as the desk holds it now — from the toast each
 * save raises, or ⌘Z in the editor. A save that failed leaves the previous Undo standing.
 *
 * Every editor mounts its own, so two editors of one cue (two cards, a cue twice in a stack) keep
 * their own drafts and Undo: what they share is the cue, which each re-reads.
 */
export interface CueRowStoreValue {
  projectId: number
  cueId: number
  /** `Q1`, or the cue's name where it has no number — the editors' scope word. */
  cueLabel: string
  /** Locked, or another project's cue: the grid reads and writes nothing. */
  readOnly: boolean
  loaded: boolean
  /** The cook could not be read (another project's cue answers 409): the grid says so rather than waiting. */
  cookFailed: boolean
  draft: CueRowDraft
  /** What the grid draws at one head and property — the draft over the cook. See [lookupCueCell]. */
  lookup: (headKey: string, attribute: string, group?: string) => CueCellValue | undefined
  /**
   * The value a single-axis position edit takes its other axis from: the draft, else the composed
   * cell — never the wire (the Look arm's rule).
   */
  composedValue: (targetType: CueRowTargetType, targetKey: string, attribute: string) => string | undefined
  setValue: (targetType: CueRowTargetType, targetKey: string, attribute: string, value: string) => void
  removeValue: (targetType: CueRowTargetType, targetKey: string, attribute: string) => void
  /** Every head the cue holds an own row on, or a layer composes onto — D14's rows. */
  heads: ReadonlySet<string>
  /** Groups holding an own row of the cue's — drawn as a group row. */
  ownGroups: ReadonlySet<string>
  /** Heads and groups with an own row (stored or pending) — *Own only*. */
  ownTargets: ReadonlySet<string>
  /**
   * A gesture on the cue's layers or effects (the Looks and FX tabs, §3.3): [edit] is applied to the
   * list as the desk holds it at flush time, after any gesture still pending on that list, and the
   * whole list is PATCHed — never `attributeAssignments`. Coalesced with the rows' flush (400 ms,
   * 2 s); [now] saves at once, for a discrete gesture (a reorder, a toggle, an add). Answers
   * whether the write that carried it landed.
   */
  editLayers: (edit: CueListEdit<CueLayerDetail>, now?: boolean) => Promise<boolean>
  editFx: (edit: CueListEdit<CueAdHocFx>, now?: boolean) => Promise<boolean>
  /**
   * A layer or effect gesture is waiting for its flush or its PATCH: a tab drawing its gesture
   * ahead of the desk keeps drawing it until this goes false and the cue is read again.
   */
  listsBusy: boolean
  /** Bumped by every Undo, so an editor holding a draft of its own can start again from the cue. */
  undoSeq: number
  undo: () => void
  canUndo: boolean
  /** Save whatever is pending now. */
  flush: () => void
  /** The cue's own rows that are palette references, by uuid — what *Copy the values* names. */
  referenceRows: readonly CueReferenceRow[]
  /**
   * *Copy the values* (palette-references plan D9): what is pending lands first, then the desk makes
   * the named references, else every one, literal — one write in the chain, and its Undo. Null when
   * it was refused or did not land.
   */
  hardenRows: (rowUuids: string[] | undefined) => Promise<HardenResponse | null>
}

/** One own row of the cue's that follows a palette. */
export interface CueReferenceRow {
  uuid: string
  reference: PaletteReference
  targetType: CueRowTargetType
  targetKey: string
  /** The heads it lands on, as the desk resolved it. */
  heads: readonly string[]
}

const CueRowStoreContext = createContext<CueRowStoreValue | null>(null)
const CueRowSaveStateContext = createContext<CueRowSaveState>('clean')

/** The editor's cue row store, or null outside one. */
export function useCueRowStore(): CueRowStoreValue | null {
  return useContext(CueRowStoreContext)
}

/** Whether the Values grid has edits waiting, in flight, or refused — for the header. */
export function useCueRowSaveState(): CueRowSaveState {
  return useContext(CueRowSaveStateContext)
}

/** Quiet time before a save. */
export const CUE_ROW_SAVE_DEBOUNCE_MS = 400
/** …and the ceiling, so a long drag saves as it goes. */
export const CUE_ROW_SAVE_CEILING_MS = 2000

const EMPTY_ROWS: readonly CookedRow[] = []
const EMPTY_ASSIGNMENTS: readonly CueAttributeAssignment[] = []
const EMPTY_FX: readonly CueAdHocFx[] = []

export function CueRowStoreProvider({
  projectId,
  cueId,
  entryId,
  readOnly,
  children,
}: {
  projectId: number
  cueId: number
  entryId?: number | null
  readOnly: boolean
  children: ReactNode
}) {
  const { data: cue } = useProjectCueQuery({ projectId, cueId, entryId })
  const {
    data: cookedData,
    fulfilledTimeStamp: cookAt,
    isSuccess: cookLoaded,
    isError: cookFailed,
  } = useProjectCueCookedQuery({ projectId, cueId })
  const { fixtures } = useFixtureLookup()
  const [patchCue] = usePatchProjectCueMutation()
  const [pressCueLayer] = usePressCueLayerMutation()
  const [hardenCue] = useHardenCueMutation()
  const dispatch = useDispatch<typeof store.dispatch>()
  const [saveState, setSaveState] = useState<CueRowSaveState>('clean')
  const [canUndo, setCanUndo] = useState(false)
  const toastId = `cue-values-${useId()}`

  // One draft per cue, minted on the change — and the cue it belongs to rides beside it, so a flush
  // on the way out (the teardown below) writes to the cue its edits were made on: `LookRowStore`'s
  // pairing, for its reason.
  const entryRef = useRef<{ cueId: number; draft: CueRowDraft; cue: Cue | undefined }>({
    cueId,
    draft: new CueRowDraft(),
    cue: undefined,
  })
  if (entryRef.current.cueId !== cueId) {
    entryRef.current = { cueId, draft: new CueRowDraft(), cue: undefined }
  }
  const entry = entryRef.current
  if (cue && cue.id === cueId) {
    entry.cue = cue
  }
  const draft = entry.draft

  const timerRef = useRef<number | null>(null)
  const ceilingRef = useRef<number | null>(null)
  const writesRef = useRef(0)
  /** Every save and Undo, one after another: each builds on the cue the one before it left. */
  const chainRef = useRef<Promise<unknown>>(Promise.resolve())
  /** What the last save changed, and the requests that wrote it, for its Undo. */
  const undoRef = useRef<HeldUndo | null>(null)
  /** The layer and effect gestures no flush has carried yet, each list's composed into one edit. */
  const listEditsRef = useRef<ListEdits>({ layers: null, adHocFx: null })
  /** Those gestures' answers — what the flush that takes them settles, and counts back down. */
  const carriedRef = useRef<((landed: boolean) => void)[]>([])
  const cueLabel = cue ? cueLabelOf(cue) : 'Cue'
  const labelRef = useRef(cueLabel)
  labelRef.current = cueLabel
  const readOnlyRef = useRef(readOnly)
  readOnlyRef.current = readOnly
  const argsRef = useRef({ projectId, cueId, entryId })
  argsRef.current = { projectId, cueId, entryId }

  const clearTimers = () => {
    if (timerRef.current != null) window.clearTimeout(timerRef.current)
    if (ceilingRef.current != null) window.clearTimeout(ceilingRef.current)
    timerRef.current = null
    ceilingRef.current = null
  }

  /**
   * One write, queued behind the last: read the cue as the desk holds it now (the cache can be
   * behind — `fetchLatestCue` says why), build the PATCH from it with [build] — any of the rows, the
   * layers and the effects, each its whole list — and send it. The cached cue stands in only when the
   * read fails, so a desk that cannot be read is still written. Answers whether it landed and what
   * [build] said to keep for Undo; null when there was nothing to write.
   *
   * [build] may name its own [send] in place of the PATCH — the layer press, whose answer rides back
   * as `answer`. A refused press is the desk's word on a gesture, toasted already, not a save that
   * failed, so it leaves the save state as it was — and neither outcome clears a failure before it.
   */
  const enqueue = useCallback(
    <U, R = unknown>(build: (latest: Cue) => { body?: CuePatchInput; send?: () => Promise<R>; undo: U } | null) => {
      const writesAtStart = writesRef.current
      const settle = () => setSaveState(writesRef.current === writesAtStart ? 'clean' : 'dirty')
      const settleSend = () =>
        setSaveState((state) => (state === 'error' ? state : writesRef.current === writesAtStart ? 'clean' : 'dirty'))
      const run = async (): Promise<{ ok: boolean; undo?: U; answer?: R; requestIds?: string[] } | null> => {
        const args = { ...argsRef.current, cueId: entry.cueId }
        let latest: Cue | undefined
        try {
          latest = await fetchLatestCue(args)
        } catch {
          latest = entry.cue
        }
        if (!latest) {
          setSaveState('error')
          return { ok: false }
        }
        const built = build(latest)
        if (built == null) {
          settle()
          return null
        }
        if (built.send) setSaveState((state) => (state === 'error' ? state : 'saving'))
        else setSaveState('saving')
        const writes = trackHistoryWrites(CUE_SAVE_ENDPOINTS)
        try {
          const answer = built.send
            ? await built.send()
            : ((await patchCue({ projectId: args.projectId, cueId: args.cueId, ...built.body }).unwrap()) as R)
          if (built.send) settleSend()
          else settle()
          return { ok: true, undo: built.undo, answer, requestIds: writes.stop() }
        } catch {
          writes.stop()
          if (built.send) settleSend()
          else setSaveState('error')
          ignoreReportedError()
          return { ok: false }
        }
      }
      const next = chainRef.current.then(run, run)
      chainRef.current = next
      return next
    },
    [entry, patchCue],
  )

  /** Layer and effect gestures not yet carried by a write that has finished. */
  const [listsInFlight, setListsInFlight] = useState(0)
  const [undoSeq, setUndoSeq] = useState(0)

  // An undo or redo from anywhere in this window — ⌘Z, the header, the History sheet — may have put
  // this cue back from under a save: its saved rows give way to the desk's, and its Undo is spent.
  useEffect(
    () =>
      onHistoryMoved((movedProjectId) => {
        if (movedProjectId !== argsRef.current.projectId) return
        entry.draft.discardSaved()
        undoRef.current = null
        setCanUndo(false)
      }),
    [entry],
  )

  const undo = useCallback(() => {
    const held = undoRef.current
    if (held == null || readOnlyRef.current) return
    // The flush timers stay: an edit to another row, or a gesture on a list, still lands — queued
    // behind this, built on what it puts back.
    undoRef.current = null
    setCanUndo(false)
    toast.dismiss(toastId)
    // Pending edits to the rows the save changed go with it; an edit to any other row still lands.
    if (held.step.rows) draft.discard(held.step.rows.keys)
    setUndoSeq((n) => n + 1)
    const message = `${labelRef.current} · ${wordsFor(held.step)} put back`
    const run = () => undoHistoryRequests(argsRef.current.projectId, held.requestIds, message)
    chainRef.current = chainRef.current.then(run, run)
  }, [draft, toastId])

  const flush = useCallback(() => {
    clearTimers()
    // Only what no save has carried yet: a sent entry is in the cue, or about to be, and sending it
    // again would re-assert it over a later edit of that row from elsewhere.
    const sent = draft.unsaved()
    const { layers: layerEdit, adHocFx: fxEdit } = listEditsRef.current
    listEditsRef.current = { layers: null, adHocFx: null }
    const carried = carriedRef.current
    carriedRef.current = []
    if (sent.size === 0 && layerEdit == null && fxEdit == null) return
    if (sent.size > 0) draft.markSending(sent)
    const keys = [...sent.keys()]
    // One PATCH for everything pending, so one Undo puts back everything it changed.
    void enqueue<UndoStep>((latest) => {
      const body: CuePatchInput = {}
      const step: UndoStep = {}
      if (sent.size > 0) {
        body.attributeAssignments = applyCueRowEntries(latest.attributeAssignments, sent)
        step.rows = { keys }
      }
      const layers = layerEdit?.(latest.layers) ?? null
      if (layers != null) {
        body.layers = layers.map(cueLayerInput)
        step.layers = true
      }
      const effects = fxEdit?.(latest.adHocFx) ?? null
      if (effects != null) {
        body.adHocFx = effects.map(cueFxInput)
        step.adHocFx = true
      }
      return Object.keys(body).length === 0 ? null : { body, undo: step }
    }).then((result) => {
      if (carried.length > 0) setListsInFlight((n) => n - carried.length)
      for (const answer of carried) answer(result?.ok === true)
      if (result == null) return
      if (!result.ok) {
        if (sent.size > 0) draft.markFailed(sent)
        return
      }
      if (sent.size > 0) draft.markSaved(sent, Date.now())
      // Undo is set only by a save that landed, and names exactly what it changed.
      const step = result.undo!
      undoRef.current = { step, requestIds: result.requestIds ?? [] }
      setCanUndo(true)
      toast(`${labelRef.current} · ${wordsFor(step)} saved`, {
        id: toastId,
        action: { label: 'Undo', onClick: () => undo() },
      })
    })
  }, [draft, enqueue, toastId, undo])

  // Read through a ref by the timers and the teardown, so neither holds a stale `flush` — and, for
  // the teardown, so a new `flush` identity is not an unmount: an effect keyed on it would run its
  // cleanup, and so save, on every change of the mutation trigger or the toast's Undo.
  const flushRef = useRef(flush)
  flushRef.current = flush
  const schedule = useCallback(() => {
    writesRef.current += 1
    setSaveState('dirty')
    if (timerRef.current != null) window.clearTimeout(timerRef.current)
    timerRef.current = window.setTimeout(() => flushRef.current(), CUE_ROW_SAVE_DEBOUNCE_MS)
    if (ceilingRef.current == null) ceilingRef.current = window.setTimeout(() => flushRef.current(), CUE_ROW_SAVE_CEILING_MS)
  }, [])

  const setValue = useCallback(
    (targetType: CueRowTargetType, targetKey: string, attribute: string, value: string) => {
      if (readOnlyRef.current) return
      draft.set(targetType, targetKey, attribute, value)
      schedule()
    },
    [draft, schedule],
  )
  const removeValue = useCallback(
    (targetType: CueRowTargetType, targetKey: string, attribute: string) => {
      if (readOnlyRef.current) return
      const key = cueRowKey(targetType, targetKey, attribute)
      const stored = (entry.cue?.attributeAssignments ?? []).some(
        (row) => cueRowKey(row.targetType, row.targetKey, row.attribute) === key,
      )
      if (!stored && !draft.isSent(key)) {
        // No own row to take away: ⌫ over a Look-won cell, or over a value set here and not yet
        // saved, which is simply forgotten. Nothing to write, and no toast to say it was saved.
        draft.discard([key])
        return
      }
      draft.remove(targetType, targetKey, attribute)
      schedule()
    },
    [draft, entry, schedule],
  )

  const editList = useCallback(
    <T,>(kind: CueChildList, edit: CueListEdit<T>, now: boolean) => {
      if (readOnlyRef.current) return Promise.resolve(false)
      const pending = listEditsRef.current[kind] as CueListEdit<T> | null
      // Composed rather than replaced: two gestures inside one flush window both land, the second
      // applied to the first's answer.
      const composed: CueListEdit<T> =
        pending == null ? edit : (stored) => {
          const first = pending(stored)
          return edit(first ?? [...stored])
        }
      ;(listEditsRef.current as Record<CueChildList, CueListEdit<T> | null>)[kind] = composed
      const landed = new Promise<boolean>((resolve) => carriedRef.current.push(resolve))
      setListsInFlight((n) => n + 1)
      schedule()
      if (now) flushRef.current()
      return landed
    },
    [schedule],
  )
  const editLayers = useCallback(
    (edit: CueListEdit<CueLayerDetail>, now = false) => editList('layers', edit, now),
    [editList],
  )
  const editFx = useCallback(
    (edit: CueListEdit<CueAdHocFx>, now = false) => editList('adHocFx', edit, now),
    [editList],
  )

  /**
   * A palette or a Look pressed into the cue's layers (apply-model plan §3.6): whatever is pending
   * lands first, then the press goes in the same chain and is one Undo — the layers as they stood.
   * The desk's answer is the cue as stored, and its layers land in the cache at once.
   */
  const pressLayer = useCallback<PressCue['pressLayer']>(
    async (source) => {
      if (readOnlyRef.current) return null
      flushRef.current()
      const args = { ...argsRef.current, cueId: entry.cueId }
      const pressed =
        'lookId' in source
          ? { lookId: source.lookId }
          : {
              paletteId: source.paletteId,
              targets: source.targets.map((t) => ({ type: t.type, key: t.key })),
              ...(source.families != null && source.families.length > 0 ? { families: [...source.families] } : {}),
            }
      const result = await enqueue<UndoStep, CueLayerPressResponse>(() => ({
        send: () => pressCueLayer({ projectId: args.projectId, cueId: args.cueId, ...pressed }).unwrap(),
        undo: { layers: true },
      }))
      if (result == null || !result.ok || result.answer == null) return null
      const answer = result.answer
      dispatch(
        cuesApi.util.updateQueryData('projectCue', args, (draftCue) => {
          draftCue.layers = answer.cue.layers
        }),
      )
      undoRef.current = { step: result.undo!, requestIds: result.requestIds ?? [] }
      setCanUndo(true)
      toast(`${labelRef.current} · looks saved`, { id: toastId, action: { label: 'Undo', onClick: () => undo() } })
      return answer
    },
    [entry, enqueue, pressCueLayer, dispatch, toastId, undo],
  )

  const hardenRows = useCallback<CueRowStoreValue['hardenRows']>(
    async (rowUuids) => {
      if (readOnlyRef.current) return null
      flushRef.current()
      const args = { ...argsRef.current, cueId: entry.cueId }
      const result = await enqueue<UndoStep, HardenResponse>(() => ({
        send: () => hardenCue({ projectId: args.projectId, cueId: args.cueId, ...(rowUuids ? { rowUuids } : {}) }).unwrap(),
        undo: { rows: { keys: [] } },
      }))
      if (result == null || !result.ok || result.answer == null) return null
      if (result.answer.hardened > 0) {
        undoRef.current = { step: result.undo!, requestIds: result.requestIds ?? [] }
        setCanUndo(true)
      }
      return result.answer
    },
    [entry, enqueue, hardenCue],
  )

  /** *Apply as values* in the cue: each literal an own row, as a cell commit lands one. */
  const landValues = useCallback<PressCue['landValues']>(
    (values) => {
      if (readOnlyRef.current) return 0
      for (const value of values) setValue(value.target.type === 'group' ? 'group' : 'fixture', value.target.key, value.attribute, value.value)
      return values.length
    },
    [setValue],
  )

  /** A bare effect stored in draft — appended, or in place of the one the picker is auditioning. */
  const pickFx = useCallback<PressCue['pickFx']>(
    (effect, replacing) =>
      editList<CueAdHocFx>(
        'adHocFx',
        (stored) => {
          const at = replacing == null ? -1 : lastIndexOfDraft(stored, replacing)
          return at < 0 ? appendItem(stored, effect) : replaceAt(stored, at, effect)
        },
        true,
      ),
    [editList],
  )

  // Retire what the server now states, once a cook read after the save has landed.
  useEffect(() => {
    if (!cue || cookAt == null) return
    draft.reconcile(cue.attributeAssignments, cookAt)
  }, [cue, cookAt, draft])

  // A pending edit lands when the editor goes — a card collapsing on a GO from elsewhere, a pane
  // folding, a page left — as the header's fields do with `commitOnUnmount`.
  useEffect(() => {
    const onLeave = () => flushRef.current()
    window.addEventListener('beforeunload', onLeave)
    return () => {
      window.removeEventListener('beforeunload', onLeave)
      flushRef.current()
    }
  }, [])

  const rows = cookedData?.rows ?? EMPTY_ROWS
  const assignments = cue?.attributeAssignments ?? EMPTY_ASSIGNMENTS
  const referenceRows = useMemo<CueReferenceRow[]>(
    () =>
      assignments.flatMap((row) =>
        row.reference != null && row.uuid != null
          ? [
              {
                uuid: row.uuid,
                reference: row.reference,
                targetType: row.targetType,
                targetKey: row.targetKey,
                heads: Object.keys(row.resolvedByHead ?? {}),
              },
            ]
          : [],
      ),
    [assignments],
  )
  const cookedByKey = useMemo(() => {
    const out = new Map<string, CookedRow>()
    for (const row of rows) out.set(lookRowKey(row.targetKey, canonicalCueAttribute(row.attribute)), row)
    return out
  }, [rows])

  // The Look that last won each cell, while this editor has been open — what an own value typed
  // over it *overrides*. The cook keeps only the winner, so once the own row is saved the Look under
  // it is no longer said anywhere but here.
  const overriddenRef = useRef(new Map<string, string>())
  for (const row of rows) {
    if (row.layerId != null && row.layerSource?.name) {
      overriddenRef.current.set(lookRowKey(row.targetKey, canonicalCueAttribute(row.attribute)), row.layerSource.name)
    }
  }

  const groupsByHead = useMemo(() => {
    const out = new Map<string, readonly string[]>()
    for (const fixture of fixtures ?? []) out.set(fixture.key, fixture.groups)
    return out
  }, [fixtures])

  // A layer's group targets bring the members the rig shows, never an infrastructure fixture (a
  // dimmer on hard power): `useFixtureLookup` is the raw list, kept raw for the keys it resolves.
  const visibleFixtures = useMemo(() => withoutInfrastructure(fixtures), [fixtures])

  // Which targets hold a pending own value — the rows *Own only* and the heads read. A value typed
  // over an existing row changes no shape, so a keystroke re-renders nothing through it.
  const [draftShape, setDraftShape] = useState('')
  useEffect(() => {
    const read = () => {
      const keys: string[] = []
      for (const [key, pending] of draft.snapshot()) if (pending.kind === 'set') keys.push(key)
      const shape = keys.sort().join('\u0001')
      setDraftShape((prev) => (prev === shape ? prev : shape))
    }
    read()
    return draft.subscribeAll(read)
  }, [draft])

  // The cue's stored rows by target and canonical attribute — a group's own row is drawn from here — and
  // each reference row's palette by uuid, which a composed own value's `reference` names.
  const { storedRows, landedGroupRows, referenceByUuid } = useMemo(() => {
    const rowsByKey = new Map<string, CueAttributeAssignment>()
    // A group reference landing under another property on a head (a colour wheel), by group, head and that property.
    const landed = new Map<string, CueAttributeAssignment>()
    const byUuid = new Map<string, PaletteReference>()
    for (const row of assignments) {
      rowsByKey.set(cueRowKey(row.targetType, row.targetKey, canonicalCueAttribute(row.attribute)), row)
      if (row.targetType === 'group') {
        for (const [head, attribute] of Object.entries(row.resolvedAttributeByHead ?? {})) {
          landed.set(landedKey(row.targetKey, head, attribute), row)
        }
      }
      if (row.reference != null) byUuid.set(row.reference.paletteUuid, row.reference)
    }
    return { storedRows: rowsByKey, landedGroupRows: landed, referenceByUuid: byUuid }
  }, [assignments])

  const value = useMemo<CueRowStoreValue>(() => {
    // The heads and own targets below read the draft's set entries; `draftShape` is what changes
    // when those do, so the memo is rebuilt then and not on every keystroke.
    void draftShape
    const own = (
      targetType: CueRowTargetType,
      targetKey: string,
      v: string,
      cellKey: string,
      reference?: PaletteReference,
    ): CueCellValue => ({
      value: v,
      source: { kind: 'own', targetType, targetKey, overrides: overriddenRef.current.get(cellKey), ...(reference ? { reference } : {}) },
    })
    /** A stored row's value on one head: a reference's resolution there (the desk's, never ours), else the literal. */
    const storedValueOn = (row: CueAttributeAssignment, headKey: string): string | undefined =>
      row.reference != null ? row.resolvedByHead?.[headKey] : row.value
    /** A group's own row on one head and property: by its own property, else a reference landing there. */
    const storedGroupRow = (group: string, headKey: string, attribute: string): CueAttributeAssignment | undefined =>
      storedRows.get(cueRowKey('group', group, attribute)) ?? landedGroupRows.get(landedKey(group, headKey, attribute))
    /** The palette a composed own value came from: the cook names its uuid (palette-references plan D18). */
    const referenceOf = (cooked: CookedRow): PaletteReference | undefined =>
      cooked.reference != null ? referenceByUuid.get(cooked.reference) : undefined
    const lookup = (headKey: string, rawAttribute: string, group?: string): CueCellValue | undefined => {
      const attribute = canonicalCueAttribute(rawAttribute)
      const cellKey = lookRowKey(headKey, attribute)
      if (group != null) {
        // A group row draws the group's **own** row and nothing else: what its members compose to is
        // on their own rows.
        const pending = draft.get('group', group, attribute)
        if (pending?.kind === 'removed') return undefined
        if (pending?.kind === 'set') return own('group', group, pending.value, cellKey)
        const stored = storedGroupRow(group, headKey, attribute)
        const storedValue = stored == null ? undefined : storedValueOn(stored, headKey)
        return stored == null || storedValue == null ? undefined : own('group', group, storedValue, cellKey, stored.reference)
      }
      const pending = draft.get('fixture', headKey, attribute)
      if (pending?.kind === 'set') return own('fixture', headKey, pending.value, cellKey)
      const cooked = cookedByKey.get(cellKey)
      const sourceType = cooked?.sourceTargetType ?? (cooked ? (cooked.targetType === 'group' ? 'group' : 'fixture') : null)
      const sourceKey = cooked?.sourceTargetKey ?? cooked?.targetKey
      const cookedOwn = cooked != null && cooked.layerId == null
      if (cookedOwn && sourceType === 'fixture' && pending?.kind !== 'removed') {
        return own('fixture', sourceKey ?? headKey, cooked.value, cellKey, referenceOf(cooked))
      }
      // A group's own row reaches its members unless a fixture row of their own beats it.
      for (const name of groupsByHead.get(headKey) ?? []) {
        const groupPending = draft.get('group', name, attribute)
        if (groupPending?.kind === 'set') return own('group', name, groupPending.value, cellKey)
      }
      if (cookedOwn && sourceType === 'group' && sourceKey != null) {
        if (draft.get('group', sourceKey, attribute)?.kind !== 'removed') {
          return own('group', sourceKey, cooked.value, cellKey, referenceOf(cooked))
        }
        return undefined
      }
      if (cooked != null && cooked.layerId != null) {
        return { value: cooked.value, source: { kind: 'layer', layerId: cooked.layerId, name: cooked.layerSource?.name ?? null } }
      }
      return undefined
    }
    const composedValue = (targetType: CueRowTargetType, targetKey: string, rawAttribute: string): string | undefined => {
      const attribute = canonicalCueAttribute(rawAttribute)
      const pending = draft.get(targetType, targetKey, attribute)
      if (pending?.kind === 'set') return pending.value
      if (targetType === 'group') {
        const member = (fixtures ?? []).find((f) => f.groups.includes(targetKey))
        const stored = storedRows.get(cueRowKey('group', targetKey, attribute))
        if (stored != null) return member ? storedValueOn(stored, member.key) : stored.reference == null ? stored.value : undefined
        return member ? cookedByKey.get(lookRowKey(member.key, attribute))?.value : undefined
      }
      return cookedByKey.get(lookRowKey(targetKey, attribute))?.value
    }

    const heads = new Set<string>()
    const ownGroups = new Set<string>()
    const ownTargets = new Set<string>()
    for (const row of rows) {
      heads.add(row.targetKey)
      if (row.layerId == null) ownTargets.add(row.targetKey)
    }
    for (const row of assignments) {
      if (row.targetType === 'group') {
        ownGroups.add(row.targetKey)
        ownTargets.add(row.targetKey)
      } else {
        heads.add(row.targetKey)
        ownTargets.add(row.targetKey)
      }
    }
    for (const [key, entry] of draft.snapshot()) {
      if (entry.kind !== 'set') continue
      const [targetType, targetKey] = key.split('\u0000')
      ownTargets.add(targetKey)
      if (targetType === 'group') ownGroups.add(targetKey)
      else heads.add(targetKey)
    }
    // A layer's own targets bring their fixtures even where the layer composes nothing on them.
    for (const layer of cue?.layers ?? []) {
      for (const target of layer.targets ?? []) {
        if (target.type === 'fixture') heads.add(target.key)
        else for (const fixture of visibleFixtures ?? []) if (fixture.groups.includes(target.key)) heads.add(fixture.key)
      }
    }

    return {
      projectId,
      cueId,
      cueLabel,
      readOnly,
      loaded: cue != null && cookLoaded,
      cookFailed: cookFailed === true,
      draft,
      lookup,
      composedValue,
      setValue,
      removeValue,
      heads,
      ownGroups,
      ownTargets,
      editLayers,
      editFx,
      listsBusy: listsInFlight > 0,
      undoSeq,
      undo,
      canUndo,
      flush,
      referenceRows,
      hardenRows,
    }
    // `saveState` is not read here: it rides its own context so a keystroke does not re-render
    // every row. `rows`/`assignments` stand for the cook and the cue; the draft is a stable object
    // whose own subscription wakes the cells.
  }, [
    projectId,
    cueId,
    cueLabel,
    readOnly,
    cue,
    cookLoaded,
    cookFailed,
    draftShape,
    visibleFixtures,
    draft,
    rows,
    assignments,
    cookedByKey,
    storedRows,
    landedGroupRows,
    referenceByUuid,
    groupsByHead,
    fixtures,
    setValue,
    removeValue,
    editLayers,
    editFx,
    listsInFlight,
    undoSeq,
    undo,
    canUndo,
    flush,
    referenceRows,
    hardenRows,
  ])

  // Keyed on what the bands read, not on the cue: a value save refetches the cue, and every cell in
  // the grid reads this context.
  // A value palette pressed into the cue is its reference rows, not a layer (lighting7 palette-references plan D13).
  const layeredKey = [
    ...(cue?.layers ?? []).flatMap((layer) => (layer.source ? [`${layer.source.kind}:${layer.source.id}`] : [])),
    ...(cue?.attributeAssignments ?? []).flatMap((row) => (row.reference != null ? [`PALETTE:${row.reference.paletteId}`] : [])),
  ].join('|')
  const effects = cue?.adHocFx ?? EMPTY_FX
  const pressCue = useMemo<PressCue>(
    () => ({
      projectId,
      cueId,
      cueLabel,
      readOnly,
      layered: new Set(layeredKey === '' ? [] : layeredKey.split('|')),
      effects,
      pressLayer,
      landValues,
      pickFx,
    }),
    [projectId, cueId, cueLabel, readOnly, layeredKey, effects, pressLayer, landValues, pickFx],
  )

  return (
    <CueRowStoreContext.Provider value={value}>
      <CueRowSaveStateContext.Provider value={saveState}>
        <PressCueContext.Provider value={pressCue}>{children}</PressCueContext.Provider>
      </CueRowSaveStateContext.Provider>
    </CueRowStoreContext.Provider>
  )
}

/** What a save or its Undo changed, in the toast's words: `values`, `looks and FX`. */
function wordsFor(step: UndoStep): string {
  const words = [step.rows && 'values', step.layers && 'looks', step.adHocFx && 'FX'].filter((w): w is string => !!w)
  return words.length <= 1 ? (words[0] ?? 'values') : `${words.slice(0, -1).join(', ')} and ${words[words.length - 1]}`
}

function cueLabelOf(cue: Cue): string {
  return cue.cueNumber ? `Q${cue.cueNumber}` : cue.name
}

const landedKey = (group: string, head: string, attribute: string) => `${group}\u0000${head}\u0000${attribute}`
