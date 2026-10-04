import { useState } from 'react'
import { RotateCcw } from 'lucide-react'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import type { PatchPlacementInput } from '@/api/patchApi'
import type { SettingOption, SettingPropertyDescriptor } from '@/store/fixtures'
import { GOBO_PATTERNS } from '@/components/stage3d/goboPatterns'
import { findGel, type GelIndex } from '@/lib/gels'
import {
  fittedOption,
  mediaOver,
  normaliseMedia,
  takesGel,
  takesGobo,
  type FittedMedia,
  type FittedSlot,
} from '@/lib/fittedMedia'
import { cn } from '@/lib/utils'
import { GelPicker } from './GelPicker'

/** The patterns a slot can be fitted with — the Stage view's vocabulary, less its open layer. */
const FITTABLE_GOBOS = GOBO_PATTERNS.filter((name) => name !== 'open')

interface MediaBoxProps {
  /** The type's loadable settings (`loadableSettings`), the stock in their options. */
  settings: readonly SettingPropertyDescriptor[]
  gels: GelIndex
  /** The patch's own fitted media. */
  media: FittedMedia | null
  onMediaChange: (next: FittedMedia | null) => void
  /** The fixture's other placements — each a unit with media of its own. */
  placements: readonly PatchPlacementInput[]
  onPlacementsChange: (next: PatchPlacementInput[]) => void
}

/**
 * The **Media** box (fixture optics plan session 3, §4): what is loaded in each of a unit's loadable
 * settings — a scroller's string as an ordered list of gel swatches, a module wheel's slots as gobo
 * or gel pickers, a media frame's wing — for a type that has any. Beside the Lantern box, and it
 * saves the way that box does: nothing here writes, the form sends `media` (and the placements' own)
 * on Save.
 *
 * Every slot is in one of three states for the unit being edited: **stock** (the type's own, which
 * nothing is stored for), **fitted** with a gel or a gobo, or **empty** — fitted with nothing, so
 * the frame or slot is open. A placement is a unit of its own whose slots start from the patch's:
 * a slot the placement has not fitted shows the patch's, and *Reset* returns it there.
 */
export function MediaBox({ settings, gels, media, onMediaChange, placements, onPlacementsChange }: MediaBoxProps) {
  const [active, setActive] = useState(0)
  const units = [
    { key: 'fixture', label: placements.length > 0 ? 'Unit 1 · this one' : 'This unit' },
    ...placements.map((p, i) => ({
      key: p.uuid ?? `new-${i}`,
      label: `Unit ${i + 2}${p.label?.trim() ? ` · ${p.label.trim()}` : ''}`,
    })),
  ]
  const index = Math.min(active, units.length - 1)
  const placement = index > 0 ? placements[index - 1] : null
  // What this unit holds of its own, and what it falls back to before the stock.
  const own = placement ? placement.media ?? null : media
  const inherited = placement ? media : null

  const setSlot = (setting: string, option: string, slot: FittedSlot | null) => {
    const slots = { ...(own?.slots ?? {}) }
    const options = { ...(slots[setting] ?? {}) }
    if (slot == null) delete options[option]
    else options[option] = slot
    slots[setting] = options
    const next = normaliseMedia({ slots })
    if (placement) {
      onPlacementsChange(placements.map((p, i) => (i === index - 1 ? { ...p, media: next } : p)))
    } else {
      onMediaChange(next)
    }
  }

  return (
    <div className="space-y-2.5 rounded-md border border-border p-3" data-media-box>
      <p className="text-xs font-medium text-muted-foreground">Media</p>
      {units.length > 1 && (
        <div className="flex flex-wrap gap-1" role="tablist" aria-label="Units of this fixture">
          {units.map((u, i) => (
            <button
              key={u.key}
              type="button"
              role="tab"
              aria-selected={i === index}
              onClick={() => setActive(i)}
              className={cn(
                'rounded-md border px-2 py-1 text-xs',
                i === index ? 'border-primary bg-primary/10 text-foreground' : 'border-border text-muted-foreground hover:text-foreground',
              )}
            >
              {u.label}
            </button>
          ))}
        </div>
      )}
      {settings.map((setting) => (
        <MediaSetting
          key={setting.name}
          setting={setting}
          gels={gels}
          own={own?.slots?.[setting.name] ?? {}}
          inherited={inherited?.slots?.[setting.name] ?? {}}
          effective={mediaOver(own, inherited)}
          onSlot={(option, slot) => setSlot(setting.name, option, slot)}
        />
      ))}
      <p className="text-xs text-muted-foreground">
        {units.length > 1
          ? "What is loaded in each unit — stored on each one's placement, never in a look. A unit's slots start from this one's."
          : 'What is loaded in this unit — stored on the patch, never in a look.'}
      </p>
    </div>
  )
}

function MediaSetting({
  setting,
  gels,
  own,
  inherited,
  effective,
  onSlot,
}: {
  setting: SettingPropertyDescriptor
  gels: GelIndex
  own: Record<string, FittedSlot>
  inherited: Record<string, FittedSlot>
  effective: FittedMedia | null
  onSlot: (option: string, slot: FittedSlot | null) => void
}) {
  const options = setting.options
  // A string reads by frame number, as the manual numbers it (frame 0 is the open leader).
  const numbered = setting.media === 'GEL' && options.length > 2
  return (
    <section className="space-y-1" aria-label={setting.displayName} data-media-setting={setting.name}>
      <p className="text-xs font-medium">
        {setting.displayName}
        <span className="ml-1.5 font-normal text-muted-foreground">
          · {setting.media === 'GEL' ? 'gels' : setting.media === 'GOBO' ? 'gobos' : 'gobos or gels'}
        </span>
      </p>
      <ol className="space-y-0.5">
        {options.map((option, i) =>
          option.loadable === false ? null : (
            <MediaSlotRow
              key={option.name}
              number={numbered ? i : null}
              setting={setting}
              option={option}
              gels={gels}
              own={own[option.name]}
              inherited={inherited[option.name]}
              shown={fittedOption(option, effective?.slots?.[setting.name]?.[option.name], gels)}
              onSlot={(slot) => onSlot(option.name, slot)}
            />
          ),
        )}
      </ol>
    </section>
  )
}

function MediaSlotRow({
  number,
  setting,
  option,
  gels,
  own,
  inherited,
  shown,
  onSlot,
}: {
  number: number | null
  setting: SettingPropertyDescriptor
  option: SettingOption
  gels: GelIndex
  own: FittedSlot | undefined
  inherited: FittedSlot | undefined
  shown: SettingOption
  onSlot: (slot: FittedSlot | null) => void
}) {
  const [picking, setPicking] = useState(false)
  const content = own ?? inherited
  const gel = content?.gel ? findGel(gels, content.gel) : null
  const state = own ? 'fitted' : inherited ? 'unit' : 'stock'
  const stockName = option.displayName
  const label =
    content == null
      ? stockLabel(option)
      : content.gel
        ? gel ? `${gel.code} ${gel.name}` : content.gel
        : content.gobo
          ? `Gobo · ${content.gobo.replace(/_/g, ' ')}`
          : 'Empty'
  return (
    <li
      className="flex items-center gap-2 rounded px-1 py-0.5 hover:bg-muted/40"
      data-media-slot={option.name}
      data-state={state}
    >
      {number != null && <span className="w-5 shrink-0 text-right font-mono text-[11px] text-muted-foreground">{number}</span>}
      <span
        className="block size-4 shrink-0 rounded-sm border"
        aria-hidden
        style={{
          background: shown.gobo ? 'repeating-linear-gradient(45deg, #666 0 2px, transparent 2px 5px)' : (shown.colourPreview ?? 'transparent'),
          borderStyle: shown.colourPreview || shown.gobo ? 'solid' : 'dashed',
        }}
      />
      <span className="min-w-0 flex-1 truncate text-xs" title={content ? `${label} — fitted in ${stockName}` : `${stockName} (stock)`}>
        {label}
        {state !== 'stock' && <span className="ml-1 text-[10px] text-muted-foreground">· {state === 'fitted' ? 'fitted' : 'from unit 1'}</span>}
      </span>
      {takesGel(setting) && (
        <Popover open={picking} onOpenChange={setPicking}>
          <PopoverTrigger asChild>
            <button
              type="button"
              className="h-6 shrink-0 rounded border border-border px-1.5 text-[11px] text-muted-foreground hover:text-foreground"
              aria-label={`Fit a gel in ${stockName}`}
            >
              Gel…
            </button>
          </PopoverTrigger>
          <PopoverContent className="w-72 p-0 overflow-hidden" align="end">
            <GelPicker
              value={content?.gel ?? null}
              autoFocus
              onPick={(code) => {
                // *Open white* in the picker is an empty frame: fitted, with nothing in it.
                onSlot(code ? { gel: code } : {})
                setPicking(false)
              }}
            />
          </PopoverContent>
        </Popover>
      )}
      {takesGobo(setting) && (
        <select
          className="h-6 shrink-0 rounded border border-border bg-background px-1 text-[11px] text-muted-foreground"
          aria-label={`Fit a gobo in ${stockName}`}
          value={content?.gobo ?? ''}
          onChange={(e) => onSlot(e.target.value ? { gobo: e.target.value } : {})}
        >
          <option value="">{content?.gobo ? 'Empty' : 'Gobo…'}</option>
          {FITTABLE_GOBOS.map((name) => (
            <option key={name} value={name}>
              {name.replace(/_/g, ' ')}
            </option>
          ))}
        </select>
      )}
      <button
        type="button"
        className="grid size-6 shrink-0 place-items-center rounded text-muted-foreground hover:text-foreground disabled:opacity-30"
        aria-label={`Reset ${stockName} to ${inherited ? "unit 1's" : 'stock'}`}
        title={inherited ? "Back to unit 1's" : 'Back to stock'}
        disabled={!own}
        onClick={() => onSlot(null)}
      >
        <RotateCcw className="size-3.5" />
      </button>
    </li>
  )
}

/** A slot's stock content in words: its stock gel by name, its pattern, or empty. */
function stockLabel(option: SettingOption): string {
  if (option.gobo) return `Gobo · ${option.gobo.replace(/_/g, ' ')}`
  if (option.colourPreview) return option.displayName
  return `${option.displayName} · empty`
}
