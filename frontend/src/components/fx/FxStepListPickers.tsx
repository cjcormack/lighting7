import { useMemo, useState } from 'react'
import { useParams } from 'react-router'
import { ArrowLeft, X } from 'lucide-react'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { cn } from '@/lib/utils'
import { usePaletteListQuery } from '@/store/palettes'
import type { PaletteSummary } from '@/api/palettesApi'
import type { SettingPropertyDescriptor } from '@/store/fixtures'
import { FAMILY_LABELS, type AttributeFamily } from '@/lib/attributeFamily'
import { palettePropertyFor, paletteRowsSwatch } from '@/lib/paletteIntent'
import {
  isSteppablePalette,
  parseStepList,
  serializeStepList,
  slotOption,
  stepPalettesFor,
} from '@/lib/stepFx'
import { EditorLabel } from '../editor/EditorLabel'
import { EditorReadout } from '../editor/EditorReadout'
import { parsePaletteRefUuid, serializePaletteRef } from './colourUtils'

/**
 * The effect editor's two list parameters (effects plan session 7, §3.6): Palette Steps' palettes
 * and Slot Steps' slots. Both edit an ordered list — the steps, in the order the effect runs them —
 * and neither resolves anything: each step is resolved per head on the desk.
 */

/** One step: its place, its name, a swatch where it has a colour, ← to move it earlier and ×. */
function StepChip({
  index,
  label,
  swatch,
  missing,
  readOnly,
  onEarlier,
  onRemove,
}: {
  index: number
  label: string
  swatch?: string | null
  missing?: boolean
  readOnly: boolean
  onEarlier: (() => void) | null
  onRemove: () => void
}) {
  return (
    <li
      data-step={label}
      className={cn(
        'flex h-7 min-w-0 items-center gap-1 rounded-md border bg-background pl-1.5 pr-0.5 text-xs',
        missing && 'border-dashed text-muted-foreground',
      )}
    >
      <span className="w-3 shrink-0 text-right text-[10px] tabular-nums text-muted-foreground">{index + 1}</span>
      {swatch && <span className="size-3 shrink-0 rounded-sm border" style={{ backgroundColor: swatch }} />}
      <span className="min-w-0 flex-1 truncate">{label}</span>
      {onEarlier != null && (
        <button
          type="button"
          aria-label={`Move ${label} earlier`}
          title="Earlier"
          disabled={readOnly}
          onClick={onEarlier}
          className="rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50"
        >
          <ArrowLeft className="size-3" />
        </button>
      )}
      <button
        type="button"
        aria-label={`Remove ${label}`}
        title="Remove"
        disabled={readOnly}
        onClick={onRemove}
        className="rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50"
      >
        <X className="size-3" />
      </button>
    </li>
  )
}

function moveEarlier<T>(list: readonly T[], index: number): T[] {
  const next = [...list]
  ;[next[index - 1], next[index]] = [next[index], next[index - 1]]
  return next
}

/**
 * Palette Steps' `palettes`: generic value palettes, in step order, by reference (`pal:{uuid}`), so
 * retuning a palette moves the running steps. The colour list's picker for any family: on an effect
 * palette only that palette's [family] is offered, once a palette is in the list only its family, and
 * on a running effect only the palettes naming the property it drives. A palette may appear more
 * than once — *A, B, A, C* is a list.
 *
 * `projectId` comes from the route, as the colour pickers' does; outside a project nothing is
 * offered and the list still reads.
 */
export function FxPaletteListPicker({
  label,
  description,
  value,
  propertyName,
  family: paletteFamily,
  readOnly,
  onChange,
}: {
  label: string
  description?: string
  value: string
  /** The property the effect drives, where it has one; an effect palette's has none. */
  propertyName?: string | null
  /** An effect palette's family, which the list must keep to — the desk refuses any other. */
  family?: AttributeFamily | null
  readOnly: boolean
  onChange: (value: string) => void
}) {
  const { projectId } = useParams<{ projectId: string }>()
  const projectIdNum = projectId ? Number(projectId) : NaN
  const { data, isLoading } = usePaletteListQuery({ projectId: projectIdNum }, { skip: !Number.isFinite(projectIdNum) })
  const templates = useMemo(() => data ?? [], [data])
  const byUuid = useMemo(() => new Map(templates.map((t) => [t.uuid, t] as const)), [templates])

  const entries = parseStepList(value)
  const resolved = entries.map((entry) => {
    const uuid = parsePaletteRefUuid(entry)
    return uuid != null ? byUuid.get(uuid) : undefined
  })
  const property = propertyName != null ? palettePropertyFor(propertyName) : null
  const family = paletteFamily ?? resolved.find((t) => t != null)?.family ?? property?.family ?? null
  const offered: PaletteSummary[] =
    family == null
      ? templates.filter(isSteppablePalette)
      : property != null
        ? stepPalettesFor(templates, family, property.propertyName)
        : templates.filter((t) => isSteppablePalette(t) && t.family === family)

  const write = (next: readonly string[]) => onChange(serializeStepList(next))

  return (
    <div className="flex flex-col gap-1.5" data-step-list="palettes">
      <EditorLabel>{label}</EditorLabel>
      {entries.length > 0 && (
        <ul className="flex flex-col gap-1" aria-label={label}>
          {entries.map((entry, i) => {
            const palette = resolved[i]
            const name = palette?.name ?? (isLoading ? '…' : 'Missing palette')
            return (
              <StepChip
                key={`${entry}-${i}`}
                index={i}
                label={name}
                swatch={palette?.family === 'COLOUR' ? paletteRowsSwatch(palette.rows) : null}
                missing={palette == null && !isLoading}
                readOnly={readOnly}
                onEarlier={i > 0 ? () => write(moveEarlier(entries, i)) : null}
                onRemove={() => write(entries.filter((_, j) => j !== i))}
              />
            )
          })}
        </ul>
      )}
      <Select
        value=""
        disabled={readOnly || offered.length === 0}
        onValueChange={(uuid) => uuid && write([...entries, serializePaletteRef(uuid)])}
      >
        <SelectTrigger aria-label={`Add to ${label}`} className="h-7 w-full text-xs">
          <SelectValue placeholder={offered.length === 0 ? 'No palettes to step' : 'Add a palette…'} />
        </SelectTrigger>
        <SelectContent>
          {offered.map((t) => (
            <SelectItem key={t.uuid} value={t.uuid} className="text-xs">
              {t.name}
              {family == null && t.family != null && (
                <span className="ml-2 text-muted-foreground">{FAMILY_LABELS[t.family].singular}</span>
              )}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <EditorReadout>
        {entries.length < 2
          ? 'Two palettes or more, of one family — each head goes to its own value for each.'
          : (description ?? `Steps through ${entries.length} palettes — each head resolves its own.`)}
      </EditorReadout>
    </div>
  )
}

/**
 * Slot Steps' `slots`: a setting's option names, in step order. With the target's setting in hand
 * the options are offered by their names on that setting, and a stored name the setting lacks is
 * drawn dashed — that head holds what is underneath on that step. On an effect palette [fromRig]
 * says the setting stands for the whole rig (every patched head's options of that name), so a name
 * may be typed as well as picked — a model patched later may have it — and a dashed one is on no
 * patched head. With no setting at all the names are typed: the desk matches them on each head's
 * own options, ignoring case, spaces and underscores, so *Gobo 3* is `GOBO_3` everywhere.
 */
export function FxSlotListPicker({
  label,
  value,
  setting,
  fromRig = false,
  readOnly,
  onChange,
}: {
  label: string
  value: string
  setting: SettingPropertyDescriptor | undefined
  fromRig?: boolean
  readOnly: boolean
  onChange: (value: string) => void
}) {
  const [typed, setTyped] = useState('')
  const entries = parseStepList(value)
  const write = (next: readonly string[]) => onChange(serializeStepList(next))

  return (
    <div className="flex flex-col gap-1.5" data-step-list="slots">
      <EditorLabel>{label}</EditorLabel>
      {entries.length > 0 && (
        <ul className="flex flex-col gap-1" aria-label={label}>
          {entries.map((entry, i) => {
            const option = slotOption(setting, entry)
            return (
              <StepChip
                key={`${entry}-${i}`}
                index={i}
                label={option?.displayName ?? entry}
                swatch={option?.colourPreview ?? null}
                missing={setting != null && option == null}
                readOnly={readOnly}
                onEarlier={i > 0 ? () => write(moveEarlier(entries, i)) : null}
                onRemove={() => write(entries.filter((_, j) => j !== i))}
              />
            )
          })}
        </ul>
      )}
      {setting != null && setting.options.length > 0 && (
        <Select value="" disabled={readOnly} onValueChange={(name) => name && write([...entries, name])}>
          <SelectTrigger aria-label={`Add to ${label}`} className="h-7 w-full text-xs">
            <SelectValue placeholder="Add a slot…" />
          </SelectTrigger>
          <SelectContent>
            {setting.options.map((o) => (
              <SelectItem key={o.name} value={o.name} className="text-xs">
                {o.displayName}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      )}
      {(fromRig || setting == null || setting.options.length === 0) && (
        <input
          aria-label={setting != null && setting.options.length > 0 ? `Type a slot for ${label}` : `Add to ${label}`}
          placeholder="Type a slot name, Enter to add"
          disabled={readOnly}
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          onKeyDown={(e) => {
            if (e.key !== 'Enter' || typed.trim() === '') return
            e.preventDefault()
            write([...entries, typed.trim()])
            setTyped('')
          }}
          className="h-7 rounded-md border bg-transparent px-2 text-xs"
        />
      )}
      <EditorReadout>
        {entries.length < 2
          ? 'Two slots or more — each head goes to its own level for each.'
          : setting != null && entries.some((e) => slotOption(setting, e) == null)
            ? fromRig
              ? 'A dashed slot is on no patched head — every head holds what is underneath on that step.'
              : 'A dashed slot is not on this head — it holds what is underneath on that step.'
            : 'Each head goes to its own level for each slot, and holds where it has none.'}
      </EditorReadout>
    </div>
  )
}
