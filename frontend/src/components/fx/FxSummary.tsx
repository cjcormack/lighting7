import { Badge } from '@/components/ui/badge'
import { Layers, LayoutGrid, ListOrdered } from 'lucide-react'
import { cn } from '@/lib/utils'
import {
  FX_FAMILY_INFO,
  fxSpeedLabel,
  secondsLabel,
  getOrderLabel,
  getElementModeLabel,
  getElementFilterLabel,
} from './fxConstants'
import { resolveColourToHex, isPaletteRef } from './colourUtils'
import { useColourPalettes, type ColourPalettes } from './FxColourPalettes'
import { SpeedMasterChip } from './SpeedMasterChip'
import type { FxSummaryData } from './fxSummaryTypes'
import { isOnSeconds } from '@/lib/fxTiming'
import { fxTargetLabel, parseSelectionKey } from '@/lib/fxTargets'
import { parseStepList, slotLabel } from '@/lib/stepFx'

export interface FxSummaryProps {
  /** Normalised effect data */
  effect: FxSummaryData
  /** Optional target indicator (fixture/group icon + key; a selection's items, effects plan D3) */
  target?: { type: 'group' | 'fixture' | 'selection'; key: string } | null
  /** Optional badge text (e.g. "via GroupName", element mode label) */
  badge?: string | null
  /** Running state: false dims the item */
  isRunning?: boolean
  /** Makes the component clickable with hover effect */
  onClick?: () => void
  /** Slot for action buttons rendered on the right side */
  actions?: React.ReactNode
  className?: string
}

export function FxSummary({
  effect,
  target,
  badge,
  isRunning,
  onClick,
  actions,
  className,
}: FxSummaryProps) {
  const colourPalettes = useColourPalettes()
  const categoryInfo = FX_FAMILY_INFO[effect.family]
  const CategoryIcon = categoryInfo?.icon
  // An effect type the library no longer resolves has no timing source, so its division could be
  // beats or seconds — the number is dropped rather than guessed, unless the flag says Seconds.
  const speedLabel = effect.seconds
    ? secondsLabel(effect.cycle)
    : fxSpeedLabel(effect.cycle, effect.timingSource, isOnSeconds(effect, effect.timingSource))
  const { colourSwatches, textParams } = getCustomParams(effect, colourPalettes)

  return (
    <div
      className={cn(
        'flex items-center gap-2 p-2 rounded border text-sm',
        isRunning === false && 'opacity-50',
        onClick && 'cursor-pointer hover:bg-accent/50 transition-colors',
        className,
      )}
      onClick={onClick}
    >
      {CategoryIcon && <CategoryIcon className="size-4 text-muted-foreground shrink-0" />}
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-1.5 flex-wrap">
          <span className="font-medium truncate">{effect.fxType}</span>
          {/* Only rendered when the effect runs on a master other than M1. */}
          <SpeedMasterChip speedMasterUuid={effect.speedMasterUuid} />
          <SpeedMasterChip speedMasterUuid={effect.rateSpeedMasterUuid} kind="rate" />
          {badge && (
            <Badge variant="outline" className="text-[10px] leading-tight px-1 py-0 shrink-0">
              {badge}
            </Badge>
          )}
          {target && <TargetIndicator target={target} />}
        </div>
        <div className="text-xs text-muted-foreground flex items-center gap-1.5 flex-wrap">
          {speedLabel != null && <span>{speedLabel}</span>}
          {effect.blendMode !== 'OVERRIDE' && (
            <>
              <Dot />
              <span className="lowercase">{effect.blendMode}</span>
            </>
          )}
          {effect.order && effect.order !== 'UNIFIED' && (
            <>
              <Dot />
              <span>{getOrderLabel(effect.order)}</span>
            </>
          )}
          {effect.timing === 'HEAD' && (
            <>
              <Dot />
              <span>per head</span>
            </>
          )}
          {effect.phaseOffset != null && effect.phaseOffset !== 0 && (
            <>
              <Dot />
              <span>phase {effect.phaseOffset}</span>
            </>
          )}
          {effect.elementMode && (
            <>
              <Dot />
              <span>{getElementModeLabel(effect.elementMode)}</span>
            </>
          )}
          {effect.elementFilter && effect.elementFilter !== 'ALL' && (
            <>
              <Dot />
              <span>{getElementFilterLabel(effect.elementFilter)}</span>
            </>
          )}
          {colourSwatches.length > 0 && (
            <>
              <Dot />
              <span className="flex items-center gap-0.5">
                {colourSwatches.map((swatch, i) => (
                  <ColourDot key={i} hex={swatch.hex} title={swatch.title} />
                ))}
              </span>
            </>
          )}
          {textParams.map((param) => (
            <span key={param.label} className="flex items-center gap-1">
              <Dot />
              <span>{param.label} {param.value}</span>
            </span>
          ))}
        </div>
      </div>

      {actions && (
        <div className="flex items-center gap-0.5 shrink-0">
          {actions}
        </div>
      )}
    </div>
  )
}

// ─── Sub-components ─────────────────────────────────────────────────────────

function TargetIndicator({ target }: { target: { type: 'group' | 'fixture' | 'selection'; key: string } }) {
  if (target.type === 'selection') {
    const items = parseSelectionKey(target.key)
    return (
      <span
        className="flex items-center gap-0.5 text-[11px] text-muted-foreground shrink-0"
        title={`Over ${items.map((t) => t.key).join(', ')}, in that order`}
      >
        <ListOrdered className="size-3" />
        {fxTargetLabel({ targetKey: target.key, isGroupTarget: false, targetType: 'selection', targets: items })}
      </span>
    )
  }
  return (
    <span className="flex items-center gap-0.5 text-[11px] text-muted-foreground shrink-0">
      {target.type === 'group' ? (
        <Layers className="size-3" />
      ) : (
        <LayoutGrid className="size-3" />
      )}
      {target.key}
    </span>
  )
}

function Dot() {
  return <span className="text-muted-foreground/50">&middot;</span>
}

function ColourDot({ hex, title }: { hex: string; title?: string }) {
  return (
    <span
      title={title}
      className="inline-block size-2.5 rounded-full border border-border shrink-0"
      style={{ backgroundColor: hex }}
    />
  )
}

// ─── Custom parameter extraction ────────────────────────────────────────────

interface TextParam {
  label: string
  value: string
}

interface Swatch {
  hex: string
  /** Hover text: the template's name where the value names one, the literal otherwise. */
  title: string
}

/**
 * Extract custom parameters for display.
 * Colour/ColourList params → flat array of swatches.
 * Other params → labelled text values.
 *
 * A `pal:` value resolves through [ColourPalettes] rather than the literal parser, so a summary
 * shows the colour the template *currently* holds — and names it on hover, because the whole reason
 * to reference one is that it has a name.
 */
function getCustomParams(
  effect: FxSummaryData,
  colourPalettes: ColourPalettes,
): { colourSwatches: Swatch[]; textParams: TextParam[] } {
  const colourSwatches: Swatch[] = []
  const textParams: TextParam[] = []
  const maxSwatches = 8

  const swatchFor = (raw: string): Swatch =>
    isPaletteRef(raw)
      ? {
          hex: colourPalettes.swatchFor(raw) ?? 'transparent',
          title: colourPalettes.labelFor(raw),
        }
      : { hex: resolveColourToHex(raw), title: raw }

  for (const [name, value] of Object.entries(effect.parameters)) {
    if (!value) continue

    const paramDef = effect.parameterDefs?.find((p) => p.name === name)
    const paramType = paramDef?.type.toLowerCase()

    if (paramType === 'colour') {
      if (colourSwatches.length < maxSwatches) {
        colourSwatches.push(swatchFor(value))
      }
    } else if (paramType === 'palettelist') {
      // Palette Steps' palettes (effects plan §3.6): references, never text worth reading — a count,
      // and a swatch for each colour palette among them.
      const palettes = parseStepList(value)
      textParams.push({ label: 'Steps', value: `${palettes.length} palette${palettes.length === 1 ? '' : 's'}` })
      for (const ref of palettes) {
        if (colourSwatches.length >= maxSwatches) break
        const hex = colourPalettes.swatchFor(ref)
        if (hex != null) colourSwatches.push({ hex, title: colourPalettes.labelFor(ref) })
      }
    } else if (paramType === 'slotlist') {
      // Slot Steps' slots, by name as an operator reads one: `GOBO_3` is *Gobo 3*.
      textParams.push({ label: 'Slots', value: parseStepList(value).map(slotLabel).join(' · ') })
    } else if (paramType === 'colourlist') {
      const colours = value.split(',').map((c) => c.trim()).filter(Boolean)
      for (const c of colours) {
        if (colourSwatches.length >= maxSwatches) break
        colourSwatches.push(swatchFor(c))
      }
    } else {
      const label = name
        .replace(/([A-Z])/g, ' $1')
        .replace(/^./, (s) => s.toUpperCase())
        .trim()
      textParams.push({ label, value })
    }
  }

  return { colourSwatches, textParams }
}
