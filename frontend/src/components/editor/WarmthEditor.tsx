import { useCallback } from 'react'
import { Slider } from '@/components/ui/slider'
import { whiteLevelsFor, whiteLevelsForKelvin, whiteMixColour, whiteMixKelvin, type Rgb, type WhiteMix } from '@/lib/whiteMix'
import { EditorField } from './EditorField'
import { EditorLabel } from './EditorLabel'
import { EditorReadout } from './EditorReadout'
import { numericSeed } from './useEditorKeyboard'
import { RecentValues } from './RecentValues'
import { parseRecentWarmth, RECENT_WARMTH, serializeRecentWarmth } from './recentValueSpelling'
import { useRecordRecent } from '@/store/recentValues'
import { useSlideEnd } from './useSlideEnd'

const toPct = (byte: number) => Math.round((byte / 255) * 100)

/** A warm/cold white pair's reading: its temperature and level, or *Off*. */
export function warmthReading(mix: WhiteMix, colour: Rgb): string {
  const levels = whiteLevelsFor(mix, colour)
  const level = Math.max(levels.warm, levels.cold)
  if (level === 0) return 'Off'
  return `${whiteMixKelvin(mix, levels.warm, levels.cold)} K · ${toPct(level)}%`
}

/**
 * The controls a warm/cold white pair is edited with (venue-visit plan D8): a **warmth** between
 * the pair's declared temperatures and a **level**, read from [colour] — what the pair's two
 * channels make — and committing the colour the chosen mix makes, which the desk lands as that mix
 * again (`lib/whiteMix.ts`). Shared by the fixtures table's Colour cell and the fixture sheet's row.
 */
export function WarmthEditor({
  mix,
  colour,
  onColourChange,
  keyboardOpen,
  projectId,
}: {
  mix: WhiteMix
  colour: Rgb
  onColourChange: (colour: Rgb) => void
  /** The editor was opened from the keyboard: Warmth takes the typed seed, as a cell's first field does. */
  keyboardOpen?: string | null
  /** The project the host edits, for the Recent band; absent, the live one. */
  projectId?: number
}) {
  const levels = whiteLevelsFor(mix, colour)
  const level = Math.max(levels.warm, levels.cold)
  const kelvin = whiteMixKelvin(mix, levels.warm, levels.cold) ?? mix.warmKelvin

  // A gesture's end records the kelvin and the level it made its colour from (D15, D17).
  const recent = useRecordRecent(RECENT_WARMTH, projectId)
  const commit = useCallback(
    (nextKelvin: number, nextLevel: number) => {
      const next = whiteLevelsForKelvin(mix, nextKelvin, nextLevel)
      onColourChange(whiteMixColour(mix, next.warm, next.cold))
      recent.stage(serializeRecentWarmth(nextKelvin, toPct(nextLevel)))
    },
    [mix, onColourChange, recent],
  )
  const pickRecent = (value: string) => {
    const warmth = parseRecentWarmth(value)
    if (warmth == null) return
    const next = whiteLevelsForKelvin(mix, warmth.kelvin, Math.round((warmth.pct / 100) * 255))
    onColourChange(whiteMixColour(mix, next.warm, next.cold))
    recent.now(value)
  }
  const slideEnd = useSlideEnd(recent.slideEnd)
  const fieldDone = (raw: string | null) => raw === null && recent.flush()
  // A warmth chosen with the level at 0 would be lost to black, so it lands at full. The typed
  // field does not clamp, so the kelvin is held to the pair's own range here — what is recorded
  // is the warmth the mix makes.
  const commitKelvin = useCallback(
    (k: number) => commit(Math.max(mix.warmKelvin, Math.min(mix.coldKelvin, k)), level > 0 ? level : 255),
    [commit, level, mix.warmKelvin, mix.coldKelvin],
  )
  const commitLevel = useCallback((pct: number) => commit(kelvin, Math.round((pct / 100) * 255)), [commit, kelvin])

  return (
    <div className="space-y-2">
      <div className="space-y-1">
        <EditorLabel>Warmth</EditorLabel>
        <div className="flex items-center gap-2.5">
          <Slider
            min={mix.warmKelvin}
            max={mix.coldKelvin}
            step={50}
            value={[kelvin]}
            onValueChange={([next]) => commitKelvin(next)}
            {...slideEnd}
            className="flex-1"
            aria-label="Warmth"
          />
          <EditorField
            label="Warmth"
            unit="K"
            min={mix.warmKelvin}
            max={mix.coldKelvin}
            value={kelvin}
            onCommit={commitKelvin}
            onDraft={fieldDone}
            seed={numericSeed(keyboardOpen ?? null)}
            className="w-[84px] shrink-0"
          />
        </div>
      </div>
      <div className="space-y-1">
        <EditorLabel>Level</EditorLabel>
        <div className="flex items-center gap-2.5">
          <Slider
            min={0}
            max={100}
            step={1}
            value={[toPct(level)]}
            onValueChange={([next]) => commitLevel(next)}
            {...slideEnd}
            className="flex-1"
            aria-label="Level"
          />
          <EditorField label="Level" unit="%" min={0} max={100} value={toPct(level)} onCommit={commitLevel} onDraft={fieldDone} className="w-[72px] shrink-0" />
        </div>
      </div>
      <EditorReadout>
        <span>
          Warm {mix.warmKelvin} K · cold {mix.coldKelvin} K — any colour lands as their nearest mix
        </span>
      </EditorReadout>
      <RecentValues listKey={RECENT_WARMTH} projectId={projectId} onPick={pickRecent} />
    </div>
  )
}
