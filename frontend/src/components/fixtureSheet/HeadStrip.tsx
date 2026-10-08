import { useRef } from 'react'
import { cn } from '@/lib/utils'
import { usePipRun } from '@/hooks/usePipRun'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { useCurrentProjectQuery } from '@/store/projects'
import { usePatchListQuery } from '@/store/patches'
import type { FixturePatch } from '@/api/patchApi'
import type { Fixture, FixtureTypeInfo } from '@/store/fixtures'
import { FixtureAppearanceSource, type FixtureAppearance } from '../fixtures/fixtureAppearance'
import { togglePick, type GroupSheetMember, type HeadPick, type PickHead } from './sheetPick'

/**
 * The head strip (fixture-fx-sheets plan D13; HeadsGroups board): **All**, then a pip per head of a
 * multi-head fixture — or per member of a group — in its live colour, the busk rig tile's pips grown
 * to a row. It picks what the rows below it edit.
 *
 * The pick is **the sheet's own**, never the desk selection (call 4): picking heads here moves no
 * other screen's targets. A pip is the busk pip (`usePipRun`): **a tap toggles**, **a mouse drag
 * runs** — every pip crossed toggled once — and **a held finger runs**. *All* is one press back, and
 * a pick that empties or covers every pip is *All* again (`normalisePick`).
 *
 * The colours are the stage's colour dispatch (`FixtureAppearanceSource`), as a rig tile's are: one
 * leaf for a fixture, whose heads are its `segments`; one per member for a group. A pip keeps a
 * floor so a dark head is still a target.
 */
export function HeadStrip({
  heads,
  pick,
  onPick,
  kind,
  fixture,
  members,
  allLabel,
}: {
  heads: readonly PickHead[]
  pick: HeadPick
  onPick: (next: HeadPick) => void
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
  // The run toggles pip after pip within one gesture, faster than a render: each toggle reads the
  // pick the last one left.
  const latest = useRef(pick)
  latest.current = pick
  const keysRef = useRef(keys)
  keysRef.current = keys
  const toggle = (key: string) => {
    const next = togglePick(latest.current, key, keysRef.current)
    latest.current = next
    onPick(next)
  }
  const { rowRef, hot, rowHandlers } = usePipRun<HTMLDivElement>({ attribute: 'data-head-pip', inert: false, onToggle: toggle })
  const lookups = usePipLookups()

  const pip = (head: PickHead, index: number, style: React.CSSProperties | undefined) => {
    const picked = pick?.has(head.key) ?? false
    return (
      <button
        key={head.key}
        type="button"
        role="checkbox"
        aria-checked={picked}
        aria-label={head.name}
        title={head.name}
        data-head-pip={head.key}
        // The keyboard's toggle — a pointer has already gone through the run, and the click that
        // follows a run is swallowed by the row.
        onClick={() => toggle(head.key)}
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

  return (
    <div data-head-strip={kind} className="flex items-start gap-1">
      <button
        type="button"
        aria-pressed={pick == null}
        onClick={() => onPick(null)}
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
