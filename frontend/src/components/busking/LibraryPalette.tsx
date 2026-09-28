import { useMemo, useState } from 'react'
import { useDraggable } from '@dnd-kit/core'
import { AudioWaveform, GripVertical, Search } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useCueSlotHover } from '@/components/dnd/useCueSlotHover'
import { Input } from '@/components/ui/input'
import { ATTRIBUTE_FAMILIES, FAMILY_LABELS, type AttributeFamily } from '@/lib/attributeFamily'
import { useTemplateListQuery } from '@/store/templates'
import { useLookListQuery } from '@/store/looks'
import { useProjectCueStackListQuery } from '@/store/cueStacks'
import { buskPaletteId, type PaletteRecord } from '@/lib/buskLayout'
import { canHandLand } from '@/lib/handTargets'
import type { BuskCue } from '@/api/buskApi'
import { EditorLabel } from '../editor/EditorLabel'
import {
  describeLookContents,
  describeTemplate,
  EFFECT_GLYPH_CLASS,
  padFaceOf,
  type PadFace,
} from './padFace'
import type { BuskPaletteDragData } from './buskDnd'
import { RigPalette } from './RigPalette'

/**
 * The library, as a palette rather than a picker (D9).
 *
 * It takes the speed rail's place while the page is being edited, a little wider, and every row is
 * a pad waiting to be dragged onto a bank. There is no ticking and no confirm: a record already on
 * the page says so and may still be placed again, because one record may sit on several pads.
 *
 * **The listeners are on the grip, never the row.** The app's pointer sensor activates at 8px and
 * is shared with the cue-slot grid, so a row that dragged by its whole body would swallow every
 * attempt to scroll this list on a touchscreen — and there is no per-draggable activation
 * constraint to reach for.
 */

type KindFilter = 'all' | 'template' | 'look' | 'cue'
type FamilyFilter = 'any' | AttributeFamily

interface PaletteRow {
  key: string
  dragId: string
  record: PaletteRecord
  face: PadFace
  name: string
  detail: string
  badge: string
  kind: KindFilter
  families: AttributeFamily[]
  /**
   * A cue slot could take this: a cue, or a Look bound to its own fixtures (D7).
   *
   * Read by `slotDrop.ts` when this row is dropped on a slot, and here to dim the row while a slot
   * is the target — derived at the palette because this is the surface that knows what each row is.
   */
  slotEligible: boolean
  swatch: string | null
  isEffect: boolean
  cueNumber: string | null
}

function PaletteRowItem({
  row,
  onPage,
  slotHovered,
}: {
  row: PaletteRow
  onPage: boolean
  /** A drag is over an FX cue slot, so rows a slot cannot take say why. */
  slotHovered: boolean
}) {
  const refused = slotHovered && !row.slotEligible
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: row.dragId,
    data: {
      type: 'busk-palette',
      record: row.record,
      face: row.face,
      slotEligible: row.slotEligible,
    } satisfies BuskPaletteDragData,
  })

  return (
    <div
      ref={setNodeRef}
      className={cn(
        'flex min-h-10 items-center gap-2.5 border-t px-2.5 py-2 text-[13px] first:border-t-0',
        isDragging && 'opacity-35',
        refused && 'opacity-40',
      )}
    >
      <button
        type="button"
        {...attributes}
        {...listeners}
        aria-label={`Place ${row.name}`}
        className="shrink-0 cursor-grab touch-none text-muted-foreground"
      >
        <GripVertical className="size-3.5" />
      </button>
      {row.cueNumber != null && (
        <span className="w-[22px] shrink-0 font-mono text-[11px] font-bold tabular-nums">
          {row.cueNumber}
        </span>
      )}
      {row.isEffect && <AudioWaveform className={EFFECT_GLYPH_CLASS} />}
      {row.swatch && (
        <span
          aria-hidden
          className="size-3 shrink-0 rounded shadow-[inset_0_0_0_1px_rgba(255,255,255,0.15)]"
          style={{ background: row.swatch }}
        />
      )}
      <div className="min-w-0 flex-1">
        <div className="truncate">{row.name}</div>
        <div className="truncate text-[11px] text-muted-foreground">{row.detail}</div>
      </div>
      {refused && (
        <span className="shrink-0 text-[10px] text-muted-foreground">needs a selection</span>
      )}
      {onPage && (
        <span className="inline-flex h-4 shrink-0 items-center rounded-full bg-muted px-1.5 text-[10px]">
          on page
        </span>
      )}
      <span className="inline-flex h-4 shrink-0 items-center rounded-full border px-1.5 text-[10px]">
        {row.badge}
      </span>
    </div>
  )
}

function SegButton({
  active,
  onClick,
  children,
  className,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
  className?: string
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        'rounded-lg px-2.5 py-1 text-xs font-semibold transition-colors',
        active ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground',
        className,
      )}
    >
      {children}
    </button>
  )
}

type PaletteTab = 'library' | 'rig'

/**
 * The tab strip over the palette: **Library** feeds the page, **Rig** feeds the band (busk-further
 * plan D4 — one edit mode, two tabs). The strip is the palette's, not either tab's, so the two
 * bodies share one frame and one width.
 */
function PaletteTabs({ tab, onSelect }: { tab: PaletteTab; onSelect: (tab: PaletteTab) => void }) {
  return (
    <div role="tablist" className="flex shrink-0 items-center gap-0.5 border-b px-3 pt-2 pb-2">
      {(['library', 'rig'] as PaletteTab[]).map((value) => (
        <button
          key={value}
          type="button"
          role="tab"
          aria-selected={tab === value}
          onClick={() => onSelect(value)}
          className={cn(
            'rounded-lg px-3 py-1 text-xs font-semibold transition-colors',
            tab === value ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {value === 'library' ? 'Library' : 'Rig'}
        </button>
      ))}
    </div>
  )
}

export function LibraryPalette({
  projectId,
  onPageKeys,
  onRigKeys,
}: {
  projectId: number
  /** `template:7` etc. for records with a pad on the page being edited. */
  onPageKeys: Set<string>
  /** `group:<name>` / `fixture:<key>` / `cell:<elementKey>` for records with a tile on the rig. */
  onRigKeys: Set<string>
}) {
  const [tab, setTab] = useState<PaletteTab>('library')
  return (
    <div className="hidden w-[360px] shrink-0 flex-col overflow-hidden border-l md:flex">
      <PaletteTabs tab={tab} onSelect={setTab} />
      {tab === 'library' ? (
        <LibraryTab projectId={projectId} onPageKeys={onPageKeys} />
      ) : (
        <RigPalette projectId={projectId} onRigKeys={onRigKeys} />
      )}
    </div>
  )
}

function LibraryTab({
  projectId,
  onPageKeys,
}: {
  projectId: number
  onPageKeys: Set<string>
}) {
  const { data: templates } = useTemplateListQuery({ projectId })
  const { data: looks } = useLookListQuery({ projectId })
  const { data: stacks } = useProjectCueStackListQuery(projectId)
  // Once for the whole palette, not per row: it is a monitor subscription, and eight of them would
  // be eight re-renders per hover.
  const slotHovered = useCueSlotHover()
  const [search, setSearch] = useState('')
  const [kind, setKind] = useState<KindFilter>('all')
  const [family, setFamily] = useState<FamilyFilter>('any')

  const rows = useMemo<PaletteRow[]>(() => {
    const out: PaletteRow[] = []
    for (const template of templates ?? []) {
      const pad = { kind: 'TEMPLATE' as const, template }
      const face = padFaceOf(pad)
      out.push({
        key: `template:${template.id}`,
        dragId: buskPaletteId('TEMPLATE', template.id),
        record: pad,
        face,
        name: template.name,
        detail: describeTemplate(template),
        badge: template.family != null ? FAMILY_LABELS[template.family].singular : 'Template',
        kind: 'template',
        families: template.family != null ? [template.family] : [],
        // The rule is `lib/handTargets.ts`'s, not this row's: a slot takes only what needs no
        // selection, and that answer must be the same one the hand's target affordance gives.
        // These three rows used to state it themselves, which made three copies of it.
        slotEligible: canHandLand({ kind: 'TEMPLATE' }, 'slot'),
        swatch: face.swatch,
        isEffect: template.kind === 'effect',
        cueNumber: null,
      })
    }
    for (const look of looks ?? []) {
      const pad = { kind: 'LOOK' as const, look }
      out.push({
        key: `look:${look.id}`,
        dragId: buskPaletteId('LOOK', look.id),
        record: pad,
        face: padFaceOf(pad),
        name: look.name,
        detail: [
          `${look.targetCount} ${look.targetCount === 1 ? 'fixture' : 'fixtures'}`,
          describeLookContents(look),
          look.hasDeferredEffects ? null : 'bound',
        ]
          .filter(Boolean)
          .join(' · '),
        badge: 'Look',
        kind: 'look',
        families: look.families,
        slotEligible: canHandLand({ kind: 'LOOK', hasDeferredEffects: look.hasDeferredEffects }, 'slot'),
        swatch: null,
        isEffect: false,
        cueNumber: null,
      })
    }
    for (const stack of stacks ?? []) {
      if (stack.type !== 'STACK') continue
      for (const cue of stack.cues) {
        // A MARKER cannot be fired, so a pad for one would be dead on arrival.
        if (cue.cueType === 'MARKER') continue
        const record: BuskCue = {
          id: cue.id,
          name: cue.name,
          cueNumber: cue.cueNumber,
          cueStackId: stack.id,
          cueStackName: stack.name,
        }
        const pad = { kind: 'CUE' as const, cue: record }
        out.push({
          key: `cue:${cue.id}`,
          dragId: buskPaletteId('CUE', cue.id),
          record: pad,
          face: padFaceOf(pad),
          name: cue.name,
          detail: stack.name,
          badge: 'Cue',
          kind: 'cue',
          families: [],
          slotEligible: canHandLand({ kind: 'CUE' }, 'slot'),
          swatch: null,
          isEffect: false,
          cueNumber: cue.cueNumber,
        })
      }
    }
    return out
  }, [templates, looks, stacks])

  const shown = useMemo(() => {
    const needle = search.trim().toLowerCase()
    return rows.filter((row) => {
      if (kind !== 'all' && row.kind !== kind) return false
      // A cue belongs to no family, so a family filter is a filter *out* rather than a partition.
      if (family !== 'any' && !row.families.includes(family)) return false
      if (needle.length === 0) return true
      return (
        row.name.toLowerCase().includes(needle) ||
        (row.cueNumber ?? '').toLowerCase().includes(needle)
      )
    })
  }, [rows, kind, family, search])

  return (
    <>
      <div className="flex shrink-0 flex-col gap-2 border-b px-3 pt-3 pb-2">
        <div className="flex items-center gap-2">
          <EditorLabel>Library</EditorLabel>
          <span className="flex-1" />
          <span className="text-[11px] text-muted-foreground">drag onto a bank</span>
        </div>
        <div className="relative">
          <Search className="pointer-events-none absolute top-1/2 left-2 size-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search"
            aria-label="Search the library"
            className="h-7 pl-7 text-[13px]"
          />
        </div>
        <div className="flex items-center gap-0.5 rounded-[10px] border bg-card p-0.5">
          {(['all', 'template', 'look', 'cue'] as KindFilter[]).map((value) => (
            <SegButton
              key={value}
              active={kind === value}
              onClick={() => setKind(value)}
              className="flex-1"
            >
              {value === 'all' ? 'All' : value === 'template' ? 'Templates' : value === 'look' ? 'Looks' : 'Cues'}
            </SegButton>
          ))}
        </div>
        <div className="flex w-fit items-center gap-0.5 rounded-[10px] border bg-card p-0.5">
          <SegButton active={family === 'any'} onClick={() => setFamily('any')}>
            Any family
          </SegButton>
          {ATTRIBUTE_FAMILIES.map((value) => (
            <SegButton key={value} active={family === value} onClick={() => setFamily(value)}>
              {FAMILY_LABELS[value].singular}
            </SegButton>
          ))}
        </div>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {shown.length === 0 ? (
          <p className="p-4 text-center text-[12px] text-muted-foreground">
            Nothing here matches. Clear the search or the filters.
          </p>
        ) : (
          shown.map((row) => (
            <PaletteRowItem
              key={row.key}
              row={row}
              onPage={onPageKeys.has(row.key)}
              slotHovered={slotHovered}
            />
          ))
        )}
      </div>
      <div className="shrink-0 border-t px-3 py-2.5 text-[11px] leading-relaxed text-muted-foreground">
        Every row is a pad waiting to be placed. Long-press lifts on touch.
      </div>
    </>
  )
}
