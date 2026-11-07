import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Slider } from '@/components/ui/slider'
import type { PaletteIntent } from '@/lib/paletteIntent'
import { useRecordRecent } from '@/store/recentValues'
import { useSlideEnd } from '@/components/editor/useSlideEnd'
import { RecentValues } from '@/components/editor/RecentValues'
import { parseRecentPercent, recentPaletteLevelKey, serializeRecentPercent } from '@/components/editor/recentValueSpelling'

/**
 * One percentage of each head's own range — a level, a strobe, a beam role. Its Recent band is the
 * palette editor's own list for [attribute], `palette-level:` (D15): a share of each head's range,
 * which the grid's `level:` list is not. A drag records on its commit, a tap at once.
 */
export function PercentControl({
  label,
  hint,
  attribute,
  value,
  onChange,
  projectId,
}: {
  label: string
  hint?: string
  /** The palette attribute it sets, which names its Recent list. */
  attribute: string
  value: PaletteIntent | undefined
  onChange: (intent: PaletteIntent | null) => void
  /** The palette's project: its Recent band is drawn only while that project is the live one. */
  projectId?: number
}) {
  const current = value?.kind === 'percent' ? value.value : null
  const recentKey = recentPaletteLevelKey(attribute)
  const recent = useRecordRecent(recentKey, projectId)
  const slideEnd = useSlideEnd(recent.slideEnd)
  const pickRecent = (recorded: string) => {
    const pct = parseRecentPercent(recorded)
    if (pct == null) return
    onChange({ kind: 'percent', value: pct })
    recent.now(recorded)
  }

  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between gap-2">
        <Label>{label}</Label>
        <div className="flex items-center gap-2">
          <span className="font-mono text-xs tabular-nums text-muted-foreground">
            {current == null ? '—' : `${current}%`}
          </span>
          {current != null && (
            <Button type="button" variant="ghost" size="sm" onClick={() => onChange(null)}>
              Clear
            </Button>
          )}
        </div>
      </div>
      <Slider
        min={0}
        max={100}
        step={1}
        value={[current ?? 0]}
        onValueChange={([next]) => {
          onChange({ kind: 'percent', value: next })
          recent.stage(serializeRecentPercent(next))
        }}
        {...slideEnd}
      />
      <RecentValues listKey={recentKey} projectId={projectId} onPick={pickRecent} />
      {hint && <p className="text-[11px] text-muted-foreground">{hint}</p>}
    </div>
  )
}
