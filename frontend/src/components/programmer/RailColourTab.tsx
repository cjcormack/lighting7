import { memo, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { Save } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { ColourEditor, type ColourBuffer, type ColourChannels } from '@/components/editor/ColourEditor'
import { EditorFooter } from '@/components/editor/EditorFooter'
import { EditorLabelLine } from '@/components/editor/EditorLabelLine'
import { headsLine } from '@/components/editor/editorCopy'
import { useEditorKeyboard } from '@/components/editor/useEditorKeyboard'
import { colourTargetsOf, templateTargetsOf } from '@/components/fixtures-list/cells/ColourCell'
import { cellActionCopy } from '@/components/fixtures-list/cellEntry'
import { useMarquee, type MarqueeSnapshot } from '@/components/fixtures-list/marqueeContext'
import { batchForTargets, type CellBatch, type WriteTarget } from '@/components/fixtures-list/rowModel'
import { computeCombinedCss } from '@/lib/colourMath'
import { useFocusedTemplateLayer } from './FocusedTemplateLayer'
import { NewTemplateFromSelectionSheet } from './NewTemplateFromSelectionSheet'
import { useProgrammerScope } from './ProgrammerScope'
import { useRailArm } from './ProgrammerWorkspace'
import { useClaimedFocus } from './useClaimedFocus'

/**
 * The programmer rail's **Colour** tab — the docked host of `ColourEditor` over the marquee
 * (editor-kit plan session 4, `RailTabs.dc.html`). The busk sheet's Colour tab on the desk's other
 * live view: a long busk over one marquee with the grid uncovered, where the cell's popover is the
 * quick form.
 *
 * **Its targets are the cell's**: the marquee's Colour batch through `colourTargetsOf` — the cell's
 * own rule, so the tab and the popover it stands in for cannot count heads two ways — or, with rows
 * selected and no cells, the rows' heads that take colour (`batchForTargets` over them, the same
 * batch a row selection's cell is handed). The emitter rows are that batch's union, as the cell's
 * are. With neither it draws its empty state and stays open, following the next marquee.
 *
 * **It writes through the Colour column's writer**: every change is the marquee's published
 * `commit('colour', …)`, which is the container's column commit through its ~30 Hz throttle and its
 * scope-aware writers — Local, or the focused Look layer's draft. It never plans a write itself.
 *
 * **Its buffer is its own, as the busk tab's is**, seeded from the rig by the editor's Pick on
 * every change of heads (`pickOnTargets`): the heads can span rows whose cells disagree, and the
 * appearance store is the one reading that covers them all. Two things differ from the busk tab,
 * both on the side of writing less. **An emitter starts unstated** — `undefined`, which the writer
 * samples from the wire — rather than at 0, because the appearance store has no per-emitter
 * reading and a 0 would drive every white LED in the marquee dark on the first byte typed into R;
 * it becomes stated only when the operator states it, and a new set of heads unstates it again.
 * And **the knob's seed is a constant** for the busk tab's reason: routing the tab's own writes back
 * into `combinedCss` is the ping-pong `ColourEditor`'s docblock records.
 *
 * **The scope arms are the cell's.** Local writes; a focused Look layer writes into the draft
 * through the same writer arm; **Output** and a focused **template** layer draw the editor
 * read-only (`readOnly`: drawn, dimmed, taking no input) with the cell's own refusal words, and
 * Pick stays live, since it reads and writes nothing. *Save as template…* stays in every scope: it
 * records from the selection, not the scope.
 *
 * **A claimed open lands here** (call 9): Enter, Set, a typed digit or a double click on a Colour
 * cell while this tab is open arrives as the rail's focus request — R focused and selected, the
 * character typed into it as its first keystroke, exactly as the popover would have opened.
 * *Spread…* opens the Spread tab with From set to this colour's RGB (`spreadFrom`).
 *
 * **No Recent** (D11): the tab exists only in the docked arm, where row C's strip is on screen.
 *
 * Memoised on `projectId`: `ProgrammerRail` re-renders on every desk-selection change (its add-effect
 * offer subscribes to it), and the tab has its own subscription to the marquee for everything else.
 */
export const RailColourTab = memo(function RailColourTab({ projectId }: { projectId: number }) {
  const marquee = useMarquee()
  const arm = useRailArm()
  const scope = useProgrammerScope()
  const focusedTemplate = useFocusedTemplateLayer() != null

  const batch = useMarqueeColourBatch(marquee)
  const targets = useMemo(() => colourTargetsOf(batch?.targets ?? NO_TARGETS), [batch])
  const resolutions = batch?.resolutions ?? NO_RESOLUTIONS
  const hasWhite = resolutions.some((r) => r.kind === 'colour' && r.property.whiteChannel != null)
  const hasAmber = resolutions.some((r) => r.kind === 'colour' && r.property.amberChannel != null)
  const hasUv = resolutions.some((r) => r.kind === 'colour' && r.property.uvChannel != null)

  const readOnly = marquee != null && !marquee.permission.entry
  const refusal = readOnly ? cellActionCopy(scope, focusedTemplate, targets.length).setTitle : null
  const commit = marquee?.commit

  const [channels, setChannels] = useState<ColourBuffer>(NEUTRAL)

  // A new set of heads unstates the emitters again (see the docblock); the editor's Pick re-seeds
  // the RGB from the rig in the same commit. Keyed on the heads' keys — a value, not the marquee's
  // identity, which moves on every frame of a drag that has not crossed a head.
  const headsKey = useMemo(() => targets.map((t) => `${t.fixtureKey ?? ''}#${t.key}`).join('|'), [targets])
  useEffect(() => {
    setChannels((prev) => ({ r: prev.r, g: prev.g, b: prev.b }))
  }, [headsKey])

  const onColourChange = useCallback(
    (r: number, g: number, b: number, w?: number, a?: number, uv?: number) => {
      if (readOnly || commit == null) return
      setChannels({ r, g, b, w, a, uv })
      commit('colour', { kind: 'colour', r, g, b, w, a, uv })
    },
    [readOnly, commit],
  )

  // What Pick read, as the buffer — zeroing only an emitter the buffer already stated, which is the
  // editor's own `zeroIfHeld` rule; an unstated one stays unstated.
  const onPick = useCallback((picked: ColourChannels) => {
    setChannels((prev) => ({
      r: picked.r,
      g: picked.g,
      b: picked.b,
      w: prev.w === undefined ? undefined : picked.w,
      a: prev.a === undefined ? undefined : picked.a,
      uv: prev.uv === undefined ? undefined : picked.uv,
    }))
  }, [])

  const onSpread = useCallback((from: ColourChannels) => arm.spreadFrom({ r: from.r, g: from.g, b: from.b }), [arm])

  // Comma steps R → G → B → the emitters as in the popover; Enter hands the keyboard back to the
  // grid, which is this panel's "done" — there is nothing to close.
  const { contentRef, onKeyDown } = useEditorKeyboard({
    autoFocus: false,
    onDone: blurActive,
  })

  // The claimed open: R focused and selected, the character seeded (`useClaimedFocus`).
  const seed = useClaimedFocus('colour', contentRef, 'input[type="number"]:not([disabled])', readOnly)

  const templateTargets = useMemo(() => templateTargetsOf(targets), [targets])
  // Mounted once Save has been pressed and kept from then on — the cell's rule: a dialog's hooks
  // are not worth holding before anyone asks, and unmounting on close would cut its slide-out.
  const [saveMounted, setSaveMounted] = useState(false)
  const [saving, setSaving] = useState(false)
  const onSave = useCallback(() => {
    setSaveMounted(true)
    setSaving(true)
  }, [])

  const empty = targets.length === 0
  const labelLine = empty ? null : (
    <EditorLabelLine
      docked
      subject={readOnly ? `${scope?.kind === 'output' ? 'Output' : 'Template layer'} · read-only` : headsLine(targets.length, marquee?.scopeLabel ?? 'Local')}
      title={refusal ?? undefined}
      column="Colour"
    />
  )

  return (
    <div data-rail-tab="colour" className="flex min-h-0 flex-1 flex-col">
      {empty ? (
        <RailTabEmpty
          column="Colour"
          hint={
            (marquee?.columns.length ?? 0) > 0
              ? 'The marquee holds no Colour cells. Drag over Colour cells, or select rows — the tab writes to what row C names, and follows it as it changes.'
              : 'Drag over Colour cells, or select rows. The tab writes to what row C names, and follows it as it changes.'
          }
          footer={
            <EditorFooter
              className="@container shrink-0 px-3 py-2"
              save={
                <Button type="button" variant="outline" size="sm" className="h-7 min-w-0 text-xs" disabled>
                  <Save className="size-3.5" /> <span className="truncate">Save as template…</span>
                </Button>
              }
            />
          }
        />
      ) : (
        <>
          {refusal != null && (
            <p data-rail-tab-refusal className="shrink-0 border-b px-3.5 py-2 text-[11px] text-muted-foreground">
              {refusal}
            </p>
          )}
          <ColourEditor
            r={channels.r}
            g={channels.g}
            b={channels.b}
            w={hasWhite ? channels.w : undefined}
            a={hasAmber ? channels.a : undefined}
            uv={hasUv ? channels.uv : undefined}
            combinedCss={NEUTRAL_CSS}
            hasWhiteChannel={hasWhite}
            hasAmberChannel={hasAmber}
            hasUvChannel={hasUv}
            onColourChange={onColourChange}
            channelFields
            keyboardOpen={seed}
            open
            docked
            readOnly={readOnly}
            labelLine={labelLine}
            contentRef={contentRef}
            onKeyDown={onKeyDown}
            targets={targets}
            projectId={projectId}
            pickOnTargets
            onPick={onPick}
            onSave={onSave}
            onSpread={onSpread}
          />
        </>
      )}
      {saveMounted && (
        <NewTemplateFromSelectionSheet open={saving} onOpenChange={setSaving} projectId={projectId} families={COLOUR_FAMILY} targets={templateTargets} />
      )}
    </div>
  )
})

/**
 * The batch the tab edits: the marquee's Colour column, or — rows selected and no cells — the rows'
 * heads as a Colour batch. Memoised on the two inputs rather than the snapshot: the rows batch
 * walks every row's descriptors, and the snapshot is republished on marquee frames that changed
 * neither.
 */
function useMarqueeColourBatch(marquee: MarqueeSnapshot | null): CellBatch | null {
  const column = marquee?.batches.get('colour') ?? null
  const rows = marquee?.rows ?? NO_TARGETS
  const rowsBatch = useMemo(() => (rows.length > 0 ? batchForTargets(rows, 'colour') : null), [rows])
  return column ?? rowsBatch
}

/**
 * A rail tab with nothing to edit: the board's empty state — a heading, one sentence, and the
 * tab's footer drawn inert — so the tab stays open and follows the next marquee. Shared by the two
 * tabs so they say it the same way.
 */
export function RailTabEmpty({ column, hint, footer }: { column: string; hint: string; footer?: ReactNode }) {
  return (
    <>
      <div data-rail-tab-empty className="min-h-0 flex-1 space-y-2 overflow-y-auto px-3.5 pt-3">
        <EditorLabelLine docked subject="Nothing selected" column={column} />
        <p className="text-xs text-muted-foreground">{hint}</p>
      </div>
      {footer}
    </>
  )
}

/** The tab's buffer starts white with every emitter unstated — see the docblock. */
const NEUTRAL: ColourBuffer = { r: 255, g: 255, b: 255 }
/** The knob's seed — a constant, for `ColourSheet`'s reason; the knob moves on Pick. */
const NEUTRAL_CSS = computeCombinedCss(255, 255, 255, 0, 0, 0)
const NO_TARGETS: readonly WriteTarget[] = []
const NO_RESOLUTIONS: CellBatch['resolutions'] = []
const COLOUR_FAMILY = ['COLOUR'] as const

function blurActive() {
  const active = document.activeElement
  if (active instanceof HTMLElement) active.blur()
}
