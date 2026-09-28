import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { DEFERRED_TARGET_TYPE } from '@/api/looksApi'
import { parseProgrammerValue } from '@/lib/programmerValue'
import { useFixtureListQuery } from '@/store/fixtures'
import { useLookQuery, useSaveLookMutation } from '@/store/looks'
import { useProgrammerLayersQuery } from '@/store/programmer'
import { LookRowDraft } from './lookRowDraft'
import { lookRowKey } from './lookRowKey'
import { focusedLayerId, useProgrammerScope } from './ProgrammerScope'
import type { ReactNode } from 'react'
import type { CueTarget } from '@/api/cuesApi'
import type { LookDetails, LookRow } from '@/api/looksApi'
import type { StagedValue } from '@/components/fixtures-list/useRowOwnership'

export { lookRowKey } from './lookRowKey'

/**
 * The focused layer's Look, in the shape the grid reads and writes it.
 *
 * Provided **once** above the grid rather than fetched per row. One `useLookQuery` for hundreds of
 * rows is the same request either way — RTK Query dedupes — but not the same number of store
 * subscriptions or re-render paths, and the grid is virtualized precisely because per-row cost
 * matters here.
 *
 * The value's identity is stable across edits, deliberately: `draft` is one long-lived object with
 * its own per-key notification, so a 30 Hz cell drag wakes one cell rather than repainting the
 * grid. Only a *server* change — new rows landing, or the focus moving — mints a new value. The
 * save state lives in its own context for the same reason.
 */
export interface LookRowStoreValue {
  /** Carried so a consumer can address the Look's own routes without re-reading the route param. */
  projectId: number
  /** The focused *stack line*. Two layers may apply one Look, so this is what addresses ops. */
  layerId: number
  lookId: number
  lookName?: string
  /** Committed row values by [lookRowKey], group rows already expanded to their members. */
  serverRows: ReadonlyMap<string, StagedValue>
  /** Uncommitted edits, overlaid on `serverRows` by the read path. */
  draft: LookRowDraft
  /** Set one cell's value and schedule the save. Canonical assignment string. */
  setValue: (targetKey: string, propertyName: string, value: string) => void
  /** Fixture keys the layer asserts on. `null` means "the Look's own targets", i.e. all of them. */
  targetedKeys: ReadonlySet<string> | null
  /** The layer's target list verbatim, so a row can append to it without re-reading the stack. */
  targets: readonly CueTarget[]
  /** The layer's `propertyMask`, verbatim — callers parse it with `parsePropertyMask`. */
  propertyMask: string | null | undefined
  /**
   * Rows that name no target of their own. They compose against whatever the applying layer
   * targets, so they cannot be drawn as a value on any one fixture's row — the grid lists them
   * separately instead of guessing.
   */
  deferredRows: readonly LookRow[]
  /**
   * Rows addressing one element of a multi-element fixture. **Not composed here**, and that is a
   * known gap rather than an oversight, and the invariant behind it lives here now because the
   * file that used to record it (`syntheticFixture.ts`) was deleted:
   *
   * > **An element key is an element-*local* suffix owned by the backend.** Client code never
   * > parses one, never splits a target key on it, and never synthesises one — the fixture's
   * > element list is the only source of the keys that exist. Composing an element row would
   * > mean deriving "which cell is this" from the string, which is exactly the derivation the
   * > rule forbids.
   *
   * The backend's own `CueComposer.applyLayer` drops element rows too — `FU-LOOK-ELEMENT-ROWS`.
   * Surfaced so the grid can say so rather than render an empty cell.
   */
  elementRows: readonly LookRow[]
  loaded: boolean
}

export type LookSaveState = 'clean' | 'dirty' | 'saving' | 'error'

const LookRowStoreContext = createContext<LookRowStoreValue | null>(null)
const LookSaveStateContext = createContext<LookSaveState>('clean')

/** The focused layer's Look, or `null` in any other scope. */
export function useLookRowStore(): LookRowStoreValue | null {
  return useContext(LookRowStoreContext)
}

/** Whether the focused Look has unsaved edits in flight — for the scope band's read-out. */
export function useLookSaveState(): LookSaveState {
  return useContext(LookSaveStateContext)
}

/** Quiet time before a save. */
const SAVE_DEBOUNCE_MS = 400
/** …and the ceiling, so a long drag persists progressively rather than all at the end. */
const SAVE_CEILING_MS = 2000

/**
 * Provides the store while a layer is focused, and `null` otherwise.
 *
 * Mounted unconditionally so the tree shape never changes with the scope — the grid beneath it
 * must not remount, ever (`useListSelection` clears its Redux scope on unmount and Record scopes
 * on the fixture selection it throws away).
 */
export function LookRowStoreProvider({
  projectId,
  children,
}: {
  projectId: number
  children: ReactNode
}) {
  const scope = useProgrammerScope()
  const layerId = focusedLayerId(scope)
  const { data: layers } = useProgrammerLayersQuery()
  const layer = layerId == null ? undefined : layers?.find((l) => l.layerId === layerId)
  // **Only a LOOK layer has editable rows here.** A focused *template* layer deliberately shows no
  // rows in the grid: a template is one family of intents edited in its own family-native editor, and
  // projecting its generic row onto every targeted row would silently convert it to a per-fixture one
  // on the first edit — the same argument 2a made for deferred Look rows. `LayerRowNotices` names it
  // and points at `/templates` instead.
  const lookId = layer?.source.kind === 'LOOK' ? layer.source.id : undefined
  const { data: look, isSuccess } = useLookQuery(
    { projectId, lookId: lookId ?? 0 },
    { skip: lookId == null },
  )
  const { data: fixtures } = useFixtureListQuery()
  const [saveLook] = useSaveLookMutation()
  const [saveState, setSaveState] = useState<LookSaveState>('clean')

  // One draft per focused Look, minted on the change rather than through a `useMemo` dependency
  // the factory never reads. Recreated when the focus moves, which is also the moment any
  // still-pending edit must have been flushed — see the teardown effect below.
  //
  // **The Look rides in the same object as its draft, and that pairing is load-bearing.** The
  // teardown flush below runs *after* the render that moved the focus, so a single `lookRef` holding
  // "the latest look" would already be pointing at the next one: the outgoing draft would either
  // find nothing to save against (edits silently lost) or — worse, when the next Look is already
  // cached — be written into a Look the operator never touched. Keeping them together means the
  // closure the cleanup calls sees exactly the pair it was built for.
  const draftRef = useRef<{
    lookId: number | undefined
    draft: LookRowDraft
    look: LookDetails | undefined
  }>({ lookId, draft: new LookRowDraft(), look: undefined })
  if (draftRef.current.lookId !== lookId) {
    draftRef.current = { lookId, draft: new LookRowDraft(), look: undefined }
  }
  const entry = draftRef.current
  // Guarded on the id rather than assigned blindly: `useLookQuery` can only hand back the focused
  // Look, but the guard is what documents that this slot never holds another one.
  if (look && look.id === lookId) entry.look = look
  const draft = entry.draft

  const timerRef = useRef<number | null>(null)
  const ceilingRef = useRef<number | null>(null)
  /** Monotonic edit count — see the note in `flush`. */
  const writesRef = useRef(0)

  const flush = useCallback(() => {
    if (timerRef.current != null) window.clearTimeout(timerRef.current)
    if (ceilingRef.current != null) window.clearTimeout(ceilingRef.current)
    timerRef.current = null
    ceilingRef.current = null
    const current = entry.look
    if (!current || entry.draft.size === 0) return
    setSaveState('saving')
    // Not `draft.size` on the way out: the draft is retired against *server* rows, so it is still
    // full for the moment between the PUT resolving and the refetch landing, and reading it there
    // would report a freshly-saved Look as unsaved. What "dirty" really means is "an edit arrived
    // after this save started".
    const writesAtStart = writesRef.current
    // Built here rather than when the drag began: `PUT /looks/{id}` replaces the whole rows array,
    // so a stale base would resend another operator's rows as we last happened to see them.
    void saveLook({ projectId, lookId: current.id, rows: entry.draft.applyTo(current.rows) })
      .unwrap()
      .then(() => setSaveState(writesRef.current === writesAtStart ? 'clean' : 'dirty'))
      .catch(() => setSaveState('error'))
  }, [entry, projectId, saveLook])

  const targetedKeys = useMemo(
    () => (layer == null ? null : expandTargets(layer.targets, fixtures)),
    [layer, fixtures],
  )
  // Read through a ref so `setValue` stays identity-stable across layer-stack broadcasts — the
  // store value is held by every mounted row.
  const targetedKeysRef = useRef(targetedKeys)
  targetedKeysRef.current = targetedKeys

  const setValue = useCallback(
    (targetKey: string, propertyName: string, value: string) => {
      // **The write guard, not just the paint guard.** Untargeted cells are drawn
      // `pointer-events-none`, which stops a *click* — but the marquee selects by rectangle, off the
      // rows wrapper, so a drag across the grid can still put untargeted cells in the batch and one
      // commit from an editable cell would fan out to all of them. Widening a layer must stay an
      // explicit press (`AddToTargetsButton`), never a side effect of a drag.
      const targeted = targetedKeysRef.current
      if (targeted && !targeted.has(targetKey)) return
      draft.set(targetKey, propertyName, value)
      writesRef.current += 1
      setSaveState('dirty')
      // Debounce with a ceiling. The cadence is not only a network question: each save republishes
      // the Look's live consumers, so this is also how often the rig moves. Faster is not better
      // here — and there is no smooth-preview escape hatch to reach for: backend sweep item D4
      // deleted the Look preview routes outright. It no longer rebuilds every row on top of that:
      // a rows-only PUT skips the `Fixture`/`GroupList` tags, because compatibility is derived from
      // a Look's effects alone (see `saveLook`'s `invalidatesTags`).
      if (timerRef.current != null) window.clearTimeout(timerRef.current)
      timerRef.current = window.setTimeout(flush, SAVE_DEBOUNCE_MS)
      if (ceilingRef.current == null) {
        ceilingRef.current = window.setTimeout(flush, SAVE_CEILING_MS)
      }
    },
    [draft, flush],
  )

  // Retire draft entries the server now states, and flush anything pending when the focus moves or
  // the page goes away. Both halves matter: without the first the overlay would hide another
  // desk's edit, and without the second a value the operator set would be lost by navigating.
  useEffect(() => {
    if (!look) return
    const committed = new Map<string, string>()
    for (const row of look.rows) {
      if (row.targetType === 'fixture' && !row.elementKey) {
        committed.set(lookRowKey(row.targetKey, row.propertyName), row.value)
      }
    }
    draft.reconcile(committed)
    if (draft.size === 0) setSaveState((prev) => (prev === 'error' ? prev : 'clean'))
  }, [look, draft])

  useEffect(() => {
    const onLeave = () => flush()
    window.addEventListener('beforeunload', onLeave)
    return () => {
      window.removeEventListener('beforeunload', onLeave)
      flush()
    }
  }, [flush])

  const value = useMemo<LookRowStoreValue | null>(() => {
    if (layer == null || lookId == null) return null
    return {
      projectId,
      layerId: layer.layerId,
      lookId,
      lookName: layer.source.name,
      serverRows: buildServerRows(look?.rows, fixtures),
      draft,
      setValue,
      targetedKeys,
      targets: layer.targets,
      propertyMask: layer.propertyMask,
      deferredRows: (look?.rows ?? []).filter((r) => r.targetType === DEFERRED_TARGET_TYPE),
      elementRows: (look?.rows ?? []).filter(
        (r) => r.targetType !== DEFERRED_TARGET_TYPE && !!r.elementKey,
      ),
      loaded: isSuccess,
    }
  }, [projectId, layer, lookId, look, fixtures, draft, setValue, targetedKeys, isSuccess])

  return (
    <LookRowStoreContext.Provider value={value}>
      <LookSaveStateContext.Provider value={saveState}>{children}</LookSaveStateContext.Provider>
    </LookRowStoreContext.Provider>
  )
}

/**
 * Committed rows as the grid addresses them.
 *
 * Group rows first, fixture rows second, so a fixture-targeted row overwrites the group one it
 * overlaps. Same specificity the backend applies, and it has to hold here or a cell would display
 * the group's value while an edit wrote the fixture's.
 */
function buildServerRows(
  rows: readonly LookRow[] | undefined,
  fixtures: readonly { key: string; groups: string[] }[] | undefined,
): ReadonlyMap<string, StagedValue> {
  const out = new Map<string, StagedValue>()
  for (const pass of ['group', 'fixture'] as const) {
    for (const row of rows ?? []) {
      if (row.targetType !== pass || row.elementKey) continue
      const staged = parseProgrammerValue(row.value)
      if (!staged) continue
      if (pass === 'fixture') {
        out.set(lookRowKey(row.targetKey, row.propertyName), staged)
        continue
      }
      for (const fixture of fixtures ?? []) {
        if (fixture.groups.includes(row.targetKey)) {
          out.set(lookRowKey(fixture.key, row.propertyName), staged)
        }
      }
    }
  }
  return out
}

/**
 * The fixture keys a layer asserts on, or `null` for "whatever the Look itself names".
 *
 * An **empty** `targets` array is not "nothing" — it means the layer adds no targets of its own and
 * the Look's bound rows land where they name (`CueLayer.targets`' own doc says so). Reading it as
 * an empty set would dim every row in the grid on a perfectly ordinary bound layer.
 *
 * **A targeted fixture's element keys are in the set too.** `resolveTargetCells` applies
 * parent-first precedence, so a multi-element fixture whose parent carries no colour property
 * resolves that column *per element* and `RowCell.targetKeys` then holds element keys. Comparing
 * those against fixture keys alone marked every pixel bar the layer explicitly targets as "outside
 * the targets" — dashed, non-editable, with a spurious Add-to-targets button beside it.
 */
export function expandTargets(
  targets: readonly CueTarget[],
  fixtures: readonly TargetableFixture[] | undefined,
): ReadonlySet<string> | null {
  if (targets.length === 0) return null
  const byKey = new Map((fixtures ?? []).map((f) => [f.key, f]))
  const keys = new Set<string>()
  const add = (fixture: TargetableFixture | undefined, key: string) => {
    keys.add(key)
    for (const element of fixture?.elements ?? []) keys.add(element.key)
  }
  for (const target of targets) {
    if (target.type === 'fixture') {
      add(byKey.get(target.key), target.key)
      continue
    }
    for (const fixture of fixtures ?? []) {
      if (fixture.groups.includes(target.key)) add(fixture, fixture.key)
    }
  }
  return keys
}

/** Only the fields these two helpers read — `Fixture` itself is far wider. */
interface TargetableFixture {
  key: string
  groups: string[]
  elements?: { key: string }[]
}
