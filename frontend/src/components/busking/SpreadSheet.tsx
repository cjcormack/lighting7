import { useCallback, useMemo, useState } from 'react'
import { Save } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { ColourEditor } from '@/components/editor/ColourEditor'
import { SpreadPanel, type IntentSpreadPlan, type SpreadRequestBody, type SpreadSeed } from '@/components/editor/SpreadPanel'
import { useEditorCramped } from '@/components/editor/EditorSurface'
import { targetFamilies } from '@/components/fixtures-list/rowModel'
import { isSpreadColourTemplate } from '@/components/fx/FxColourTemplates'
import { RecordLookSheet } from '@/components/programmer/RecordLookSheet'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import type { AttributeFamily } from '@/lib/attributeFamily'
import { selectedCells } from '@/lib/cellsSubSelection'
import { getProgrammerFadeMs } from '@/lib/programmerFade'
import { useSpreadMutation } from '@/store/programmerOps'
import { useTemplateListQuery } from '@/store/templates'
import { writeTargetsOf } from './ColourSheet'
import { lookLayerTarget, type BuskingTarget } from './buskingTypes'

export type { SpreadSeed } from '@/components/editor/SpreadPanel'

/**
 * The side sheet's **Spread** tab — the docked host of `SpreadPanel` (busk-further plan D9, D10;
 * editor-kit plan D2, session 3; `Spread.dc.html` is the authority on layout).
 *
 * What is the host's: the busk selection → the request's targets (`lookLayerTarget`, a group as a
 * group and a cell by its element key) and the families the heads can take (`targetFamilies` over
 * the selection's write targets, the Colour tab's expansion), the *Over: Cells* count — what the
 * Cells chip counts, one expansion (`lib/cellsSubSelection.ts`) pinned against the desk's own
 * fixture, so the two cannot answer "how many cells" differently — the selection's mask, the
 * mutation with the programmer fade read at send time, the Colour tab's seed hand-over, and the
 * footer's save. Everything else — the form, the endpoint editors, Curve · Order · Parts · Over,
 * Live and Apply / Send again, the keyboard, the desk's answer read for its `skippedFamilies` and
 * nothing drawn from it — is the panel's, shared with the programmer's row C. Nothing here changed
 * to the eye when the body moved into the panel.
 *
 * **A spread is a result, not a template** (D10). *Save as Look…* opens `RecordLookSheet` over
 * the selection — `record-look`, the same gesture every busked state is kept by — and nothing here
 * mints a template: a "spread template" would need a second grammar and a resolver that knows the
 * selection's order at cook time, which no template does. The programmer's footer carries no save
 * (editor-kit D11: Record is one row up); this host fills the panel's `save` slot.
 *
 * **The client never interpolates.** This file imports nothing from `editor/spreadPlans.ts`, the
 * client walk the panel keeps for the raw kind; the desk interpolates in the intent's own space
 * and the rig is the preview (the strip drawn from `written[]` went on 2026-09-21).
 */

export interface SpreadSheetProps {
  projectId: number
  selectedTargets: Map<string, BuskingTarget>
  /** The selection's attribute mask, sent with the request. Null is every attribute. */
  families: AttributeFamily[] | null
  /** A *From* handed over by the Colour tab's *Spread…* button; applied once, on change. */
  seed?: SpreadSeed | null
  /** Called once a seed has been applied, so the host can drop it. */
  onSeedConsumed?: () => void
  /** Force the tighter layout; the cramped height query answers it otherwise. */
  compact?: boolean
}

export function SpreadSheet({ projectId, selectedTargets, families, seed, onSeedConsumed, compact }: SpreadSheetProps) {
  const cramped = useEditorCramped()
  const isCompact = compact ?? cramped
  const selected = useMemo(() => [...selectedTargets.values()], [selectedTargets])
  const { fixtures } = useFixtureLookup()
  const [spread] = useSpreadMutation()
  const { data: templates } = useTemplateListQuery({ projectId })
  const colourTemplates = useMemo(() => (templates ?? []).filter(isSpreadColourTemplate), [templates])

  const writeTargets = useMemo(() => writeTargetsOf(selected, fixtures), [selected, fixtures])
  const available = useMemo(() => targetFamilies(writeTargets), [writeTargets])
  const layerTargets = useMemo(() => selected.map(lookLayerTarget), [selected])
  // No rows and no group list: the count needs the parent↔cell lookup and a group's members, both
  // of which the fixture list carries.
  const cellCount = useMemo(
    () => selectedCells(layerTargets, { rows: [], groups: [], fixtures: fixtures ?? [] }).length,
    [layerTargets, fixtures],
  )
  const [saving, setSaving] = useState(false)

  const send = useCallback(
    (body: SpreadRequestBody) => spread({ ...body, projectId, fadeMs: getProgrammerFadeMs() }).unwrap(),
    [spread, projectId],
  )

  const plan = useMemo<IntentSpreadPlan>(
    () => ({
      kind: 'intent',
      col: 'selection',
      label: 'Selection',
      targets: layerTargets,
      count: layerTargets.length,
      cellCount,
      families: available,
      mask: families,
      colourTemplates,
      send,
    }),
    [layerTargets, cellCount, available, families, colourTemplates, send],
  )
  const plans = useMemo(() => [plan], [plan])

  return (
    <>
      <SpreadPanel
        host="docked"
        plans={plans}
        colourEditor={ColourEditor}
        compact={isCompact}
        seed={seed}
        onSeedConsumed={onSeedConsumed}
        save={
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="h-7 min-w-0 text-xs"
            disabled={selected.length === 0}
            title="Record the selection’s Local values as a Look — a spread is a result, not a template"
            onClick={() => setSaving(true)}
          >
            <Save className="size-3.5" /> <span className="truncate">Save as Look…</span>
          </Button>
        }
      />
      <RecordLookSheet open={saving} onOpenChange={setSaving} projectId={projectId} targets={layerTargets} />
    </>
  )
}
