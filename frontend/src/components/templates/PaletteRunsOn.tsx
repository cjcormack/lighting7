import { useMemo, useState } from 'react'
import { FX_ATTRIBUTE_SENTINELS } from '@/lib/fxTargetAttributes'
import { cn } from '@/lib/utils'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { useVisibleFixtureListQuery } from '@/store/fixtures'
import { useMaster1Uuid, useSpeedMasterBpm, useSpeedMasterDisplay } from '@/store/speedMasters'
import { isOnSeconds } from '@/lib/fxTiming'
import { NOTIONAL_HEADS, NOTIONAL_HEADS_WORD, fxSpeedLabel } from '@/components/fx/fxConstants'
import { useWindowSelectionTargets } from '@/components/fx/selectionFxTarget'
import { findFxEntry } from '@/components/busking/buskingTypes'
import { FxGraphStrip } from '@/components/fxEditor/FxGraphStrip'
import { previewDraftOf, storedFxAttribute } from '@/components/fxEditor/fxStripDraft'
import { useFxPreview } from '@/components/fxEditor/useFxPreview'
import { fixturesSupportingFamily, type AttributeFamily } from '@/lib/attributeFamily'
import { attributesFor } from '@/lib/fxTargetAttributes'
import { useFxLibraryQuery, type FxLibraryEntry } from '@/store/fixtureFx'
import { usePaletteListQuery } from '@/store/palettes'
import type { PaletteFx } from '@/api/palettesApi'

type StripOn = 'selection' | 'notional'

/**
 * The effect branch's answer to `PaletteResolvesTo` — *what will this run on, and how fast?*
 *
 * The two panels ask genuinely different questions, which is why this is a sibling rather than a
 * branch inside that one. A **value** resolves differently per head, so its panel is a per-head list
 * from the server's own resolver and every row can carry a clamp or a snap. An **effect** is one
 * rule applied to every head the layer names, with the distribution spreading its phase — so there
 * is nothing per-head to list, and the interesting facts are *how many heads could take it* and
 * *how fast it goes*.
 *
 * The head count is a capability filter over the patch. Under it is the FX editor's **graph strip**
 * (FX editor plan D15, §4.6), the desk's own samples of the draft, redrawn as it changes: on the
 * window's selection when there is one, as the lane would be over what it holds now, or on eight
 * notional heads — heads the rig does not have — as the FX alone. *On the selection · On eight
 * heads* switches between them. The client computes none of it (P2).
 */
export function PaletteRunsOn({
  projectId,
  family,
  effect,
  entry,
}: {
  projectId: number
  family: AttributeFamily
  effect: PaletteFx
  /**
   * The library entry for `effect.fxType`.
   *
   * This panel renders a **draft**, which has never been near the server and so carries no
   * `PaletteFx.timingSource` — that field is resolved on read. The editor has the entry in
   * hand anyway (it needs one to render the parameters at all), so the rule across the two surfaces
   * is: *a draft asks the library, a saved template carries the answer*.
   */
  entry: FxLibraryEntry | undefined
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

  // A beam effect drives the property the palette names (Slot Steps' setting) or else the one it
  // declares, so it runs on the heads that have that one — never a sentinel, which names none.
  const declared = entry?.compatibleAttributes.length === 1 ? entry.compatibleAttributes[0] : null
  const beamAttribute =
    family === 'BEAM'
      ? (effect.attribute || (declared != null && !FX_ATTRIBUTE_SENTINELS.has(declared) ? declared : null))
      : null
  const heads = useMemo(
    () =>
      beamAttribute == null
        ? fixturesSupportingFamily(fixtures, family)
        : (fixtures ?? []).filter((fixture) => attributesFor([{ type: 'fixture', fixture }]).has(beamAttribute)).length,
    [fixtures, family, beamAttribute],
  )
  // Timed in seconds: a wall-clock effect, or a beat effect on Seconds (D7) — no master's tempo to quote.
  const isWallClock = entry?.timingSource === 'WALL_CLOCK' || effect.seconds === true
  const onSeconds = isOnSeconds(effect, entry?.timingSource)

  // No entry means no timing source, and `fxSpeedLabel` answers null for exactly that — the
  // two readings of `cycle` are a tempo apart, so the number is dropped rather than guessed.
  const division = entry == null ? null : fxSpeedLabel(effect.cycle, entry.timingSource ?? 'BEAT', effect.seconds)
  const speedLabel = division ?? (isWallClock ? 'One cycle' : 'One bar')
  const masterLabel = isWallClock
    ? onSeconds
      ? 'Seconds — no master'
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
          {beamAttribute != null ? `Any fixture with ${beamAttribute}.` : DESCRIPTION[family]} The same FX on every
          head, its phase spread by its Order.
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
        </div>
        <PaletteFxStrip projectId={projectId} family={family} effect={effect} entry={entry} />
      </div>
    </div>
  )
}

/**
 * The draft's graph strip: asked of the desk as a `{draft}`, `include: ONLY` — on the window's
 * selection, drawn as the lane over what it holds now, or on [NOTIONAL_HEADS] heads the rig lacks,
 * drawn as the FX alone, since nothing underneath them is real.
 */
function PaletteFxStrip({
  projectId,
  family,
  effect,
  entry,
}: {
  projectId: number
  family: AttributeFamily
  effect: PaletteFx
  entry: FxLibraryEntry | undefined
}) {
  // The editor is the Palettes page's, never the programmer's: the desk's selection, or this window's own.
  const selection = useWindowSelectionTargets(false)
  const [chosen, setChosen] = useState<StripOn | null>(null)
  const on: StripOn = selection.length === 0 ? 'notional' : (chosen ?? 'selection')
  const { data: templates } = usePaletteListQuery({ projectId })
  const attribute = storedFxAttribute(effect, family, entry, templates)
  // In the programmer's band, where a pressed palette runs.
  const draft = useMemo(() => (attribute == null ? null : previewDraftOf(effect, attribute, { programmerOwned: true })), [attribute, effect])
  const preview = useFxPreview(
    draft == null
      ? null
      : {
          projectId,
          targets: on === 'selection' ? selection : [],
          fxIds: [],
          drafts: [draft],
          include: 'ONLY',
          notional: on === 'notional' ? NOTIONAL_HEADS : undefined,
        },
  )
  const { data: library } = useFxLibraryQuery()
  const fxName = (fxType: string) => findFxEntry(library, fxType)?.name ?? fxType
  const choices: readonly { value: StripOn; label: string; disabled: boolean; title: string }[] = [
    {
      value: 'selection',
      label: 'On the selection',
      disabled: selection.length === 0,
      title: selection.length === 0 ? 'Nothing is selected' : 'The selected heads, over what they hold now',
    },
    { value: 'notional', label: `On ${NOTIONAL_HEADS_WORD} heads`, disabled: false, title: 'Heads the rig does not have: the FX alone' },
  ]
  return (
    <div className="space-y-1.5" data-palette-fx-strip={on}>
      <div role="group" aria-label="Draw the FX" className="flex h-6 w-fit items-center rounded-md border p-0.5">
        {choices.map((c) => (
          <button
            key={c.value}
            type="button"
            aria-pressed={on === c.value}
            disabled={c.disabled}
            title={c.title}
            onClick={() => setChosen(c.value)}
            className={cn(
              'h-full rounded px-1.5 text-[11px] text-muted-foreground hover:text-foreground disabled:opacity-50',
              on === c.value && 'bg-accent font-medium text-foreground',
            )}
          >
            {c.label}
          </button>
        ))}
      </div>
      <FxGraphStrip
        answer={preview.answer}
        error={preview.error}
        waiting={attribute == null ? 'The FX names no attribute the graph can draw yet.' : 'Asking the desk…'}
        draw={on === 'selection' ? 'result' : 'this'}
        fxName={fxName}
        bounce={effect.direction === 'BOUNCE'}
      />
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
