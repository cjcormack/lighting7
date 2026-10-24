import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import { X } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { BlindDot } from '@/components/busking/BlindMarks'
import { SceneryControl } from '@/components/scenery/SceneryControl'
import { ElementIcon } from '@/components/scenery/SceneryReadout'
import { TRACKED_HATCH_CLASS } from '@/components/scenery/trackedHatch'
import { useProgrammerBlind } from '@/hooks/useProgrammerBlind'
import { formatError } from '@/lib/formatError'
import { useProgrammerFade } from '@/lib/programmerFade'
import { createSyncStore, sessionStorageArea } from '@/lib/syncStore'
import { describeScenerySource, heldSceneryIn, sceneryKindLabel, sceneryMoves, shownSceneryState } from '@/lib/scenery'
import { cn } from '@/lib/utils'
import { useLookQuery } from '@/store/looks'
import {
  programmerClearScenery,
  programmerSetScenery,
  useProgrammerLayersQuery,
  useProgrammerScenery,
} from '@/store/programmer'
import { useLiveScenery, useSetLookSceneryMutation } from '@/store/scenery'
import type { LiveScenery, SceneryChange, SceneryState, SceneryWriteItem } from '@/api/sceneryApi'
import type { StageElementDto } from '@/api/stageElementApi'
import type { ProgrammerLayer } from '@/api/programmerWsApi'
import { useProgrammerScope, type ProgrammerScope } from './ProgrammerScope'

// — filters ————————————————————————————————————————————————————————————————————

/**
 * The Scenery table's filters (scenery-programmer plan D4, tables-colours plan D6): *Set* lists the
 * Set layer, *Venue* the Venue layer, *Moving only* drops what can only be shown and hidden, and
 * *Changed* — the Values table's *Lit* for scenery — drops what nothing on stage has moved off its
 * base. A piece the scope holds is listed whatever they say — its release × must stay in reach.
 */
export interface SceneryFilters {
  set: boolean
  venue: boolean
  movingOnly: boolean
  changedOnly: boolean
}

export const DEFAULT_SCENERY_FILTERS: SceneryFilters = { set: true, venue: true, movingOnly: false, changedOnly: false }

/** Per window, as the busk sheet's tab is: `sessionStorage`, so a second window keeps its own. */
export const SCENERY_FILTERS_KEY = 'programmer.scenery.filters'

const sceneryFiltersStore = createSyncStore<SceneryFilters>({
  key: SCENERY_FILTERS_KEY,
  fallback: DEFAULT_SCENERY_FILTERS,
  storage: sessionStorageArea,
  parse: (parsed) => {
    if (parsed == null || typeof parsed !== 'object') return DEFAULT_SCENERY_FILTERS
    const stored = parsed as Partial<Record<keyof SceneryFilters, unknown>>
    const flag = (key: keyof SceneryFilters) => (typeof stored[key] === 'boolean' ? stored[key] : DEFAULT_SCENERY_FILTERS[key])
    return { set: flag('set'), venue: flag('venue'), movingOnly: flag('movingOnly'), changedOnly: flag('changedOnly') }
  },
})

/**
 * This window's filters — the Scenery table's chips, remembered per window (`sessionStorage`) so a
 * second mount reads them back.
 */
export function useSceneryFilters(): [SceneryFilters, (next: SceneryFilters) => void] {
  const filters = useSyncExternalStore(sceneryFiltersStore.subscribe, sceneryFiltersStore.getSnapshot, sceneryFiltersStore.getServerSnapshot)
  return [filters, sceneryFiltersStore.set]
}

/** Test seam. */
export function resetSceneryFiltersStore() {
  sceneryFiltersStore.reset()
}

/**
 * The rows the Scenery table draws, **Venue then Set** and by name within each, after the filters — a held
 * piece always. [isChanged] is what *Changed* keeps: `SceneryScope.isChanged`. Pure, so the grouping and
 * the filters are pinned without a store.
 */
export function sceneryGroups(
  elements: readonly StageElementDto[],
  filters: SceneryFilters,
  held: ReadonlySet<string>,
  isChanged: (element: StageElementDto) => boolean = () => false,
): { layer: 'VENUE' | 'SET'; elements: StageElementDto[] }[] {
  const keep = (e: StageElementDto) => {
    if (held.has(e.uuid)) return true
    if (e.layer === 'SET' ? !filters.set : !filters.venue) return false
    if (filters.changedOnly && !isChanged(e)) return false
    return !filters.movingOnly || sceneryMoves(e)
  }
  const byName = (a: StageElementDto, b: StageElementDto) => a.name.localeCompare(b.name)
  return (['VENUE', 'SET'] as const)
    .map((layer) => ({ layer, elements: elements.filter((e) => (e.layer === 'SET' ? 'SET' : 'VENUE') === layer && keep(e)).sort(byName) }))
    .filter((g) => g.elements.length > 0)
}

const NO_ENTRIES: LiveScenery['entries'] = {}

/** Two shown states the same, `open` and `trimM` to within a hair so a float round trip is no change. */
function sameSceneryState(a: SceneryState, b: SceneryState): boolean {
  const near = (x: number | undefined, y: number | undefined) => (x == null || y == null ? x === y : Math.abs(x - y) < 1e-6)
  return a.visible === b.visible && near(a.open, b.open) && near(a.trimM, b.trimM)
}

// — the scope's arm ————————————————————————————————————————————————————————————————

/**
 * Where a row's gesture lands, by the programmer's scope (D4): **Local** writes the programmer's own
 * scenery; a focused **Look** layer writes that Look's scenery while live, through the Look's own
 * scenery `PUT`; **Output** and a focused **template** layer write nothing (a template carries no
 * scenery, stage-view D11), and each row is disabled with the reason, never hidden.
 */
export type SceneryArm =
  | { kind: 'local' }
  | { kind: 'look'; lookId: number; name: string }
  | { kind: 'readOnly'; reason: string; subject: string }

function armOf(scope: ProgrammerScope | null, layers: readonly ProgrammerLayer[] | undefined): SceneryArm {
  if (scope == null || scope.kind === 'local') return { kind: 'local' }
  if (scope.kind === 'output') return { kind: 'readOnly', reason: 'Output is read-only', subject: 'Output' }
  const layer = layers?.find((l) => l.layerId === scope.layerId)
  if (layer == null) return { kind: 'readOnly', reason: 'Output is read-only', subject: 'Output' }
  if (layer.source.kind === 'PALETTE') {
    return { kind: 'readOnly', reason: 'A palette carries no scenery', subject: layer.source.name }
  }
  return { kind: 'look', lookId: layer.source.id, name: layer.source.name }
}

/** A Look's stored list as the draft holds it: by `sortOrder`, the fields a write sends. */
function itemsOf(stored: readonly SceneryChange[]): SceneryWriteItem[] {
  return [...stored].sort((a, b) => a.sortOrder - b.sortOrder).map((c) => ({ elementUuid: c.elementUuid, state: c.state }))
}

/**
 * A focused Look's scenery as the list edits it: the Look's own list, held as a draft while a save
 * is in flight so a refetch from before it cannot put an older list back under the operator's hands
 * (`SceneryEditor`'s rule), and written **whole** per gesture. A refusal toasts and goes back to
 * what the desk holds.
 *
 * **The draft belongs to one Look.** It carries the Look it was read from, and a focus moved to
 * another Look adopts that one's list at once, whatever is in flight for the first — a whole-list
 * `PUT` built from the old draft would otherwise replace the new Look's scenery with the old one's.
 * In-flight counts are per Look for the same reason.
 *
 * **What the desk holds is the latest word, from either side**: the stored list as it arrives, or
 * the list a save the desk accepted sent, whichever came last (`confirmed`). A refusal goes back to
 * that — never to the list as it stood when the refused gesture began, which would drop a save that
 * landed in between. A refetch skipped while a save was in flight needs no replay: the save
 * invalidates the Look, and that refetch lands with nothing in flight.
 */
function useLookScenery(projectId: number, lookId: number | null) {
  const { currentData: detail } = useLookQuery({ projectId, lookId: lookId ?? 0 }, { skip: lookId == null })
  const [setLookScenery] = useSetLookSceneryMutation()
  const stored = useMemo<readonly SceneryChange[]>(() => (lookId == null ? [] : (detail?.scenery ?? [])), [detail, lookId])
  const [draft, setDraft] = useState<{ lookId: number | null; items: SceneryWriteItem[] }>({ lookId: null, items: [] })
  const pending = useRef(new Map<number, number>())
  const confirmed = useRef<{ lookId: number | null; items: SceneryWriteItem[] }>({ lookId: null, items: [] })

  useEffect(() => {
    const items = itemsOf(stored)
    confirmed.current = { lookId, items }
    if (lookId != null && (pending.current.get(lookId) ?? 0) > 0) return
    setDraft({ lookId, items })
  }, [stored, lookId])

  // The draft as this render may build on: never another Look's.
  const items = draft.lookId === lookId ? draft.items : itemsOf(stored)

  const save = useCallback(
    (next: SceneryWriteItem[]) => {
      if (lookId == null) return
      setDraft({ lookId, items: next })
      pending.current.set(lookId, (pending.current.get(lookId) ?? 0) + 1)
      const settle = () => {
        const left = (pending.current.get(lookId) ?? 1) - 1
        if (left > 0) pending.current.set(lookId, left)
        else pending.current.delete(lookId)
        return left
      }
      setLookScenery({ projectId, lookId, scenery: next })
        .unwrap()
        .then(() => {
          if (confirmed.current.lookId === lookId) confirmed.current = { lookId, items: next }
          // The save invalidates the Look, so the refetch that follows lands with nothing in
          // flight and is adopted — a foreign edit skipped meanwhile included.
          settle()
        })
        .catch((e: unknown) => {
          toast.error(`Scenery refused: ${formatError(e)}`)
          settle()
          // The desk refused the whole list: show what it holds — if the focus is still here.
          setDraft((d) => (d.lookId === lookId && confirmed.current.lookId === lookId ? { lookId, items: confirmed.current.items } : d))
        })
    },
    [lookId, projectId, setLookScenery],
  )

  // Until the Look's own list has arrived there is nothing to build a whole-list `PUT` on: a write
  // then would replace every piece the Look holds with the one just moved.
  const ready = lookId == null || detail != null

  const writeMany = useCallback(
    (patches: readonly SceneryWriteItem[]) => {
      if (!ready || patches.length === 0) return
      let next = items
      for (const { elementUuid, state: patch } of patches) {
        const at = next.findIndex((d) => d.elementUuid === elementUuid)
        next =
          at < 0
            ? [...next, { elementUuid, state: patch }]
            : next.map((d, i) => (i === at ? { ...d, state: { ...d.state, ...patch } } : d))
      }
      save(next)
    },
    [items, ready, save],
  )
  const write = useCallback(
    (elementUuid: string, patch: SceneryState) => writeMany([{ elementUuid, state: patch }]),
    [writeMany],
  )
  const release = useCallback(
    (elementUuid: string) => {
      if (ready) save(items.filter((d) => d.elementUuid !== elementUuid))
    },
    [items, ready, save],
  )

  return { held: items, write, writeMany, release, ready }
}

// — the scope, shared by the Scenery table and the Stage view ——————————————————————————————————————

/**
 * Everything a scenery row needs from the programmer's scope (D4), read once per mount: where a
 * gesture lands, what the scope holds, and what each element shows. Shared by the programmer's
 * Scenery table and the Stage view's scenery popover, so the two cannot draw one element two ways.
 */
export interface SceneryScope {
  arm: SceneryArm
  /** What the scope holds, by element uuid: the programmer's overlay, or a focused Look's own list. */
  held: ReadonlyMap<string, SceneryState>
  /**
   * Whether the stage has [element] off its base — a set, a cue, a Look or the programmer moved it.
   * Judged by state, not by holder: the frame also lists pieces a cue tracks back to their base.
   */
  isChanged: (element: StageElementDto) => boolean
  /** One row's face: the state it draws, its read-out, whether it is held and whether Blind stages it. */
  rowOf: (element: StageElementDto) => { state: SceneryState; readout: string | undefined; held: boolean; staged: boolean }
  write: (elementUuid: string, patch: SceneryState) => void
  /**
   * Several pieces in one gesture — the Scenery table's Set over a marquee. One write, since a
   * focused Look's scenery is written whole: two in a row would each send a list without the other.
   */
  writeMany: (patches: readonly SceneryWriteItem[]) => void
  release: (elementUuid: string) => void
  /** The label line's subject: *2 held · Local*, the focused Look's name, or the read-only arm's word. */
  subject: string
  /** False while a focused Look's own list is loading: its rows draw, and write nothing until it lands. */
  ready: boolean
}

export function useSceneryScope(projectId: number): SceneryScope {
  const live = useLiveScenery()
  const programmer = useProgrammerScenery()
  const blind = useProgrammerBlind()
  const fade = Number(useProgrammerFade()) || 0
  const scope = useProgrammerScope()
  const { data: layers } = useProgrammerLayersQuery()
  const arm = armOf(scope, layers)
  const look = useLookScenery(projectId, arm.kind === 'look' ? arm.lookId : null)

  // The programmer's overlay in Local (and, read-only, in Output and over a template — it is still
  // what Clear will drop); the Look's own list with a Look focused.
  const held = useMemo(() => {
    const map = new Map<string, SceneryState>()
    if (arm.kind === 'look') for (const item of look.held) map.set(item.elementUuid, item.state)
    else if (programmer.projectId == null || programmer.projectId === projectId) {
      for (const item of programmer.elements) map.set(item.elementUuid, item.state)
    }
    return map
  }, [arm.kind, look.held, programmer, projectId])

  const staging = blind && arm.kind === 'local'
  const sameProject = live.projectId == null || live.projectId === projectId
  const entries = sameProject ? live.entries : NO_ENTRIES
  const staged = sameProject ? live.staged : undefined
  const isChanged = (element: StageElementDto) => {
    const entry = entries[element.uuid]
    return entry != null && !sameSceneryState(shownSceneryState(element, entry.state), shownSceneryState(element))
  }

  const rowOf = (element: StageElementDto) => {
    const entry = entries[element.uuid]
    const stagedEntry = staging ? staged?.[element.uuid] : undefined
    const own = held.get(element.uuid)
    // A focused Look's own state is what that Look says, over the stage as it is now.
    const state = shownSceneryState(element, entry?.state, stagedEntry?.state, arm.kind === 'look' ? own : undefined)
    const source = arm.kind === 'look' && own != null ? `in ${arm.name}` : describeScenerySource(entry?.source, entry != null)
    const readout = [stagedEntry != null ? 'staged' : null, source].filter(Boolean).join(' · ') || undefined
    return { state, readout, held: own != null, staged: stagedEntry != null }
  }

  const write = (elementUuid: string, patch: SceneryState) => {
    if (arm.kind === 'look') look.write(elementUuid, patch)
    else if (arm.kind === 'local') programmerSetScenery(elementUuid, patch, fade)
  }
  const writeMany = (patches: readonly SceneryWriteItem[]) => {
    if (arm.kind === 'look') look.writeMany(patches)
    else if (arm.kind === 'local') for (const p of patches) programmerSetScenery(p.elementUuid, p.state, fade)
  }
  const release = (elementUuid: string) => {
    if (arm.kind === 'look') look.release(elementUuid)
    else if (arm.kind === 'local') programmerClearScenery(elementUuid, fade)
  }

  const subject = arm.kind === 'readOnly' ? arm.subject : arm.kind === 'look' ? arm.name : `${held.size} held · Local`
  return { arm, held, isChanged, rowOf, write, writeMany, release, subject, ready: arm.kind !== 'look' || look.ready }
}

/** How many pieces the programmer holds in [projectId] — what Clear will drop, and the Scenery tab's count. */
export function useHeldSceneryCount(projectId: number): number {
  return heldSceneryIn(useProgrammerScenery(), projectId).length
}

/**
 * One element's row, wherever it is listed: its `SceneryControl`, the kind's glyph and word, and —
 * where the scope holds it and can write — a release ×. **Held** is the programmer's ownership
 * language: `ring-primary` on a held row; the tracked hatch on a row that only inherits; the
 * `BlindDot` on a row Blind is staging.
 */
export function SceneryElementRow({ element, scope }: { element: StageElementDto; scope: SceneryScope }) {
  const { arm } = scope
  const row = scope.rowOf(element)
  const readOnly = arm.kind === 'readOnly'
  const disabled = readOnly || !scope.ready
  return (
    <div
      data-scenery-element={element.uuid}
      data-held={row.held || undefined}
      data-staged={row.staged || undefined}
      title={readOnly ? arm.reason : undefined}
      className={cn('relative rounded-md px-2 py-1.5', row.held ? 'border ring-1 ring-primary' : TRACKED_HATCH_CLASS)}
    >
      {row.staged && <BlindDot />}
      <SceneryControl
        element={element}
        state={row.state}
        commit={arm.kind === 'look' ? 'release' : 'live'}
        disabled={disabled}
        readout={row.readout}
        onWrite={(patch) => scope.write(element.uuid, patch)}
        labelEnd={
          <>
            <ElementIcon element={element} />
            <span className="min-w-0 truncate text-[10px] text-muted-foreground">{sceneryKindLabel(element)}</span>
            <span className="flex-1" />
            {row.held && !disabled && (
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="size-6 shrink-0"
                aria-label={`Release ${element.name}`}
                title={arm.kind === 'look' ? `Take ${element.name} out of ${arm.name}` : `Release ${element.name}`}
                onClick={() => scope.release(element.uuid)}
              >
                <X className="size-3.5" />
              </Button>
            )}
          </>
        }
      />
    </div>
  )
}

export const NO_SCENERY_YET = 'The stage has no scenery yet — place some from the Stage view (Edit → + Scenery).'
