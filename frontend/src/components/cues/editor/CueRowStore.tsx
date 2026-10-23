import { createContext, useCallback, useContext, useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react'
import { toast } from 'sonner'
import { lookRowKey } from '@/components/programmer/lookRowKey'
import { useDispatch } from 'react-redux'
import type { store } from '@/store'
import { cuesApi, fetchLatestCue, usePatchProjectCueMutation, usePressCueLayerMutation, useProjectCueCookedQuery, useProjectCueQuery } from '@/store/cues'
import { PressCueContext, type PressCue } from '@/components/press/pressCue'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { withoutInfrastructure } from '@/lib/infrastructure'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { cueEffectInput, cueLayerInput } from '@/lib/cueUtils'
import type {
  CookedRow,
  Cue,
  CueAdHocEffect,
  CueLayer,
  CueLayerDetail,
  CueLayerPressResponse,
  CuePatchInput,
  CuePropertyAssignment,
} from '@/api/cuesApi'
import {
  CueRowDraft,
  applyCueRowEntries,
  canonicalCueProperty,
  cueRowKey,
  restoreCueRows,
  rowsWithKeys,
  type CueRowTargetType,
} from './cueRowDraft'
import { appendItem, replaceAt } from './cueListEdits'
import { lastIndexOfDraft } from '@/components/fx/draftEffect'

/**
 * What one save changed, for its Undo: the rows' keys and the rows the cue held under them before
 * it, and the whole layer or effect list as it stood before, for each list the save wrote.
 */
interface UndoStep {
  rows?: { keys: readonly string[]; rows: readonly CuePropertyAssignment[] }
  layers?: readonly CueLayer[]
  adHocEffects?: readonly CueAdHocEffect[]
}

/** One of the cue's two other child lists, as the Looks and FX tabs write them. */
export type CueChildList = 'layers' | 'adHocEffects'

/**
 * A gesture on one child list: the list as the desk holds it in — a layer with its `id`, an effect
 * with its `uuid`, so a gesture can find the child it was made on — the list to write out (null:
 * nothing to write).
 */
export type CueListEdit<T> = (stored: readonly T[]) => T[] | null

interface ListEdits {
  layers: CueListEdit<CueLayerDetail> | null
  adHocEffects: CueListEdit<CueAdHocEffect> | null
}

/**
 * Where a composed cell's value came from (show-cue-editing plan D2): one of the cue's **own** rows,
 * or the Look layer that won it. `overrides` names the Look an own value sits over, where this
 * editor has seen one win the cell.
 */
export type CueCellSource =
  | { kind: 'own'; targetType: CueRowTargetType; targetKey: string; overrides?: string }
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
 * **Server state** is the cue's details (its `propertyAssignments`) and its cook (`/cooked`); the
 * **draft** holds own values the operator set and own rows they took away, notifying per key.
 * Writes go to the draft and schedule a flush — 400 ms of quiet, 2 s at most (D10) — which builds
 * the whole `propertyAssignments` list from the cue **as the desk holds it at flush time** — read
 * past the cache (`fetchLatestCue`), since another client's edit does not invalidate it — keeping
 * each row's fade, move-in-dark and sort order, and PATCHes it. A flush sends only the entries no
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
  lookup: (headKey: string, propertyName: string, group?: string) => CueCellValue | undefined
  /**
   * The value a single-axis position edit takes its other axis from: the draft, else the composed
   * cell — never the wire (the Look arm's rule).
   */
  composedValue: (targetType: CueRowTargetType, targetKey: string, propertyName: string) => string | undefined
  setValue: (targetType: CueRowTargetType, targetKey: string, propertyName: string, value: string) => void
  removeValue: (targetType: CueRowTargetType, targetKey: string, propertyName: string) => void
  /** Every head the cue holds an own row on, or a layer composes onto — D14's rows. */
  heads: ReadonlySet<string>
  /** Groups holding an own row of the cue's — drawn as a group row. */
  ownGroups: ReadonlySet<string>
  /** Heads and groups with an own row (stored or pending) — *Own only*. */
  ownTargets: ReadonlySet<string>
  /**
   * A gesture on the cue's layers or effects (the Looks and FX tabs, §3.3): [edit] is applied to the
   * list as the desk holds it at flush time, after any gesture still pending on that list, and the
   * whole list is PATCHed — never `propertyAssignments`. Coalesced with the rows' flush (400 ms,
   * 2 s); [now] saves at once, for a discrete gesture (a reorder, a toggle, an add). Answers
   * whether the write that carried it landed.
   */
  editLayers: (edit: CueListEdit<CueLayerDetail>, now?: boolean) => Promise<boolean>
  editEffects: (edit: CueListEdit<CueAdHocEffect>, now?: boolean) => Promise<boolean>
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
const EMPTY_ASSIGNMENTS: readonly CuePropertyAssignment[] = []
const EMPTY_EFFECTS: readonly CueAdHocEffect[] = []

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
  /** What the last save changed — the rows' keys and the rows they replaced — for its Undo. */
  const undoRef = useRef<UndoStep | null>(null)
  /** The layer and effect gestures no flush has carried yet, each list's composed into one edit. */
  const listEditsRef = useRef<ListEdits>({ layers: null, adHocEffects: null })
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
      const run = async (): Promise<{ ok: boolean; undo?: U; answer?: R } | null> => {
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
        try {
          const answer = built.send
            ? await built.send()
            : ((await patchCue({ projectId: args.projectId, cueId: args.cueId, ...built.body }).unwrap()) as R)
          if (built.send) settleSend()
          else settle()
          return { ok: true, undo: built.undo, answer }
        } catch {
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

  const undo = useCallback(() => {
    const step = undoRef.current
    if (step == null || readOnlyRef.current) return
    // The flush timers stay: an edit to another row, or a gesture on a list, still lands — queued
    // behind this, built on what it puts back.
    undoRef.current = null
    setCanUndo(false)
    toast.dismiss(toastId)
    // Pending edits to the rows the save changed go with it; an edit to any other row still lands.
    if (step.rows) draft.discard(step.rows.keys)
    setUndoSeq((n) => n + 1)
    void enqueue((latest) => ({
      body: {
        ...(step.rows ? { propertyAssignments: restoreCueRows(latest.propertyAssignments, step.rows.keys, step.rows.rows) } : {}),
        ...(step.layers ? { layers: [...step.layers] } : {}),
        ...(step.adHocEffects ? { adHocEffects: [...step.adHocEffects] } : {}),
      },
      undo: null,
    })).then((result) => {
      if (result?.ok) toast(`${labelRef.current} · ${wordsFor(step)} put back`, { id: toastId })
    })
  }, [draft, enqueue, toastId])

  const flush = useCallback(() => {
    clearTimers()
    // Only what no save has carried yet: a sent entry is in the cue, or about to be, and sending it
    // again would re-assert it over a later edit of that row from elsewhere.
    const sent = draft.unsaved()
    const { layers: layerEdit, adHocEffects: effectEdit } = listEditsRef.current
    listEditsRef.current = { layers: null, adHocEffects: null }
    const carried = carriedRef.current
    carriedRef.current = []
    if (sent.size === 0 && layerEdit == null && effectEdit == null) return
    if (sent.size > 0) draft.markSending(sent)
    const keys = [...sent.keys()]
    // One PATCH for everything pending, so one Undo puts back everything it changed.
    void enqueue<UndoStep>((latest) => {
      const body: CuePatchInput = {}
      const step: UndoStep = {}
      if (sent.size > 0) {
        body.propertyAssignments = applyCueRowEntries(latest.propertyAssignments, sent)
        step.rows = { keys, rows: rowsWithKeys(latest.propertyAssignments, keys) }
      }
      const layers = layerEdit?.(latest.layers) ?? null
      if (layers != null) {
        body.layers = layers.map(cueLayerInput)
        step.layers = latest.layers.map(cueLayerInput)
      }
      const effects = effectEdit?.(latest.adHocEffects) ?? null
      if (effects != null) {
        body.adHocEffects = effects.map(cueEffectInput)
        step.adHocEffects = latest.adHocEffects.map(cueEffectInput)
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
      undoRef.current = step
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
    (targetType: CueRowTargetType, targetKey: string, propertyName: string, value: string) => {
      if (readOnlyRef.current) return
      draft.set(targetType, targetKey, propertyName, value)
      schedule()
    },
    [draft, schedule],
  )
  const removeValue = useCallback(
    (targetType: CueRowTargetType, targetKey: string, propertyName: string) => {
      if (readOnlyRef.current) return
      const key = cueRowKey(targetType, targetKey, propertyName)
      const stored = (entry.cue?.propertyAssignments ?? []).some(
        (row) => cueRowKey(row.targetType, row.targetKey, row.propertyName) === key,
      )
      if (!stored && !draft.isSent(key)) {
        // No own row to take away: ⌫ over a Look-won cell, or over a value set here and not yet
        // saved, which is simply forgotten. Nothing to write, and no toast to say it was saved.
        draft.discard([key])
        return
      }
      draft.remove(targetType, targetKey, propertyName)
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
  const editEffects = useCallback(
    (edit: CueListEdit<CueAdHocEffect>, now = false) => editList('adHocEffects', edit, now),
    [editList],
  )

  /**
   * A palette or a Look pressed into the cue's layers (apply-model plan §3.6): whatever is pending
   * lands first, then the press goes in the same chain and is one Undo — the layers as they stood.
   * The desk's answer is the cue as stored, and its layers land in the cache at once.
   */
  const pressLayer = useCallback<PressCue['pressLayer']>(
    async (source, targets, families) => {
      if (readOnlyRef.current) return null
      flushRef.current()
      const args = { ...argsRef.current, cueId: entry.cueId }
      const result = await enqueue<UndoStep, CueLayerPressResponse>((latest) => ({
        send: () =>
          pressCueLayer({
            projectId: args.projectId,
            cueId: args.cueId,
            ...source,
            targets: targets.map((t) => ({ type: t.type, key: t.key })),
            ...(families != null && families.length > 0 ? { families: [...families] } : {}),
          }).unwrap(),
        undo: { layers: latest.layers.map(cueLayerInput) },
      }))
      if (result == null || !result.ok || result.answer == null) return null
      const answer = result.answer
      dispatch(
        cuesApi.util.updateQueryData('projectCue', args, (draftCue) => {
          draftCue.layers = answer.cue.layers
        }),
      )
      undoRef.current = result.undo!
      setCanUndo(true)
      toast(`${labelRef.current} · looks saved`, { id: toastId, action: { label: 'Undo', onClick: () => undo() } })
      return answer
    },
    [entry, enqueue, pressCueLayer, dispatch, toastId, undo],
  )

  /** *Apply as values* in the cue: each literal an own row, as a cell commit lands one. */
  const landValues = useCallback<PressCue['landValues']>(
    (values) => {
      if (readOnlyRef.current) return 0
      for (const value of values) setValue(value.target.type === 'group' ? 'group' : 'fixture', value.target.key, value.propertyName, value.value)
      return values.length
    },
    [setValue],
  )

  /** A bare effect stored in draft — appended, or in place of the one the picker is auditioning. */
  const pickEffect = useCallback<PressCue['pickEffect']>(
    (effect, replacing) =>
      editList<CueAdHocEffect>(
        'adHocEffects',
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
    draft.reconcile(cue.propertyAssignments, cookAt)
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
  const assignments = cue?.propertyAssignments ?? EMPTY_ASSIGNMENTS
  const cookedByKey = useMemo(() => {
    const out = new Map<string, CookedRow>()
    for (const row of rows) out.set(lookRowKey(row.targetKey, canonicalCueProperty(row.propertyName)), row)
    return out
  }, [rows])

  // The Look that last won each cell, while this editor has been open — what an own value typed
  // over it *overrides*. The cook keeps only the winner, so once the own row is saved the Look under
  // it is no longer said anywhere but here.
  const overriddenRef = useRef(new Map<string, string>())
  for (const row of rows) {
    if (row.layerId != null && row.layerSource?.name) {
      overriddenRef.current.set(lookRowKey(row.targetKey, canonicalCueProperty(row.propertyName)), row.layerSource.name)
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

  const storedGroupRows = useMemo(() => {
    const out = new Map<string, string>()
    for (const row of assignments) {
      if (row.targetType === 'group') out.set(cueRowKey('group', row.targetKey, row.propertyName), row.value)
    }
    return out
  }, [assignments])

  const value = useMemo<CueRowStoreValue>(() => {
    // The heads and own targets below read the draft's set entries; `draftShape` is what changes
    // when those do, so the memo is rebuilt then and not on every keystroke.
    void draftShape
    const own = (targetType: CueRowTargetType, targetKey: string, v: string, cellKey: string): CueCellValue => ({
      value: v,
      source: { kind: 'own', targetType, targetKey, overrides: overriddenRef.current.get(cellKey) },
    })
    const lookup = (headKey: string, rawProperty: string, group?: string): CueCellValue | undefined => {
      const propertyName = canonicalCueProperty(rawProperty)
      const cellKey = lookRowKey(headKey, propertyName)
      if (group != null) {
        // A group row draws the group's **own** row and nothing else: what its members compose to is
        // on their own rows.
        const pending = draft.get('group', group, propertyName)
        if (pending?.kind === 'removed') return undefined
        if (pending?.kind === 'set') return own('group', group, pending.value, cellKey)
        const stored = storedGroupRows.get(cueRowKey('group', group, propertyName))
        return stored == null ? undefined : own('group', group, stored, cellKey)
      }
      const pending = draft.get('fixture', headKey, propertyName)
      if (pending?.kind === 'set') return own('fixture', headKey, pending.value, cellKey)
      const cooked = cookedByKey.get(cellKey)
      const sourceType = cooked?.sourceTargetType ?? (cooked ? (cooked.targetType === 'group' ? 'group' : 'fixture') : null)
      const sourceKey = cooked?.sourceTargetKey ?? cooked?.targetKey
      const cookedOwn = cooked != null && cooked.layerId == null
      if (cookedOwn && sourceType === 'fixture' && pending?.kind !== 'removed') {
        return own('fixture', sourceKey ?? headKey, cooked.value, cellKey)
      }
      // A group's own row reaches its members unless a fixture row of their own beats it.
      for (const name of groupsByHead.get(headKey) ?? []) {
        const groupPending = draft.get('group', name, propertyName)
        if (groupPending?.kind === 'set') return own('group', name, groupPending.value, cellKey)
      }
      if (cookedOwn && sourceType === 'group' && sourceKey != null) {
        if (draft.get('group', sourceKey, propertyName)?.kind !== 'removed') return own('group', sourceKey, cooked.value, cellKey)
        return undefined
      }
      if (cooked != null && cooked.layerId != null) {
        return { value: cooked.value, source: { kind: 'layer', layerId: cooked.layerId, name: cooked.layerSource?.name ?? null } }
      }
      return undefined
    }
    const composedValue = (targetType: CueRowTargetType, targetKey: string, rawProperty: string): string | undefined => {
      const propertyName = canonicalCueProperty(rawProperty)
      const pending = draft.get(targetType, targetKey, propertyName)
      if (pending?.kind === 'set') return pending.value
      if (targetType === 'group') {
        const stored = storedGroupRows.get(cueRowKey('group', targetKey, propertyName))
        if (stored != null) return stored
        const member = (fixtures ?? []).find((f) => f.groups.includes(targetKey))
        return member ? cookedByKey.get(lookRowKey(member.key, propertyName))?.value : undefined
      }
      return cookedByKey.get(lookRowKey(targetKey, propertyName))?.value
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
      editEffects,
      listsBusy: listsInFlight > 0,
      undoSeq,
      undo,
      canUndo,
      flush,
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
    storedGroupRows,
    groupsByHead,
    fixtures,
    setValue,
    removeValue,
    editLayers,
    editEffects,
    listsInFlight,
    undoSeq,
    undo,
    canUndo,
    flush,
  ])

  // Keyed on what the bands read, not on the cue: a value save refetches the cue, and every cell in
  // the grid reads this context.
  const layeredKey = (cue?.layers ?? []).flatMap((layer) => (layer.source ? [`${layer.source.kind}:${layer.source.id}`] : [])).join('|')
  const effects = cue?.adHocEffects ?? EMPTY_EFFECTS
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
      pickEffect,
    }),
    [projectId, cueId, cueLabel, readOnly, layeredKey, effects, pressLayer, landValues, pickEffect],
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
  const words = [step.rows && 'values', step.layers && 'looks', step.adHocEffects && 'FX'].filter((w): w is string => !!w)
  return words.length <= 1 ? (words[0] ?? 'values') : `${words.slice(0, -1).join(', ')} and ${words[words.length - 1]}`
}

function cueLabelOf(cue: Cue): string {
  return cue.cueNumber ? `Q${cue.cueNumber}` : cue.name
}
