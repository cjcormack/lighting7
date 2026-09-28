import { memo, useMemo, type ComponentType } from 'react'
import { Badge } from '@/components/ui/badge'
import {
  Theater,
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
import { MiniStage } from './MiniStage'
import { collectCueTargets } from './targetUtils'
import { TriggerSummary } from './TriggerSummary'
import { TimingBadge } from './TimingBadge'
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
 * Reusable read-only detail body for a cue: transition, notes, palette, **its composed values**,
 * layers, ad-hoc effects and script hooks.
 *
 * **This is the cue read surface, all of it.** Four component trees reach it and none of them render
 * cue content themselves: the cue card → here, and `RunMobileCueCard` and
 * `PromptBookCueCard` → `CueCardBody` → here. Deepening a section here therefore lights up all four,
 * which is why the plan's estimate of "four independent read renderers" turned out to be one file.
 *
 * The doc used to say "presets" and name a `CueDetailSheet` that no longer exists: presets became
 * Looks applied through layers, and the sheet was folded into the Run card's inline panel.
 */
export const CueDetailContent = memo(function CueDetailContent({
  cue,
  projectId,
  enabled = true,
}: CueDetailContentProps) {
  const { data: library } = useEffectLibraryQuery(undefined, { skip: !enabled })
  const { data: looks } = useLookListQuery({ projectId }, { skip: !enabled })
  // The template library, for the same reason the Look library is here: a layer row says what it
  // applies, and since fx-templates a template's badge also says *which kind* it holds. Without this
  // an effect template drew as a plain template chip on the one surface an operator reads a cue
  // from. One cached list, already warm — the programmer and the busk view mount the same entry.
  const { data: templates } = useTemplateListQuery({ projectId }, { skip: !enabled })

  // Same derivation the collapsed row uses for its target chips, so the two cannot disagree about
  // what the cue touches.
  const targets = useMemo(() => (cue ? collectCueTargets(cue) : []), [cue])
  const layers = cue?.layers ?? []
  // Built once rather than a `.find` per layer: a cue with a dozen layers over a library of a
  // hundred Looks was doing twelve hundred comparisons per render.
  const looksById = useMemo(
    () => new Map((looks ?? []).map((look) => [look.id, look])),
    [looks],
  )
  const templatesById = useMemo(
    () => new Map((templates ?? []).map((template) => [template.id, template])),
    [templates],
  )
  const adHocEffects = cue?.adHocEffects ?? []
  const triggers = cue?.triggers ?? []

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

      {/* ── Stage ──
          A picture of which heads this cue touches, over the real patch.

          Only Run's Targets pane drew this, and session 2b deleted that pane — so it is here or
          nowhere. It belongs here rather than in a pane of its own: "which heads" is a reading of
          the cue, like the values and the layers below, and the chip list that used to sit beside it
          is already on the collapsed row. Rendered only when the cue actually targets something,
          because an empty stage map is a large grey rectangle that says nothing. */}
      {targets.length > 0 && (
        <div className="space-y-1.5">
          <SectionHeader icon={Theater} label="Stage" count={targets.length} />
          <MiniStage projectId={projectId} targets={targets} heightClass="h-32" />
        </div>
      )}

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
          layer that looked different here would be describing one structure twice.

          This is the whole read surface: the expanded cue card renders it directly, and
          `RunMobileCueCard` and `PromptBookCueCard` through `CueCardBody`. */}
      <div className="space-y-1.5">
        <SectionHeader icon={Layers} label="Layers" count={layers.length} />
        {layers.length === 0 ? (
          <p className="text-[11px] text-muted-foreground">None.</p>
        ) : (
          layers.map((layer, index) => (
            <LayerRow
              key={`layer-${index}`}
              layer={layer}
              index={index}
              // Both libraries, so a template layer reads here exactly as it does in the programmer
              // — family badge, and the wave when it holds an effect. This surface used to pass no
              // template library at all and `describeStackSource` therefore declined to paint a
              // template layer as missing; with the list loaded that verdict becomes honest, and a
              // layer naming a deleted template now says so here too.
              // `librariesLoaded` waits for *both*, as `ProgrammerLookStack` does: with only one
              // list back, every layer applying the other would paint as missing for the length of
              // a fetch.
              info={describeStackSource(
                layer.source,
                looksById,
                templatesById,
                looks != null && templates != null,
              )}
              sortable={false}
              showTargets
              readOnly
            />
          ))
        )}
      </div>

      {/* ── Ad-hoc Effects ── */}
      <div className="space-y-1.5">
        <SectionHeader icon={AudioWaveform} label="Effects" count={adHocEffects.length} />
        {adHocEffects.length === 0 ? (
          <p className="text-[11px] text-muted-foreground">None.</p>
        ) : (
          adHocEffects.map((effect, index) => (
            <EffectSummary
              key={`effect-${index}`}
              effect={fromCueAdHocEffect(effect, library)}
              target={{ type: effect.targetType, key: effect.targetKey }}
              actions={
                <TimingBadge
                  delayMs={effect.delayMs}
                  intervalMs={effect.intervalMs}
                  randomWindowMs={effect.randomWindowMs}
                />
              }
            />
          ))
        )}
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
