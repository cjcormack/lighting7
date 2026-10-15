import { useCallback } from 'react'
import { Slider } from '@/components/ui/slider'
import { whiteLevelsFor, whiteLevelsForKelvin, whiteMixColour, whiteMixKelvin, type Rgb, type WhiteMix } from '@/lib/whiteMix'
import { EditorField } from './EditorField'
import { EditorLabel } from './EditorLabel'
import { EditorReadout } from './EditorReadout'
import { numericSeed } from './useEditorKeyboard'

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
}: {
  mix: WhiteMix
  colour: Rgb
  onColourChange: (colour: Rgb) => void
  /** The editor was opened from the keyboard: Warmth takes the typed seed, as a cell's first field does. */
  keyboardOpen?: string | null
}) {
  const levels = whiteLevelsFor(mix, colour)
  const level = Math.max(levels.warm, levels.cold)
  const kelvin = whiteMixKelvin(mix, levels.warm, levels.cold) ?? mix.warmKelvin

  const commit = useCallback(
    (nextKelvin: number, nextLevel: number) => {
      const next = whiteLevelsForKelvin(mix, nextKelvin, nextLevel)
      onColourChange(whiteMixColour(mix, next.warm, next.cold))
    },
    [mix, onColourChange],
  )
  // A warmth chosen with the level at 0 would be lost to black, so it lands at full.
  const commitKelvin = useCallback((k: number) => commit(k, level > 0 ? level : 255), [commit, level])
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
            className="flex-1"
            aria-label="Level"
          />
          <EditorField label="Level" unit="%" min={0} max={100} value={toPct(level)} onCommit={commitLevel} className="w-[72px] shrink-0" />
        </div>
      </div>
      <EditorReadout>
        <span>
          Warm {mix.warmKelvin} K · cold {mix.coldKelvin} K — any colour lands as their nearest mix
        </span>
      </EditorReadout>
    </div>
  )
}
