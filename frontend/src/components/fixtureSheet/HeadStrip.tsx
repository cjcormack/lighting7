import { useRef } from 'react'
import { ChevronDown, ChevronLeft, ChevronRight, Grid2x2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import { usePipRun, type PipRunStep } from '@/hooks/usePipRun'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { useCurrentProjectQuery } from '@/store/projects'
import { usePatchListQuery } from '@/store/patches'
import type { FixturePatch } from '@/api/patchApi'
import type { Fixture, FixtureTypeInfo } from '@/store/fixtures'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { FixtureAppearanceSource, type FixtureAppearance } from '../fixtures/fixtureAppearance'
import {
  addToPick,
  filterPick,
  PICK_FILTER_LABELS,
  PICK_FILTERS,
  selectOnlyPick,
  stepPick,
  togglePick,
  type GroupSheetMember,
  type HeadPick,
  type PickFilter,
  type PickHead,
} from './sheetPick'

/**
 * The head strip (fixture-fx-sheets plan D13; HeadsGroups board): **All**, then a pip per head of a
 * multi-head fixture — or per member of a group — in its live colour, the busk rig tile's pips grown
 * to a row, then the **Cells** menu and *Prev* / *Next*. It picks what the rows below it edit.
 *
 * The pick is **the sheet's own**, never the desk selection (call 4): picking heads here moves no
 * other screen's targets. The usual picks are one head, every head, or a pattern of them, so a pip
 * **picks that head alone** — a tap, a click, or the first pip of a run — and a run adds every pip
 * it crosses (`usePipRun`: a mouse drag, or a held finger). ⌘ or ⇧ with a click toggles one head in
 * or out. *All* lights every pip, since it holds every head, and a pick that covers every pip is
 * *All* again (`normalisePick`) — still every pip lit, so nothing reads as lost.
 *
 * The Cells menu is the busk band's vocabulary over the heads: *All*, *Odd*, *Even* and the halves
 * over every head (exactly the element filters an effect starts with) and *Invert* of the pick; the
 * steps move the pick one head along. Its face names the filter last chosen, and any other gesture
 * clears it — `mode`, which the sheet keeps beside the pick.
 *
 * The colours are the stage's colour dispatch (`FixtureAppearanceSource`), as a rig tile's are: one
 * leaf for a fixture, whose heads are its `segments`; one per member for a group. A pip keeps a
 * floor so a dark head is still a target.
 */
export function HeadStrip({
  heads,
  pick,
  mode,
  onPick,
  kind,
  fixture,
  members,
  allLabel,
}: {
  heads: readonly PickHead[]
  pick: HeadPick
  /** The Cells filter that made [pick], or null once anything else has moved it. */
  mode: PickFilter | null
  onPick: (next: HeadPick, mode?: PickFilter | null) => void
  /** A fixture's heads (narrow pips, numbered) or a group's members (wide, named). */
  kind: 'heads' | 'members'
  /** `heads`: the fixture whose `segments` colour the pips. */
  fixture?: Fixture
  /** `members`: each member — its fixture, and its head's place when it is one head of a bar. */
  members?: readonly GroupSheetMember[]
  /** The *All* button's words — `All`, `All 6`. */
  allLabel: string
}) {
  const keys = heads.map((h) => h.key)
  // A run moves the pick pip after pip within one gesture, faster than a render: each step reads
  // the pick the last one left.
  const latest = useRef(pick)
  latest.current = pick
  const keysRef = useRef(keys)
  keysRef.current = keys
  const pickTo = (next: HeadPick, nextMode: PickFilter | null = null) => {
    latest.current = next
    onPick(next, nextMode)
  }
  const press = (key: string, step: PipRunStep) => {
    const all = keysRef.current
    if (step.additive) pickTo(togglePick(latest.current, key, all))
    else pickTo(step.first ? selectOnlyPick(key, all) : addToPick(latest.current, key, all))
  }
  const { rowRef, hot, rowHandlers } = usePipRun<HTMLDivElement>({ attribute: 'data-head-pip', inert: false, onToggle: press })
  const lookups = usePipLookups()
  const noun = kind === 'heads' ? 'head' : 'member'

  const pip = (head: PickHead, index: number, style: React.CSSProperties | undefined) => {
    const picked = pick == null || pick.has(head.key)
    return (
      <button
        key={head.key}
        type="button"
        aria-pressed={picked}
        aria-label={head.name}
        title={`${head.name} — ⌘ or ⇧ to add or remove it`}
        data-head-pip={head.key}
        // A touch tap and the keyboard — a mouse has already gone through the run, and the click
        // that follows a run is swallowed by the row.
        onClick={(e) => press(head.key, { first: true, additive: e.metaKey || e.shiftKey })}
        className={cn(
          'flex min-w-2 flex-1 flex-col items-stretch gap-0.5 outline-none',
          kind === 'heads' ? 'max-w-5' : 'max-w-[38px]',
        )}
      >
        <span
          className={cn(
            'h-7 rounded-[5px] border border-white/15 transition-transform duration-100',
            picked && 'shadow-[0_0_0_2px_var(--color-primary)]',
            hot === head.key && 'scale-110',
          )}
          style={style}
        />
        <span className="truncate text-center text-[9px] leading-none text-muted-foreground">
          {kind === 'heads' ? index + 1 : head.name}
        </span>
      </button>
    )
  }

  const shown: PickFilter | null = pick == null ? 'ALL' : mode
  return (
    <div data-head-strip={kind} className="flex items-start gap-1">
      <button
        type="button"
        aria-pressed={pick == null}
        onClick={() => pickTo(null, 'ALL')}
        className={cn(
          'inline-flex h-7 shrink-0 items-center rounded-[7px] border px-2.5 text-[11.5px] text-muted-foreground',
          pick == null && 'border-primary bg-primary/15 text-foreground',
        )}
      >
        {allLabel}
      </button>
      <div
        ref={rowRef}
        role="group"
        aria-label={kind === 'heads' ? 'Heads' : 'Members'}
        className="flex min-w-0 flex-1 touch-manipulation items-start gap-1"
        {...rowHandlers}
      >
        {kind === 'heads' && fixture != null ? (
          <PipLeaf fixture={fixture} lookups={lookups}>
            {(appearance) => heads.map((h, i) => pip(h, i, pipStyle(appearance, i)))}
          </PipLeaf>
        ) : (
          heads.map((h, i) => {
            const member = members?.find((m) => m.key === h.key)
            return member == null ? (
              pip(h, i, undefined)
            ) : (
              <PipLeaf key={h.key} fixture={member.fixture} lookups={lookups}>
                {(appearance) => pip(h, i, pipStyle(appearance, member.elementIndex))}
              </PipLeaf>
            )
          })
        )}
      </div>
      <div className="flex shrink-0 items-center gap-0.5">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              data-pick-cells
              aria-label={`Cells: ${shown == null ? 'a pick of your own' : PICK_FILTER_LABELS[shown]}`}
              title={`Pick by pattern — ${PICK_FILTERS.map((f) => PICK_FILTER_LABELS[f]).join(' · ')}`}
              className="inline-flex h-7 items-center gap-1 rounded-[7px] border px-1.5 text-[11.5px] text-muted-foreground hover:text-foreground"
            >
              <Grid2x2 className="size-3.5" />
              {shown != null && <span className="@max-[400px]/sheet:hidden">{PICK_FILTER_LABELS[shown]}</span>}
              <ChevronDown className="size-3" />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuLabel className="text-[11px] font-normal text-muted-foreground">Cells</DropdownMenuLabel>
            {PICK_FILTERS.map((filter) => (
              <DropdownMenuItem
                key={filter}
                data-pick-filter={filter}
                disabled={filter === 'INVERT' && pick == null}
                onSelect={() => pickTo(filterPick(latest.current, filter, keysRef.current), filter === 'INVERT' ? null : filter)}
              >
                {PICK_FILTER_LABELS[filter]}
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
        {([-1, 1] as const).map((step) => (
          <button
            key={step}
            type="button"
            data-pick-step={step === 1 ? 'next' : 'prev'}
            aria-label={step === 1 ? `Next ${noun}` : `Previous ${noun}`}
            title={step === 1 ? `Move the pick one ${noun} on` : `Move the pick one ${noun} back`}
            onClick={() => pickTo(stepPick(latest.current, step, keysRef.current))}
            className="grid size-7 place-items-center rounded-[7px] border text-muted-foreground hover:text-foreground"
          >
            {step === 1 ? <ChevronRight className="size-3.5" /> : <ChevronLeft className="size-3.5" />}
          </button>
        ))}
      </div>
    </div>
  )
}

/** A pip's colour: the stage's — a head's segment, or the whole fixture's — with a floor so a dark head is still a target. */
function pipStyle(appearance: FixtureAppearance | null, index: number | null): React.CSSProperties {
  if (appearance == null) return { background: 'var(--color-muted)' }
  const segment = index == null ? undefined : appearance.segments?.[index]
  const intensity = Math.max(0, Math.min(1, segment?.intensity ?? appearance.intensity))
  return { background: segment?.css ?? appearance.color, opacity: 0.25 + 0.75 * intensity }
}

/**
 * The appearance leaf for one fixture, as the rig tile mounts it. A fixture with no patch (a stale
 * key) draws a muted pip.
 */
function PipLeaf({
  fixture,
  lookups,
  children,
}: {
  fixture: Fixture
  lookups: PipLookups
  children: (appearance: FixtureAppearance | null) => React.ReactNode
}) {
  const patch = lookups.patches?.find((p) => p.key === fixture.key)
  if (patch == null) return <>{children(null)}</>
  return (
    <FixtureAppearanceSource patch={patch} fixture={fixture} fixtureType={lookups.typeByKey.get(fixture.typeKey)}>
      {(appearance) => children(appearance)}
    </FixtureAppearanceSource>
  )
}

interface PipLookups {
  patches: readonly FixturePatch[] | undefined
  typeByKey: ReadonlyMap<string, FixtureTypeInfo>
}

/** The patches and types the leaves need, read once for the strip. */
function usePipLookups(): PipLookups {
  const { data: project } = useCurrentProjectQuery()
  const { data: patches } = usePatchListQuery(project?.id ?? 0, { skip: project == null })
  const { typeByKey } = useFixtureLookup()
  return { patches, typeByKey }
}
