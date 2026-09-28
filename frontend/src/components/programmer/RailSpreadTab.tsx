import { memo, useMemo, useRef } from 'react'
import { ColourEditor } from '@/components/editor/ColourEditor'
import { EditorLabelLine } from '@/components/editor/EditorLabelLine'
import { SpreadPanel } from '@/components/editor/SpreadPanel'
import { spreadColumnsForTargets, useMarqueeSpreadPlans, type SpreadColumn } from '@/components/fixtures-list/SpreadPopover'
import { useMarquee } from '@/components/fixtures-list/marqueeContext'
import { useProgrammerScope } from './ProgrammerScope'
import { useRailArm } from './ProgrammerWorkspace'
import { RailTabEmpty } from './RailColourTab'
import { useClaimedFocus } from './useClaimedFocus'

/**
 * The programmer rail's **Spread** tab — the docked host of `SpreadPanel` over the marquee
 * (editor-kit plan session 4, `RailTabs.dc.html`): row C's Spread popover, docked, beside the grid.
 *
 * **Its plans are the popover's**, from `useMarqueeSpreadPlans` — the builder lifted out of
 * `SpreadPopover` so the two cannot build different targets, masks or scope arms from one
 * marquee: the family segment answered by the marquee and drawn checked, the Property row where the
 * family holds more than one, Over: Heads, Live off by default, Apply always sending, the desk
 * resolving every intent. A rows-only selection spreads by the plain lists' row rule
 * (`spreadColumnsForTargets`), every head with the family chosen in the panel.
 *
 * **The scope arm is session 3's, unchanged**: Local sends and lands in Local; a focused Look layer
 * sends `write: false` and lands the desk's literals in the layer's draft; Output and a focused
 * template layer refuse with the popover's own words, drawn on the panel since a docked host has no
 * trigger to disable.
 *
 * **Row C's Spread focuses this tab** while it is open (the claim), and the Colour tab's — or a
 * colour cell's — *Spread…* opens it with From seeded (`spreadSeed`, `useSpreadSeed`'s shape),
 * dropped once read. Like every host of the panel it mounts inside the grid's `EditorContext`
 * (`ScopedEditorContextProvider`, around the rail's tabs), because the raw Speed arm writes
 * through `useCellWriters`.
 *
 * Memoised on `projectId`: `ProgrammerRail` re-renders on every desk-selection change (its add-effect
 * offer subscribes to it), and the tab has its own subscription to the marquee for everything else.
 */
export const RailSpreadTab = memo(function RailSpreadTab({ projectId }: { projectId: number }) {
  const marquee = useMarquee()
  const arm = useRailArm()
  const scope = useProgrammerScope()

  const rowColumns = useMemo<readonly SpreadColumn[]>(() => spreadColumnsForTargets(marquee?.rows ?? []), [marquee?.rows])
  const columns = marquee == null ? NO_COLUMNS : marquee.columns.length > 0 ? marquee.columns : marquee.rows.length > 0 ? rowColumns : NO_COLUMNS
  const { plans, disabledReason, labelLine, footerNote } = useMarqueeSpreadPlans({
    columns,
    projectId,
    desk: true,
    scopeLabel: marquee?.scopeLabel ?? 'Local',
    docked: true,
  })

  // The scope's refusal says so on the label line too — Output's cook is not "4 heads · Local".
  const shownLabel =
    disabledReason == null ? labelLine : (
      <EditorLabelLine
        docked
        subject={`${scope?.kind === 'output' ? 'Output' : 'Template layer'} · read-only`}
        title={disabledReason}
        column="Spread"
      />
    )

  // Row C's Spread, claimed: focus the panel's first field (From) — not where the scope refuses.
  const wrapperRef = useRef<HTMLDivElement>(null)
  const { spreadSeed, consumeSpreadSeed } = arm
  useClaimedFocus('spread', wrapperRef, '[data-spread-sheet-body] input:not([disabled])', disabledReason != null)

  const empty = plans.length === 0
  return (
    <div ref={wrapperRef} data-rail-tab="spread" className="flex min-h-0 flex-1 flex-col *:border-l-0">
      {empty ? (
        <RailTabEmpty
          column="Spread"
          hint={
            (marquee?.columns.length ?? 0) > 0
              ? 'The marquee holds no column Spread can drive — dimmer, colour, position, zoom, focus, iris, strobe or speed.'
              : 'Drag over cells in a column Spread can drive, or select rows. The tab spreads over what row C names, and follows it as it changes.'
          }
        />
      ) : (
        <SpreadPanel
          host="docked"
          plans={plans}
          colourEditor={ColourEditor}
          labelLine={shownLabel}
          footerNote={footerNote}
          disabledReason={disabledReason}
          seed={spreadSeed}
          onSeedConsumed={consumeSpreadSeed}
        />
      )}
    </div>
  )
})

const NO_COLUMNS: readonly SpreadColumn[] = []
