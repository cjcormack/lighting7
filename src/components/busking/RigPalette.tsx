import { useMemo, useState } from 'react'
import { useDraggable } from '@dnd-kit/core'
import { ChevronDown, ChevronRight, GripVertical, Layers, LayoutGrid, Search } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Input } from '@/components/ui/input'
import { useGroupListQuery } from '@/store/groups'
import { usePatchListQuery, useVisiblePatchListQuery } from '@/store/patches'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import type { BuskRigPatch } from '@/api/buskRigApi'
import { paletteRecordKey, rigIdsFromPatches, rigPaletteId, type RigPaletteRecord } from '@/lib/buskRig'
import { EditorLabel } from '../editor/EditorLabel'
import type { RigPaletteDragData } from './buskDnd'

/**
 * The **Rig** tab of the edit palette: every group and fixture, once, each a row waiting to be
 * dragged onto a rig row (busk-further plan D4, `Rig.dc.html`).
 *
 * Three things it says beside the name: *on rig* where the record already has a tile (it may still
 * be placed again — one record may sit on several tiles), *hidden* for a `stageHidden` patch, which
 * is **dimmed rather than gone** (the rig is the operator's, the stage flag is the stage's; §3.1),
 * and the cell count of a multi-head fixture, whose cells expand under it and **drag out as tiles
 * of their own**. The listeners are on the grip, never the row, for `LibraryPalette`'s reason: the
 * app's pointer sensor activates at 8px and a row that dragged by its body would swallow every
 * attempt to scroll this list on a touchscreen.
 */

type KindFilter = 'all' | 'group' | 'fixture'

interface PaletteRow {
  key: string
  record: RigPaletteRecord
  name: string
  detail: string
  hidden: boolean
  /**
   * A group the desk has published no id for — no patched member — cannot be named by the rig
   * write (`toRigRequest`), so it is offered as a row that says why and drags nowhere, rather than
   * as a drop that snaps back at commit. `LibraryPalette`'s `slotEligible` dim is the precedent.
   */
  unplaceable: boolean
  cells: RigPaletteRecord[]
}

function GripRow({
  record,
  name,
  detail,
  hidden,
  unplaceable = false,
  onRig,
  indent,
  expand,
}: {
  record: RigPaletteRecord
  name: string
  detail: string
  hidden: boolean
  unplaceable?: boolean
  onRig: boolean
  indent?: boolean
  expand?: { open: boolean; count: number; onToggle: () => void }
}) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: rigPaletteId(record),
    data: { type: 'rig-palette', record, name } satisfies RigPaletteDragData,
    disabled: unplaceable,
  })
  const Icon = record.kind === 'group' ? Layers : LayoutGrid
  return (
    <div
      ref={setNodeRef}
      className={cn(
        'flex min-h-10 items-center gap-2.5 border-t px-2.5 py-2 text-[13px] first:border-t-0',
        indent && 'pl-8',
        isDragging && 'opacity-35',
        (hidden || unplaceable) && 'opacity-50',
      )}
    >
      <button
        type="button"
        {...attributes}
        {...listeners}
        aria-label={`Place ${name}`}
        aria-disabled={unplaceable || undefined}
        disabled={unplaceable}
        className={cn('shrink-0 touch-none text-muted-foreground', unplaceable ? 'cursor-not-allowed' : 'cursor-grab')}
      >
        <GripVertical className="size-3.5" />
      </button>
      {record.kind !== 'cell' && <Icon className="size-3.5 shrink-0 text-muted-foreground" />}
      <div className="min-w-0 flex-1">
        <div className="truncate">{name}</div>
        <div className="truncate text-[11px] text-muted-foreground">{detail}</div>
      </div>
      {onRig && (
        <span className="inline-flex h-4 shrink-0 items-center rounded-full bg-muted px-1.5 text-[10px]">
          on rig
        </span>
      )}
      {expand != null && (
        <button
          type="button"
          onClick={expand.onToggle}
          aria-expanded={expand.open}
          aria-label={`${expand.open ? 'Hide' : 'Show'} the cells of ${name}`}
          className="inline-flex h-4 shrink-0 items-center gap-0.5 rounded-full border px-1.5 text-[10px] text-muted-foreground hover:text-foreground"
        >
          {expand.count} cells
          {expand.open ? <ChevronDown className="size-3" /> : <ChevronRight className="size-3" />}
        </button>
      )}
    </div>
  )
}

function SegButton({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        'flex-1 rounded-lg px-2.5 py-1 text-xs font-semibold transition-colors',
        active ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground',
      )}
    >
      {children}
    </button>
  )
}

export function RigPalette({
  projectId,
  onRigKeys,
}: {
  projectId: number
  /** `group:<name>` / `fixture:<key>` / `cell:<elementKey>` for records with a tile on the rig. */
  onRigKeys: Set<string>
}) {
  const { data: groups } = useGroupListQuery()
  // Rows are the rig an operator is offered; the ids are the whole patch's, so a group whose only
  // members are infrastructure is still placeable — a group is explicit, and still drives them.
  const { data: patches } = useVisiblePatchListQuery(projectId)
  const { data: allPatches } = usePatchListQuery(projectId)
  const { fixtureByKey } = useFixtureLookup()
  const [search, setSearch] = useState('')
  const [kind, setKind] = useState<KindFilter>('all')
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set())

  const rows = useMemo<{ groups: PaletteRow[]; fixtures: PaletteRow[] }>(() => {
    const ids = rigIdsFromPatches(allPatches)
    const groupRows: PaletteRow[] = (groups ?? []).map((group) => {
      const record: RigPaletteRecord = { kind: 'group', group }
      const unplaceable = !ids.groupIdByName.has(group.name)
      return {
        key: paletteRecordKey(record),
        record,
        name: group.name,
        detail: unplaceable
          ? 'no patched member · cannot be placed until it has one'
          : `${group.memberCount} ${group.memberCount === 1 ? 'member' : 'members'}`,
        hidden: false,
        unplaceable,
        cells: [],
      }
    })
    const fixtureRows: PaletteRow[] = (patches ?? []).map((patch) => {
      // The cells come from the live fixture's own element list — keys opaque, order the desk's.
      const elements = (fixtureByKey.get(patch.key)?.elements ?? []).map((element) => ({
        key: element.key,
        name: element.displayName,
      }))
      const rigPatch: BuskRigPatch = { id: patch.id, key: patch.key, name: patch.displayName, elements }
      const record: RigPaletteRecord = { kind: 'fixture', patch: rigPatch }
      return {
        key: paletteRecordKey(record),
        record,
        name: patch.displayName,
        detail: [
          elements.length > 0 ? `${elements.length} cells` : patch.key,
          patch.stageHidden ? 'hidden · stageHidden' : null,
        ]
          .filter(Boolean)
          .join(' · '),
        hidden: patch.stageHidden,
        unplaceable: false,
        cells: elements.map((element) => ({ kind: 'cell', patch: rigPatch, element })),
      }
    })
    return { groups: groupRows, fixtures: fixtureRows }
  }, [groups, patches, allPatches, fixtureByKey])

  const needle = search.trim().toLowerCase()
  const matches = (row: PaletteRow) => needle.length === 0 || row.name.toLowerCase().includes(needle)
  const shownGroups = kind === 'fixture' ? [] : rows.groups.filter(matches)
  const shownFixtures = kind === 'group' ? [] : rows.fixtures.filter(matches)
  const notOnRig = [...rows.groups, ...rows.fixtures].filter((row) => !onRigKeys.has(row.key)).length

  function toggleExpanded(key: string) {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  return (
    <>
      <div className="flex shrink-0 flex-col gap-2 border-b px-3 pt-3 pb-2">
        <div className="flex items-center gap-2">
          <EditorLabel>Rig</EditorLabel>
          <span className="flex-1" />
          <span className="text-[11px] text-muted-foreground">drag onto a row</span>
        </div>
        <div className="relative">
          <Search className="pointer-events-none absolute top-1/2 left-2 size-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search targets…"
            aria-label="Search targets"
            className="h-7 pl-7 text-[13px]"
          />
        </div>
        <div className="flex items-center gap-0.5 rounded-[10px] border bg-card p-0.5">
          {(['all', 'group', 'fixture'] as KindFilter[]).map((value) => (
            <SegButton key={value} active={kind === value} onClick={() => setKind(value)}>
              {value === 'all' ? 'All' : value === 'group' ? 'Groups' : 'Fixtures'}
            </SegButton>
          ))}
        </div>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {shownGroups.length === 0 && shownFixtures.length === 0 ? (
          <p className="p-4 text-center text-[12px] text-muted-foreground">
            Nothing here matches. Clear the search or the filter.
          </p>
        ) : (
          <>
            {shownGroups.length > 0 && (
              <div className="px-3 pt-2 pb-1 text-[10px] font-bold tracking-[0.08em] text-muted-foreground uppercase">
                Groups · {shownGroups.length}
              </div>
            )}
            {shownGroups.map((row) => (
              <GripRow
                key={row.key}
                record={row.record}
                name={row.name}
                detail={row.detail}
                hidden={row.hidden}
                unplaceable={row.unplaceable}
                onRig={onRigKeys.has(row.key)}
              />
            ))}
            {shownFixtures.length > 0 && (
              <div className="px-3 pt-2 pb-1 text-[10px] font-bold tracking-[0.08em] text-muted-foreground uppercase">
                Fixtures · {shownFixtures.length}
              </div>
            )}
            {shownFixtures.map((row) => (
              <div key={row.key}>
                <GripRow
                  record={row.record}
                  name={row.name}
                  detail={row.detail}
                  hidden={row.hidden}
                  onRig={onRigKeys.has(row.key)}
                  expand={
                    row.cells.length > 0
                      ? { open: expanded.has(row.key), count: row.cells.length, onToggle: () => toggleExpanded(row.key) }
                      : undefined
                  }
                />
                {expanded.has(row.key) &&
                  row.cells.map((cell) =>
                    cell.kind === 'cell' ? (
                      <GripRow
                        key={cell.element.key}
                        record={cell}
                        name={cell.element.name}
                        detail={cell.element.key}
                        hidden={row.hidden}
                        onRig={onRigKeys.has(paletteRecordKey(cell))}
                        indent
                      />
                    ) : null,
                  )}
              </div>
            ))}
          </>
        )}
      </div>
      <div className="shrink-0 border-t px-3 py-2.5 text-[11px] leading-relaxed text-muted-foreground">
        {notOnRig} not on the rig · a cell drags out as its own tile.
      </div>
    </>
  )
}
