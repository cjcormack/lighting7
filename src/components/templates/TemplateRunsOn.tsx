import { useMemo } from 'react'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { useVisibleFixtureListQuery } from '@/store/fixtures'
import { useMaster1Uuid, useSpeedMasterBpm, useSpeedMasterDisplay } from '@/store/speedMasters'
import { effectSpeedLabel } from '@/components/fx/fxConstants'
import { isTemplateRef, resolveColourToHex } from '@/components/fx/colourUtils'
import { useColourTemplates } from '@/components/fx/FxColourTemplates'
import { fixturesSupportingFamily, type AttributeFamily } from '@/lib/attributeFamily'
import type { EffectLibraryEntry } from '@/store/fixtureFx'
import type { TemplateEffect } from '@/api/templatesApi'

/** Beats in a bar. The desk has no time signature; every division label is quoted against 4/4. */
const BEATS_PER_BAR = 4

/** Family tints for the preview strip, for an effect with no colour of its own to show. */
const FAMILY_TINT: Record<AttributeFamily, [string, string]> = {
  INTENSITY: ['#FFFFFF', '#1A1A1A'],
  COLOUR: ['#A78BFA', '#1E1035'],
  POSITION: ['#38BDF8', '#0A2233'],
  BEAM: ['#FBBF24', '#2A1A00'],
}

/**
 * The effect branch's answer to `TemplateResolvesTo` — *what will this run on, and how fast?*
 *
 * The two panels ask genuinely different questions, which is why this is a sibling rather than a
 * branch inside that one. A **value** resolves differently per head, so its panel is a per-head list
 * from the server's own resolver and every row can carry a clamp or a snap. An **effect** is one
 * rule applied to every head the layer names, with the distribution spreading its phase — so there
 * is nothing per-head to list, and the interesting facts are *how many heads could take it* and
 * *how fast it goes*.
 *
 * **It asks the server nothing**, and that is the second difference. `TemplateResolvesTo` posts the
 * draft to `/templates/resolve` because colour degradation and wheel snapping are arithmetic the
 * resolver must own. Here there is no such arithmetic: the head count is a capability filter over
 * the patch, and the strip is a drawing of the division against the tempo. It is a **preview, not a
 * promise** — the caption says so, and nothing here should grow into a simulation of the effect.
 */
export function TemplateRunsOn({
  family,
  effect,
  entry,
}: {
  family: AttributeFamily
  effect: TemplateEffect
  /**
   * The library entry for `effect.effectType`.
   *
   * This panel renders a **draft**, which has never been near the server and so carries no
   * `TemplateEffect.timingSource` — that field is resolved on read. The editor has the entry in
   * hand anyway (it needs one to render the parameters at all), so the rule across the two surfaces
   * is: *a draft asks the library, a saved template carries the answer*.
   */
  entry: EffectLibraryEntry | undefined
}) {
  const { data: fixtures } = useVisibleFixtureListQuery()
  const master1 = useMaster1Uuid()
  // A null `speedMasterUuid` means master 1, and the bpm lookup needs the real row's uuid — an
  // `''`/null key matches no master in the live bank. Same resolution `BeatIndicator` makes.
  const resolvedMaster = effect.speedMasterUuid ?? master1
  const bpm = useSpeedMasterBpm(resolvedMaster)
  const display = useSpeedMasterDisplay(effect.speedMasterUuid)
  // A WALL_CLOCK effect never reads `speedMasterUuid`; its cycle is scaled by the *rate* master,
  // and a null one means **unscaled** rather than master 1. Naming master 1 there would claim a
  // tempo link the effect does not have.
  const rateDisplay = useSpeedMasterDisplay(effect.rateSpeedMasterUuid)
  const colours = useColourTemplates()

  const heads = useMemo(() => fixturesSupportingFamily(fixtures, family), [fixtures, family])
  const isWallClock = entry?.timingSource === 'WALL_CLOCK'

  /**
   * Up to two colours off the effect's own `colour` parameters, so a colour effect previews in the
   * colours it will actually run in. A `tmpl:` reference resolves through the same lookup the
   * pickers use; anything else falls back to the family tint rather than to black, which would read
   * as a real (and wrong) preview.
   */
  const [from, to] = useMemo(() => {
    const tint = FAMILY_TINT[family]
    const names = (entry?.parameters ?? [])
      .filter((param) => param.type.toLowerCase() === 'colour')
      .map((param) => param.name)
    const swatches = names
      .map((name) => {
        const value = effect.parameters[name]
        if (value == null || value === '') return null
        return isTemplateRef(value) ? colours.swatchFor(value) : resolveColourToHex(value)
      })
      .filter((hex): hex is string => hex != null)
    if (swatches.length === 0) return tint
    return [swatches[0], swatches[1] ?? tint[1]]
  }, [entry, effect.parameters, family, colours])

  // One cycle as a fraction of the strip: a bar for a beat effect (so a 1-bar division fills it
  // exactly and a 2-bar one shows half a cycle), the whole strip for a wall-clock one, whose cycle
  // is what the strip *is*.
  const cyclePct = isWallClock
    ? 100
    : Math.max(4, Math.min(200, (effect.beatDivision / BEATS_PER_BAR) * 100))

  // No entry means no timing source, and `effectSpeedLabel` answers null for exactly that — the
  // two readings of `beatDivision` are a tempo apart, so the number is dropped rather than guessed.
  const division = entry == null ? null : effectSpeedLabel(effect.beatDivision, entry.timingSource ?? 'BEAT')
  const cycleWord = isWallClock ? 'One cycle' : 'One bar'
  const speedLabel = division == null ? cycleWord : `${cycleWord} · ${division}`
  const masterLabel = isWallClock
    ? effect.rateSpeedMasterUuid == null
      ? 'Unscaled'
      : rateDisplay
        ? `M${rateDisplay.index} · ${rateDisplay.name}`
        : 'M1'
    : display
      ? `M${display.index} · ${display.name}`
      : 'M1'

  return (
    <div className="space-y-1.5">
      <div className="flex items-center gap-2">
        <Label>Runs on</Label>
        <Badge variant="secondary" className="text-[10px] px-1.5 py-0 tabular-nums">
          {heads} head{heads === 1 ? '' : 's'}
        </Badge>
      </div>
      <div className="rounded-md border p-3 space-y-2">
        <p className="text-[11px]">
          {DESCRIPTION[family]} The same effect on every head, its phase spread by the distribution.
        </p>

        <div>
          <div className="flex items-center justify-between gap-2">
            <span className="text-[9px] font-bold uppercase tracking-wider text-muted-foreground">
              {speedLabel}
            </span>
            <span className="font-mono text-[9px] font-bold uppercase tracking-wider text-muted-foreground tabular-nums">
              {isWallClock ? masterLabel : `${masterLabel} · ${bpm == null ? '—' : Math.round(bpm)} bpm`}
            </span>
          </div>
          <div
            className="mt-1 h-5 rounded border border-border/60"
            style={{
              background: `repeating-linear-gradient(to right, ${from} 0%, ${to} ${cyclePct / 2}%, ${from} ${cyclePct}%)`,
            }}
            aria-hidden
          />
          {!isWallClock && (
            <div className="mt-0.5 flex items-center justify-between font-mono text-[9px] text-muted-foreground">
              {Array.from({ length: BEATS_PER_BAR }, (_, i) => (
                <span key={i}>{i + 1}</span>
              ))}
            </div>
          )}
        </div>

        <p className="text-[10px] text-muted-foreground">
          A preview of the shape and the speed, not of the output — what each head actually shows
          depends on the emitters it has.
        </p>
      </div>
    </div>
  )
}

/** Who the effect can land on, in the same "any fixture with…" grammar the library row uses. */
const DESCRIPTION: Record<AttributeFamily, string> = {
  INTENSITY: 'Any fixture with a dimmer.',
  COLOUR: 'Any fixture with colour.',
  POSITION: 'Any moving head.',
  BEAM: 'Any fixture with the beam role.',
}
