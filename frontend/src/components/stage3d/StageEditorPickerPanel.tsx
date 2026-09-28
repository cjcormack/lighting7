import { useEffect, useMemo, useRef, useState } from 'react'
import { EyeOff } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { cn, formatTriple } from '@/lib/utils'
import type { FixturePatch } from '@/api/patchApi'
import type { StageRegionDto } from '@/api/stageRegionApi'
import type { RiggingDto } from '@/api/riggingApi'
import type { Selection } from './Stage3D'

type RowSelection = Exclude<Selection, null>

interface Row {
  selection: RowSelection
  label: string
  sublabel: string
  /** Patch rows only: marks a `stageHidden` fixture. It isn't drawn in the
   *  scene, so this list is the only way to reach it and un-hide it. */
  hidden?: boolean
}

interface Section {
  title: string
  rows: Row[]
  /** Index of this section's first row in the cross-section flat list (for cursor math). */
  startIdx: number
}

interface StageEditorPickerPanelProps {
  patches: FixturePatch[]
  regions: StageRegionDto[]
  riggings: RiggingDto[]
  onSelect: (s: Selection) => void
}

function rowKey(sel: RowSelection): string {
  switch (sel.kind) {
    case 'patch': return `patch-${sel.patchKey}`
    case 'region': return `region-${sel.uuid}`
    case 'rigging': return `rigging-${sel.uuid}`
  }
}

function patchSublabel(p: FixturePatch): string {
  const maker = [p.manufacturer, p.model].filter(Boolean).join(' ') || 'Fixture'
  const ch = p.channelCount ?? 1
  const range = ch > 1 ? `${p.startChannel}–${p.startChannel + ch - 1}` : `${p.startChannel}`
  return `${maker} · DMX ${range} on U${p.universe}`
}

function regionSublabel(r: StageRegionDto): string {
  return `${formatTriple(r.widthM, r.depthM, r.heightM, ' × ')} m`
}

function riggingSublabel(r: RiggingDto): string {
  const kind = r.kind ?? 'Rigging'
  return r.lengthM == null ? kind : `${kind} · ${r.lengthM.toFixed(1)} m`
}

export function StageEditorPickerPanel({
  patches,
  regions,
  riggings,
  onSelect,
}: StageEditorPickerPanelProps) {
  const [search, setSearch] = useState('')
  const [cursor, setCursor] = useState(0)
  const rowRefs = useRef<Map<string, HTMLButtonElement>>(new Map())

  const sections = useMemo<Section[]>(() => {
    const fixtureRows: Row[] = [...patches]
      .sort((a, b) => a.sortOrder - b.sortOrder)
      .map((p) => ({
        selection: { kind: 'patch', patchKey: p.key },
        label: p.displayName || `Patch ${p.id}`,
        sublabel: patchSublabel(p),
        hidden: p.stageHidden,
      }))
    const regionRows: Row[] = [...regions]
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((r) => ({
        selection: { kind: 'region', uuid: r.uuid },
        label: r.name,
        sublabel: regionSublabel(r),
      }))
    const riggingRows: Row[] = [...riggings]
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((r) => ({
        selection: { kind: 'rigging', uuid: r.uuid },
        label: r.name,
        sublabel: riggingSublabel(r),
      }))
    const q = search.trim().toLowerCase()
    const filter = (rows: Row[]) =>
      !q
        ? rows
        : rows.filter(
            (r) => r.label.toLowerCase().includes(q) || r.sublabel.toLowerCase().includes(q),
          )
    const nonEmpty = [
      { title: 'Fixtures', rows: filter(fixtureRows) },
      { title: 'Regions', rows: filter(regionRows) },
      { title: 'Rigging', rows: filter(riggingRows) },
    ].filter((s) => s.rows.length > 0)
    let offset = 0
    return nonEmpty.map((s) => {
      const startIdx = offset
      offset += s.rows.length
      return { ...s, startIdx }
    })
  }, [patches, regions, riggings, search])

  const flat = useMemo(() => sections.flatMap((s) => s.rows), [sections])

  // Reset cursor whenever the filter result set changes so it never points past
  // the end of the (potentially shorter) flat list.
  useEffect(() => {
    setCursor(0)
  }, [flat.length])

  // Keep the highlighted row in view as the cursor moves.
  useEffect(() => {
    const active = flat[cursor]
    if (!active) return
    rowRefs.current.get(rowKey(active.selection))?.scrollIntoView({ block: 'nearest' })
  }, [cursor, flat])

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setCursor((c) => Math.min(c + 1, Math.max(0, flat.length - 1)))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setCursor((c) => Math.max(c - 1, 0))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      const row = flat[cursor]
      if (row) onSelect(row.selection)
    } else if (e.key === 'Escape' && search) {
      e.preventDefault()
      setSearch('')
    }
  }

  return (
    <aside className="flex w-full flex-col border-l bg-background sm:w-[360px]">
      <div className="border-b p-2">
        <Input
          autoFocus
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          onKeyDown={onKeyDown}
          placeholder="Search fixtures, regions, rigging…"
          className="h-9 text-sm"
        />
      </div>
      <div className="flex-1 overflow-y-auto p-2">
        {sections.length === 0 && (
          <div className="py-8 text-center text-xs text-muted-foreground">
            {search ? 'No matches' : 'Nothing to select yet'}
          </div>
        )}
        {sections.map((s) => (
          <div key={s.title} className="mb-3 last:mb-0">
            <div className="px-2 py-1 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
              {s.title}
            </div>
            <ul>
              {s.rows.map((r, rowIdx) => {
                const i = s.startIdx + rowIdx
                const active = i === cursor
                const key = rowKey(r.selection)
                return (
                  <li key={key}>
                    <button
                      ref={(el) => {
                        if (el) rowRefs.current.set(key, el)
                        else rowRefs.current.delete(key)
                      }}
                      type="button"
                      onClick={() => onSelect(r.selection)}
                      onMouseEnter={() => setCursor(i)}
                      className={cn(
                        'flex w-full flex-col items-start rounded-md px-2 py-1.5 text-left',
                        active ? 'bg-accent text-accent-foreground' : 'hover:bg-accent/50',
                      )}
                    >
                      <span className="flex w-full items-center gap-1.5 text-sm leading-tight">
                        <span className="truncate">{r.label}</span>
                        {r.hidden && (
                          <EyeOff
                            className="size-3 shrink-0 text-muted-foreground"
                            role="img"
                            aria-label="Hidden from Stage view"
                          />
                        )}
                      </span>
                      <span className="w-full truncate text-[11px] leading-tight text-muted-foreground">
                        {r.sublabel}
                      </span>
                    </button>
                  </li>
                )
              })}
            </ul>
          </div>
        ))}
      </div>
    </aside>
  )
}
