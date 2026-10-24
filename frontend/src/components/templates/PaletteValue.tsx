import { AudioWaveform } from 'lucide-react'
import { fxSpeedLabel } from '@/components/fx/fxConstants'
import { isAroundSpelling } from '@/components/fx/fxEditorModel'
import { describePaletteRows, paletteRowsSwatch } from '@/lib/paletteIntent'
import type { PaletteSummary } from '@/api/palettesApi'

/**
 * The Value read-out — the template's contents in its own grammar, never resolved against a head
 * (`paletteIntent.ts` serialises and parses only). Every reader takes `rows ?? []`: an effect
 * template's empty list is omitted on the wire.
 */
export function PaletteValue({ palette: template }: { palette: PaletteSummary }) {
  if (template.kind === 'fx') {
    const effect = template.fx
    const speed = effect == null ? null : fxSpeedLabel(effect.cycle, effect.timingSource, effect.seconds)
    const around = effect != null && isAroundSpelling(effect) ? 'around' : null
    const text = effect == null ? 'FX' : [effect.fxType, speed, around].filter((p) => p != null && p !== '').join(' · ')
    return (
      <span className="mx-1.5 flex min-w-0 items-center gap-1.5 text-xs text-muted-foreground" title={text}>
        <AudioWaveform className="size-3.5 shrink-0 text-violet-400" />
        <span className="truncate">{text}</span>
      </span>
    )
  }
  const rows = template.rows ?? []
  if (!template.isGeneric) {
    const heads = new Set(rows.map((r) => r.targetKey)).size
    return (
      <span className="mx-1.5 truncate text-xs text-muted-foreground">
        {heads} head{heads === 1 ? '' : 's'} · per fixture
      </span>
    )
  }
  const swatch = paletteRowsSwatch(rows)
  const described = describePaletteRows(rows)
  return (
    <span className="mx-1.5 flex min-w-0 items-center gap-1.5" title={described}>
      {swatch != null && (
        <span className="size-4 shrink-0 rounded-sm border border-border/60" style={{ background: swatch }} />
      )}
      <span className="truncate font-mono text-xs tabular-nums text-muted-foreground">{described}</span>
    </span>
  )
}
