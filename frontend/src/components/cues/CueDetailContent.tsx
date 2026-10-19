import { memo, useMemo, type ComponentType } from 'react'
import { Badge } from '@/components/ui/badge'
import {
  Blinds,
  Flame,
  Layers,
  AudioWaveform,
  Zap,
  Hash,
  Clock,
  SlidersHorizontal,
  type LucideProps,
} from 'lucide-react'
import { formatFadeText } from '@/lib/cueUtils'
import { formatMs } from '@/lib/formatMs'
import { useEffectLibraryQuery } from '@/store/fixtureFx'
import { useLookListQuery } from '@/store/looks'
import { useTemplateListQuery } from '@/store/templates'
import { EffectSummary } from '@/components/fx/EffectSummary'
import { LayerRow, describeStackSource } from '@/components/looks/LookStack'
import { CueValueGrid } from './CueValueGrid'
import { TriggerSummary } from './TriggerSummary'
import { TimingBadge } from './TimingBadge'
import { CueSceneryReadout } from '@/components/scenery/SceneryReadout'
import { CueEventsReadout } from '@/components/effects/CueEventsEditor'
import { fromCueAdHocEffect } from '@/components/fx/effectSummaryTypes'
import type { Cue } from '@/api/cuesApi'

// ── Section header (shared across all detail sections) ────────────────

export function SectionHeader({
  icon: Icon,
  label,
  count,
}: {
  icon: ComponentType<LucideProps>
  label: string
  count?: number
}) {
  return (
    <div className="text-xs font-medium text-muted-foreground uppercase tracking-wide flex items-center gap-1.5">
      <Icon className="size-3.5" />
      {label}
      {count != null && count > 0 && (
        <Badge variant="secondary" className="text-[10px] px-1.5 py-0">
          {count}
        </Badge>
      )}
    </div>
  )
}

// ── Detail content body ───────────────────────────────────────────────

interface CueDetailContentProps {
  cue: Cue | null
  projectId: number
  /** Controls whether data queries fire (for lazy-loading). Defaults to true. */
  enabled?: boolean
}

/**
 * Reusable read-only detail body for a cue: transition, notes, **its composed values**, layers,
 * ad-hoc effects and script hooks.
 *
 * **The read-out the phone card and the Prompt Book draw**: `RunMobileCueCard` and
 * `PromptBookCueCard` reach it through `CueCardBody`. The desktop card and the cue sheet's pane mount
 * `CueEditor` instead (show-cue-editing plan §4.2), whose Looks and FX tabs edit what this reads.
 */
export const CueDetailContent = memo(function CueDetailContent({
  cue,
  projectId,
  enabled = true,
}: CueDetailContentProps) {
  const layers = cue?.layers ?? []
  const adHocEffects = cue?.adHocEffects ?? []
  const triggers = cue?.triggers ?? []
  const scenery = cue?.scenery ?? []
  const trackedScenery = cue?.trackedScenery ?? []
  const events = cue?.events ?? []

  const fadeText = cue ? formatFadeText(cue.fadeDurationMs, cue.fadeCurve) : ''

  if (!cue) {
    return <p className="text-sm text-muted-foreground">No cue selected.</p>
  }

  return (
    <>
      {/* ── Transition ── */}
      <div className="space-y-1.5">
        <SectionHeader icon={Clock} label="Transition" />
        <div className="flex flex-wrap items-center gap-1.5 text-sm">
          <Badge variant="outline" className="font-mono">{fadeText}</Badge>
          {cue.autoAdvance && (
            <Badge
              variant="outline"
              className="border-blue-500/30 text-blue-500 bg-blue-500/10"
            >
              Auto-advance
              {cue.autoAdvanceDelayMs ? ` after ${formatMs(cue.autoAdvanceDelayMs)}` : ''}
            </Badge>
          )}
        </div>
      </div>

      {/* ── Notes ── */}
      {cue.notes && (
        <div className="space-y-1.5">
          <SectionHeader icon={Hash} label="Notes" />
          <p className="text-sm italic text-muted-foreground whitespace-pre-wrap">
            {cue.notes}
          </p>
        </div>
      )}

      {/* No stage map (venue-visit plan S3). A 2D `MiniStage` of the heads the cue touches stood
          here; it was the cue's least informative reading — the values below say what the heads
          do, the collapsed row's chips say which they are, and the Stage view draws the rig in
          3D — and it went from the cards on desktop and phone alike. */}
      {/* ── Values ──
          The cue's composed output, drawn with the *same* cells as the programmer's grid. Session
          2a's answer to "a cue looks nothing like the programmer that made it": there is now one
          value language, and this is a read of it rather than a second editor.

          Above Layers deliberately — the values are what the operator is looking for, and the stack
          is the explanation for them. */}
      <div className="space-y-1.5">
        <SectionHeader icon={SlidersHorizontal} label="Values" />
        <CueValueGrid projectId={projectId} cueId={cue.id} enabled={enabled} />
      </div>

      {/* ── Layers ──
          The same `LayerRow` the cue editor and the programmer draw, with `readOnly` and
          `sortable={false}`. It used to be a hand-rolled shallow copy — ordinal, name, mask, amount,
          off, targets, timing — which meant the read surface and the authoring surface drifted apart
          the moment either grew a field. Sharing the row is the same argument `LookStack` makes for
          serving both the cue editor and the programmer: a cue *is* a saved programmer stack, so a
          layer that looked different here would be describing one structure twice. */}
      <div className="space-y-1.5">
        <SectionHeader icon={Layers} label="Layers" count={layers.length} />
        <CueLayersReadout cue={cue} projectId={projectId} enabled={enabled} />
      </div>

      {/* ── Scenery ──
          What the cue moves on GO, on its own clocks, and — hatched — what it shows without moving
          it: scenery tracks, so the set at this cue is every change from the top of the stack down
          (stage-view plan session 8). Read-only; the cue editor's Scenery tab edits it. Only drawn when there is
          something to draw, so a rig with no scenery keeps its cards as they were. */}
      {(scenery.length > 0 || trackedScenery.length > 0) && (
        <div className="space-y-1.5">
          <SectionHeader icon={Blinds} label="Scenery" count={scenery.length} />
          <CueSceneryReadout projectId={projectId} scenery={scenery} tracked={trackedScenery} enabled={enabled} />
        </div>
      )}

      {/* ── Events ──
          The cannons this cue fires on GO into it (stage-view plan session 9). Read-only; the cue
          editor's Events tab edits them. Only drawn when there are some. */}
      {events.length > 0 && (
        <div className="space-y-1.5">
          <SectionHeader icon={Flame} label="Events" count={events.length} />
          <CueEventsReadout events={events} />
        </div>
      )}

      {/* ── Ad-hoc Effects ── */}
      <div className="space-y-1.5">
        <SectionHeader icon={AudioWaveform} label="Effects" count={adHocEffects.length} />
        <CueEffectsReadout cue={cue} enabled={enabled} />
      </div>

      {/* ── Script Hooks ── */}
      <div className="space-y-1.5">
        <SectionHeader icon={Zap} label="Script Hooks" count={triggers.length} />
        {triggers.length === 0 ? (
          <p className="text-[11px] text-muted-foreground">None.</p>
        ) : (
          triggers.map((trigger, index) => (
            <TriggerSummary key={`trigger-${index}`} trigger={trigger} />
          ))
        )}
      </div>
    </>
  )
})

/**
 * A cue's layer stack, read-only — the same `LayerRow` the programmer draws, top of the array last.
 */
function CueLayersReadout({ cue, projectId, enabled = true }: { cue: Cue; projectId: number; enabled?: boolean }) {
  const { data: looks } = useLookListQuery({ projectId }, { skip: !enabled })
  // The template library, for the same reason the Look library is here: a layer row says what it
  // applies, and since fx-templates a template's badge also says *which kind* it holds. One cached
  // list, already warm — the programmer and the busk view mount the same entry.
  const { data: templates } = useTemplateListQuery({ projectId }, { skip: !enabled })
  // Built once rather than a `.find` per layer: a cue with a dozen layers over a library of a
  // hundred Looks was doing twelve hundred comparisons per render.
  const looksById = useMemo(() => new Map((looks ?? []).map((look) => [look.id, look])), [looks])
  const templatesById = useMemo(
    () => new Map((templates ?? []).map((template) => [template.id, template])),
    [templates],
  )
  const layers = cue.layers
  if (layers.length === 0) return <p className="text-[11px] text-muted-foreground">None.</p>
  return (
    <>
      {layers.map((layer, index) => (
        <LayerRow
          key={`layer-${index}`}
          layer={layer}
          index={index}
          // `librariesLoaded` waits for *both*, as the Stack table does: with only one list
          // back, every layer applying the other would paint as missing for the length of a fetch.
          info={describeStackSource(layer.source, looksById, templatesById, looks != null && templates != null)}
          sortable={false}
          showTargets
          readOnly
        />
      ))}
    </>
  )
}

/** A cue's own effects, read-only, each with its fire timing. */
function CueEffectsReadout({ cue, enabled = true }: { cue: Cue; enabled?: boolean }) {
  const { data: library } = useEffectLibraryQuery(undefined, { skip: !enabled })
  const adHocEffects = cue.adHocEffects
  if (adHocEffects.length === 0) return <p className="text-[11px] text-muted-foreground">None.</p>
  return (
    <>
      {adHocEffects.map((effect, index) => (
        <EffectSummary
          key={`effect-${index}`}
          effect={fromCueAdHocEffect(effect, library)}
          target={{ type: effect.targetType, key: effect.targetKey }}
          actions={
            <TimingBadge delayMs={effect.delayMs} intervalMs={effect.intervalMs} randomWindowMs={effect.randomWindowMs} />
          }
        />
      ))}
    </>
  )
}
