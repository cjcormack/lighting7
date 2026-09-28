import { useMemo } from 'react'
import { ChevronLeft, type LucideIcon } from 'lucide-react'
import { BeatIndicator } from '@/components/BeatIndicator'
import { FixtureAppearanceSource } from '@/components/fixtures/fixtureAppearance'
import {
  SIDE_PANEL_STRIP_CELL_CLASS,
  SIDE_PANEL_STRIP_CLASS,
} from '@/components/sheet/sidePanel'
import { Button } from '@/components/ui/button'
import { formatBpm } from '@/hooks/useBpmDraft'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { setBuskSheet, toggleBuskSheet, type BuskSheetTab } from '@/lib/buskWindow'
import { cn } from '@/lib/utils'
import { usePatchListQuery } from '@/store/patches'
import { useSpeedMasterLiveQuery } from '@/store/speedMasters'
import { BLIND_NAME_SUFFIX, BlindDot } from './BlindMarks'
import { useProgrammerBlind } from '@/hooks/useProgrammerBlind'
import { selectedHeadCount, type BuskingTarget } from './buskingTypes'

/**
 * The side sheet **folded** to the shared 40px strip (busk-further plan D7; `Sheets.dc.html`
 * §Folded): the two live readouts a busking operator glances at — the beat and master 1's tempo —
 * the tab glyphs, so a folded rail is still one tap from any tab, the selection's colour as a dot,
 * and its head count.
 *
 * **It is the programmer rail's strip**, through `SIDE_PANEL_STRIP_CLASS` — same width, same fill,
 * same chevron cell at the top. It was 44px and unfilled, which is the phone handle's measurement
 * on a control that is only ever drawn on the desk board; `components/sheet/sidePanel.ts` states
 * why 40 is this one's. `Sheets.dc.html` §Folded, cited above, still draws it at 44: the commit
 * wins.
 *
 * A tap on a glyph unfolds onto that tab; the chevron unfolds onto whichever tab was last open —
 * through `toggleBuskSheet`, the one reader of that memory, so the chevron and a MIDI
 * `BuskSheetToggle` open the same tab. Both write `busk.sheet` and nothing else — the fold is that
 * fact's `none`, not a state of its own.
 *
 * **The colour dot reads the stage's colour dispatch** (`FixtureAppearanceSource`), as the rig tile
 * does: the first selected fixture's, or the first member of the first selected group, so a fold
 * beside a lit selection shows what the rig is doing and not what the picker last said. A
 * selection with no head draws a dim ring.
 *
 * **The live cue number sits under the Show glyph** (busk-chrome plan D4): green, an em-dash with
 * nothing on stage, read from the **server cursor** — `transport.serverActiveCueId`, the cue on
 * stage, which holds on the outgoing cue mid-fade — not the animating one, so the fold says what
 * the rig is doing. `SideSheet` derives it and hands in [liveCue]; the strip subscribes to nothing
 * for it — the blind dot on the same glyph is its own narrowed read (`BlindMarks.tsx`), and the
 * word rides the glyph's `aria-label`, since that label wins the accessible name over contents.
 */
export function SideSheetFold({
  projectId,
  selectedTargets,
  tabs,
  liveCue,
}: {
  projectId: number
  selectedTargets: Map<string, BuskingTarget>
  tabs: readonly { id: BuskSheetTab; label: string; icon: LucideIcon }[]
  /** The cue on stage — its number, or its name where it has none — or null with nothing on stage. */
  liveCue: string | null
}) {
  const blind = useProgrammerBlind()
  const { data: live } = useSpeedMasterLiveQuery()
  const master1 = live?.find((master) => master.index === 1) ?? null
  const heads = selectedHeadCount([...selectedTargets.values()])

  return (
    <div data-side-sheet="none" className={cn(SIDE_PANEL_STRIP_CLASS, 'hidden md:flex')}>
      {/* The strip's own cell, as the rail's expand chevron is: a full-width 40px square with the
          dividing line under it, so the two strips read as one control at the same height. */}
      <Button
        variant="ghost"
        size="icon"
        onClick={toggleBuskSheet}
        aria-label="Unfold the side sheet"
        title="Unfold the side sheet"
        className={SIDE_PANEL_STRIP_CELL_CLASS}
      >
        <ChevronLeft className="size-3.5" />
      </Button>

      {/* Everything below the cell shares the column's own padding: the cell is edge-to-edge and
          its border has to run the full 40px, so the inset cannot be on the strip. */}
      <div className="flex min-h-0 w-full flex-1 flex-col items-center gap-3 py-2">
        <div className="flex flex-col items-center gap-1" title="Master 1">
          <BeatIndicator
            master={master1 == null ? undefined : { uuid: master1.uuid, index: 1 }}
            className="size-2"
          />
          <span data-fold-tempo className="text-[11px] font-semibold tabular-nums">
            {master1 == null ? '—' : formatBpm(master1.bpm)}
          </span>
        </div>

        <div className="flex flex-col items-center gap-1">
          {tabs.map((tab) => (
            <div key={tab.id} className="flex flex-col items-center">
              <button
                type="button"
                onClick={() => setBuskSheet(tab.id)}
                // The label wins the accessible name over the button's contents, so the blind
                // word rides here rather than as an `sr-only` span the dot would carry silently.
                aria-label={`Open the ${tab.label} tab${tab.id === 'show' && blind ? BLIND_NAME_SUFFIX : ''}`}
                title={tab.id === 'show' && blind ? `${tab.label}${BLIND_NAME_SUFFIX}` : tab.label}
                className="relative rounded p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground"
              >
                <tab.icon className="size-4" />
                {/* Blind on the Show glyph, as the cue number is under it: the sheet's one report
                    of the programmer lives on that tab (`BlindMarks.tsx`). */}
                {tab.id === 'show' && <BlindDot />}
              </button>
              {tab.id === 'show' && (
                <span
                  data-fold-cue
                  className={cn(
                    'max-w-full truncate text-[10px] font-semibold tabular-nums',
                    liveCue == null ? 'text-muted-foreground' : 'text-green-500',
                  )}
                  title={liveCue == null ? 'Nothing on stage' : `On stage: ${liveCue}`}
                >
                  {liveCue ?? '—'}
                </span>
              )}
            </div>
          ))}
        </div>

        <span className="flex-1" />

        <SelectionColourDot projectId={projectId} selectedTargets={selectedTargets} />
        <span
          data-fold-heads
          className="text-[10px] tabular-nums text-muted-foreground"
          title="Selected heads"
        >
          {heads}
        </span>
      </div>
    </div>
  )
}

function SelectionColourDot({
  projectId,
  selectedTargets,
}: {
  projectId: number
  selectedTargets: Map<string, BuskingTarget>
}) {
  const { data: patches } = usePatchListQuery(projectId)
  const { fixtures, fixtureByKey, typeByKey } = useFixtureLookup()

  // The first head the selection names: a fixture target directly, a group through its first
  // patched member. Element keys are never parsed — a cell target carries its parent fixture.
  const head = useMemo(() => {
    for (const target of selectedTargets.values()) {
      if (target.type === 'fixture') return target.fixture.key
      const member = fixtures?.find((fixture) => fixture.groups.includes(target.name))
      if (member != null) return member.key
    }
    return null
  }, [selectedTargets, fixtures])
  const patch = head == null ? undefined : patches?.find((p) => p.key === head)

  if (patch == null) {
    return <span data-fold-colour className="size-3 rounded-full border border-muted-foreground/40" title="No selection" />
  }
  const fixture = fixtureByKey.get(patch.key)
  return (
    <FixtureAppearanceSource patch={patch} fixture={fixture} fixtureType={fixture == null ? undefined : typeByKey.get(fixture.typeKey)}>
      {(appearance) => (
        <span
          data-fold-colour
          className="size-3 rounded-full border border-border"
          style={{ background: appearance.color, opacity: 0.3 + 0.7 * appearance.intensity }}
          title="The selection's colour"
        />
      )}
    </FixtureAppearanceSource>
  )
}
