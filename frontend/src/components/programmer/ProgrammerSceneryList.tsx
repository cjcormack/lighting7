import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { X } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { EditorLabelLine } from '@/components/editor/EditorLabelLine'
import { EditorReadout } from '@/components/editor/EditorReadout'
import { BlindDot } from '@/components/busking/BlindMarks'
import { SceneryControl } from '@/components/scenery/SceneryControl'
import { ElementIcon } from '@/components/scenery/SceneryReadout'
import { TRACKED_HATCH_CLASS } from '@/components/scenery/trackedHatch'
import { useProgrammerBlind } from '@/hooks/useProgrammerBlind'
import { formatError } from '@/lib/formatError'
import { useProgrammerFade } from '@/lib/programmerFade'
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
import { useStageElementListQuery } from '@/store/stageElements'
import type { SceneryChange, SceneryState, SceneryWriteItem } from '@/api/sceneryApi'
import type { StageElementDto } from '@/api/stageElementApi'
import type { ProgrammerLayer } from '@/api/programmerWsApi'
import { useProgrammerScope, type ProgrammerScope } from './ProgrammerScope'

// — filters ————————————————————————————————————————————————————————————————————

/**
 * The list's three filters (scenery-programmer plan D4, the Scenery tab mock-up): *Set* lists the
 * Set layer, *Venue* the Venue layer, *Moving only* drops what can only be shown and hidden. A piece
 * the scope holds is listed whatever they say — its release × must stay in reach.
 */
export interface SceneryFilters {
  set: boolean
  venue: boolean
  movingOnly: boolean
}

export const DEFAULT_SCENERY_FILTERS: SceneryFilters = { set: true, venue: true, movingOnly: false }

/** Per window, as the busk sheet's tab is: `sessionStorage`, so a second window keeps its own. */
export const SCENERY_FILTERS_KEY = 'programmer.scenery.filters'

function readFilters(): SceneryFilters {
  try {
    const raw = window.sessionStorage.getItem(SCENERY_FILTERS_KEY)
    if (raw == null) return DEFAULT_SCENERY_FILTERS
    const parsed = JSON.parse(raw) as Partial<SceneryFilters>
    return {
      set: typeof parsed.set === 'boolean' ? parsed.set : DEFAULT_SCENERY_FILTERS.set,
      venue: typeof parsed.venue === 'boolean' ? parsed.venue : DEFAULT_SCENERY_FILTERS.venue,
      movingOnly: typeof parsed.movingOnly === 'boolean' ? parsed.movingOnly : DEFAULT_SCENERY_FILTERS.movingOnly,
    }
  } catch {
    // A private window, blocked storage, or a value an older build wrote: the defaults.
    return DEFAULT_SCENERY_FILTERS
  }
}

function writeFilters(filters: SceneryFilters) {
  try {
    window.sessionStorage.setItem(SCENERY_FILTERS_KEY, JSON.stringify(filters))
  } catch {
    // Storage refused: the filters still hold for this mount.
  }
}

/**
 * The rows the list draws, **Venue then Set** and by name within each, after the filters — a held
 * piece always. Pure, so the grouping and the filters are pinned without a store.
 */
export function sceneryGroups(
  elements: readonly StageElementDto[],
  filters: SceneryFilters,
  held: ReadonlySet<string>,
): { layer: 'VENUE' | 'SET'; elements: StageElementDto[] }[] {
  const keep = (e: StageElementDto) => {
    if (held.has(e.uuid)) return true
    if (e.layer === 'SET' ? !filters.set : !filters.venue) return false
    return !filters.movingOnly || sceneryMoves(e)
  }
  const byName = (a: StageElementDto, b: StageElementDto) => a.name.localeCompare(b.name)
  return (['VENUE', 'SET'] as const)
    .map((layer) => ({ layer, elements: elements.filter((e) => (e.layer === 'SET' ? 'SET' : 'VENUE') === layer && keep(e)).sort(byName) }))
    .filter((g) => g.elements.length > 0)
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
  if (layer.source.kind === 'TEMPLATE') {
    return { kind: 'readOnly', reason: 'A template carries no scenery', subject: layer.source.name }
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

  const write = useCallback(
    (elementUuid: string, patch: SceneryState) => {
      if (!ready) return
      const at = items.findIndex((d) => d.elementUuid === elementUuid)
      save(
        at < 0
          ? [...items, { elementUuid, state: patch }]
          : items.map((d, i) => (i === at ? { ...d, state: { ...d.state, ...patch } } : d)),
      )
    },
    [items, ready, save],
  )
  const release = useCallback(
    (elementUuid: string) => {
      if (ready) save(items.filter((d) => d.elementUuid !== elementUuid))
    },
    [items, ready, save],
  )

  return { held: items, write, release, ready }
}

// — the scope, shared by the band and the list ——————————————————————————————————————

/**
 * Everything a scenery row needs from the programmer's scope (D4), read once per mount: where a
 * gesture lands, what the scope holds, and what each element shows. Shared by the rail's Scenery
 * band (the held pieces) and the full list (`ProgrammerSceneryList`), so the two cannot draw one
 * element two ways.
 */
export interface SceneryScope {
  arm: SceneryArm
  /** What the scope holds, by element uuid: the programmer's overlay, or a focused Look's own list. */
  held: ReadonlyMap<string, SceneryState>
  /** One row's face: the state it draws, its read-out, whether it is held and whether Blind stages it. */
  rowOf: (element: StageElementDto) => { state: SceneryState; readout: string | undefined; held: boolean; staged: boolean }
  write: (elementUuid: string, patch: SceneryState) => void
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
  const entries = sameProject ? live.entries : {}
  const staged = sameProject ? live.staged : undefined

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
  const release = (elementUuid: string) => {
    if (arm.kind === 'look') look.release(elementUuid)
    else if (arm.kind === 'local') programmerClearScenery(elementUuid, fade)
  }

  const subject = arm.kind === 'readOnly' ? arm.subject : arm.kind === 'look' ? arm.name : `${held.size} held · Local`
  return { arm, held, rowOf, write, release, subject, ready: arm.kind !== 'look' || look.ready }
}

/** How many pieces the programmer holds in [projectId] — what Clear will drop, and the rail's count. */
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

// — the full list ——————————————————————————————————————————————————————————————————

/**
 * Every scene element (scenery-programmer plan D1, D4), **Venue then Set** and by name, each its
 * `SceneryElementRow` — what the rail's Scenery band opens as *All scenery…*, through
 * `EditorSurface`: a `w-72` popover on a desk or iPad, a bottom sheet on an upright phone, a side
 * sheet on a short viewport.
 *
 * **What a row shows is what the stage will**: the live frame's target over the element's base,
 * and — in Local while blind — the staged state over that, since Local is where the operator is
 * writing. Output shows live. Writes go at the programmer's fade (the Clear/Blind picker's), or —
 * at Snap — at the piece's own `travelS`; a release flies the piece home on the same fade. A
 * refusal is the desk's `programmer.error`, which the existing error toast shows.
 */
export const ProgrammerSceneryList = memo(function ProgrammerSceneryList({
  projectId,
  scope,
}: {
  projectId: number
  /**
   * The opener's scope reading, when it has one. The rail's band passes its own, so the band and
   * the list it opens edit **one** Look draft: two drafts of one whole-list `PUT` would each send
   * a list without the other's last change.
   */
  scope?: SceneryScope
}) {
  return scope != null ? <SceneryList projectId={projectId} scope={scope} /> : <OwnScopeSceneryList projectId={projectId} />
})

function OwnScopeSceneryList({ projectId }: { projectId: number }) {
  return <SceneryList projectId={projectId} scope={useSceneryScope(projectId)} />
}

function SceneryList({ projectId, scope }: { projectId: number; scope: SceneryScope }) {
  const { data: elements } = useStageElementListQuery(projectId)

  const [filters, setFilters] = useState<SceneryFilters>(readFilters)
  const toggle = (key: keyof SceneryFilters) => {
    const next = { ...filters, [key]: !filters[key] }
    setFilters(next)
    writeFilters(next)
  }

  const heldKeys = useMemo(() => new Set(scope.held.keys()), [scope.held])
  const groups = useMemo(() => sceneryGroups(elements ?? [], filters, heldKeys), [elements, filters, heldKeys])

  return (
    <div data-programmer-scenery className="flex min-h-0 flex-col">
      <div className="shrink-0 space-y-2 border-b px-3 py-2">
        <EditorLabelLine subject={scope.subject} column="Scenery" />
        <div role="group" aria-label="Scenery filters" className="flex flex-wrap items-center gap-1">
          <FilterChip on={filters.set} onClick={() => toggle('set')} label="Set" />
          <FilterChip on={filters.venue} onClick={() => toggle('venue')} label="Venue" />
          <FilterChip on={filters.movingOnly} onClick={() => toggle('movingOnly')} label="Moving only" />
        </div>
        {scope.arm.kind === 'readOnly' && <EditorReadout>{scope.arm.reason}</EditorReadout>}
      </div>
      <div className="min-h-0 flex-1 space-y-3 overflow-y-auto px-3 py-2">
        {(elements ?? []).length === 0 ? (
          <p className="text-xs text-muted-foreground">{NO_SCENERY_YET}</p>
        ) : groups.length === 0 ? (
          <p className="text-xs text-muted-foreground">Nothing matches the filters.</p>
        ) : (
          groups.map((group) => (
            <section key={group.layer} data-scenery-group={group.layer} className="space-y-1.5">
              <EditorLabel>{group.layer === 'VENUE' ? 'Venue' : 'Set'}</EditorLabel>
              {group.elements.map((element) => (
                <SceneryElementRow key={element.uuid} element={element} scope={scope} />
              ))}
            </section>
          ))
        )}
      </div>
    </div>
  )
}

export const NO_SCENERY_YET = 'The stage has no scenery yet — place some from the Stage view (Edit → + Scenery).'

function FilterChip({ on, onClick, label }: { on: boolean; onClick: () => void; label: string }) {
  return (
    <button
      type="button"
      aria-pressed={on}
      onClick={onClick}
      className={cn(
        'inline-flex h-6 items-center rounded-md border px-2 text-[11px] font-medium transition-colors',
        on ? 'border-primary/50 bg-primary/10 text-foreground' : 'text-muted-foreground hover:text-foreground',
      )}
    >
      {label}
    </button>
  )
}
