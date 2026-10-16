import { useCallback, useMemo, useRef, useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { AudioWaveform, LayoutGrid, Plus } from 'lucide-react'
import { cn } from '@/lib/utils'
import { PHONE_FOLDED_CLASS } from '@/components/sheet/toolbarFolds'
import { useScrollEdges } from '@/hooks/useScrollEdges'
import type { CellRef } from '@/components/sheet/cellSelectionModel'
import type { ColumnKey } from '@/components/fixtures-list/columns'
import { type AttributeFamily } from '@/lib/attributeFamily'
import { EFFECT_GLYPH_CLASS, padPresenceClass } from '@/components/busking/padFace'
import { templateLayerPresence } from '@/components/busking/lookPresence'
import type { EffectPresence } from '@/components/busking/buskingTypes'
import { PalettePressButton, templatePressTitle } from '@/components/press/PressGestures'
import { usePress, type PressGesture, type PressInto } from '@/hooks/usePress'
import { useProgrammerAppliedQuery } from '@/store/programmer'
import { templateRowsSwatch, describeTemplateIntent } from '@/lib/templateIntent'
import { stripTemplates } from '@/lib/templateRecents'
import { useTemplateListQuery } from '@/store/templates'
import { NewTemplateFromSelectionSheet } from './NewTemplateFromSelectionSheet'
import { useVenueMode } from '@/store/venues'
import { TemplatePicker } from './TemplatePicker'
import { usePressFamilies } from '@/store/selection'
import type { TemplateSummary, TemplateTarget } from '@/api/templatesApi'

/**
 * The template strip: the templates that fit what you have selected, one press away.
 *
 * **The selection is the filter**, which is the whole design: select colour cells and only colour
 * templates are offered, so there is no picker to open and no family dropdown to get wrong. It reads
 * the *cell* selection where there is one (the marquee says which attribute you mean) and falls back
 * to what the selected fixtures **can take** where there is not — a rig of RGB pars with no mover
 * on it is offered no position template, because "does this head have the family at all" is the
 * first half of template compatibility (fx-templates D6).
 *
 * The **second half is the emitters**. A template may name `white`, `amber` or `uv` outright, and a
 * head without that emitter refuses the *whole* template rather than the row — so an RGB-only par is
 * offered no "Amber Key", even though amber is COLOUR and the par has colour. The family alone
 * cannot draw that line: the hex and all three emitters are one family.
 *
 * **The selection is the target too**, by the same rule: a marquee over three colour cells lands
 * the press on those three heads — which, since the two selections became one, are also the
 * fixture selection — and a row selection lands it on the rows. The container derives all three
 * (`templateTargets`, `targetFamilies`, `targetEmitters`
 * on `renderToolbar`) because only it knows which rows the cells sit on; the strip reads nothing
 * from Redux.
 *
 * **The gestures are the press's** (`usePress`, apply-model plan D1, D8), the same on every chip
 * and pad on the desk:
 *
 *  - **tap** puts the palette on as a layer that *tracks* it, targeted at the selection and masked
 *    to its family — or takes the selected heads off it when it already covers them all. The desk
 *    replaces the palette's family on those heads (D2). Retune the palette and every layer moves.
 *  - **⌥click** is *Apply as values*: literals in Local, which retuning the palette never moves.
 *  - **hold or right-click** opens the menu — the tap's verb named, *Apply as values*, *Pick up*,
 *    *Edit palette…* — and never presses.
 *
 * With a Look layer focused a tap writes the palette into that Look instead (D7), and the chips
 * draw no ring. Otherwise each chip wears the pad's ring from the desk's applied state, and the
 * row is filtered by the selection's mask as well as by what the heads can take (§4.2).
 *
 * The last chip records the selection as a new template, which is how the library fills up without
 * anyone visiting it. It is drawn **outside** the scroller, pinned to its right: it is the one
 * control here that is not a member of the library, and a chip that fills the library must not be
 * the chip that scrolls off the end of it.
 *
 * **It renders nothing with no targets, which is a reversal.** Until session 2 of the space plan
 * the strip showed the *whole* library with nothing selected and a press toasted "select the
 * fixtures this should land on first". That was the single most expensive line on the page: a row
 * of chips — wrapping to four rows on a real library — spending 43px of a grid's height on a
 * gesture that could only fail. The library is browsed on `/palettes`; this strip is where a
 * template is *pressed*, and a press needs a target (space plan D3). `usePress` still refuses a
 * generic palette with no targets, as defence in depth: the toggle route would take one as a layer
 * that lands nowhere.
 *
 * **The chips are the eight most recently *pressed*, not the whole library** (see `stripTemplates`).
 * A show has sixty templates and this row was sized for six; the fix is not a longer scroller but a
 * different question — the row answers "what am I reaching for", and `All · n` opens a searchable
 * pad grid for everything else (`TemplatePicker`). Recency is a **desk** fact, stamped server-side
 * on every press from any surface, so the row agrees with the busk page and with the hardware.
 *
 * **It is a row of the selection bar, not a band of its own.** It renders a leading hairline, then
 * the chips in a `flex-1 min-w-0 overflow-x-auto` scroller under a right-edge mask, then `All · n`,
 * then `New` — four siblings of one flex line that `ProgrammerGrid` owns, which is why there is no
 * wrapper element around them. Below 600px of that row the scroller is not drawn and the last two
 * are the whole strip. The hairline belongs to the strip rather than to the bar so that the two
 * appear and disappear together: the bar renders whenever anything is selected, and the strip
 * whenever a press has somewhere to land, and those are not quite the same condition (a selected
 * group row with no visible members resolves to no targets at all).
 */
export function TemplateStrip({
  projectId,
  cells,
  askedFamilies,
  targets,
  targetFamilies,
  targetEmitters = [],
}: {
  projectId: number
  /** The marquee's cells. Empty when the operator has selected rows but not cells. */
  cells: readonly CellRef<ColumnKey>[]
  /**
   * The families those cells name, already derived by the bar for its own badge — `null` when
   * there are no cells. Passed in rather than recomputed so the badge and these chips answer from
   * one evaluation: they sit a few pixels apart, and a marquee drag mints a fresh `cells` array
   * every animation frame, so deriving it twice was two passes per frame to say one thing.
   */
  askedFamilies: readonly AttributeFamily[] | null
  /** Where a press lands: the cells' heads when there is a marquee, the selected rows' otherwise. */
  targets: readonly TemplateTarget[]
  /** The families those heads have at all. Empty when nothing is selected. */
  targetFamilies: readonly AttributeFamily[]
  /** The bundled emitters those heads have — `white` / `amber` / `uv`. Empty when none does. */
  targetEmitters?: readonly string[]
}) {
  const { data: templates } = useTemplateListQuery({ projectId }, { skip: !projectId })
  const { isVenue } = useVenueMode(projectId)
  const [newOpen, setNewOpen] = useState(false)
  const [pickerOpen, setPickerOpen] = useState(false)
  const scrollerRef = useRef<HTMLDivElement>(null)
  const allRef = useRef<HTMLButtonElement>(null)
  // The mask the press carries: the desk's while following, the marquee's own when unlinked (D4).
  const pressFamilies = usePressFamilies(askedFamilies)
  const pair = useMemo(() => ({ targets, families: pressFamilies }), [targets, pressFamilies])
  const { press, into } = usePress(projectId, pair)
  const onGesture = useCallback(
    (template: TemplateSummary, gesture: PressGesture) => void press({ kind: 'template', template }, gesture),
    [press],
  )

  // The pad's ring, from the desk's resolved applied state (apply-model plan §4.2). None while a
  // Look layer is focused: a press writes the Look's values there, and a ring would describe the
  // programmer's layers instead.
  const { data: applied } = useProgrammerAppliedQuery()
  const presenceOf = useCallback(
    (template: TemplateSummary): EffectPresence =>
      into.kind === 'look' ? 'none' : templateLayerPresence(applied ?? [], targets, template.id),
    [applied, targets, into],
  )

  /**
   * The families the selection is asking about.
   *
   * From the **cells** when there are any — a marquee across the Colour column means colour, and
   * nothing else. That arm arrives as `askedFamilies` from the bar, which needs the same answer
   * for its badge, so the filter and the label beside it answer from one evaluation rather than
   * two that could drift. With rows selected but no cells there is no attribute in the gesture, so
   * the answer is what those heads *have*: every family they could take, none they could not.
   *
   * The third arm — `null`, "no question yet" — no longer reaches the screen: with no targets the
   * strip renders nothing. It still *runs*, because hooks execute before the early return that
   * discards their result, so this is dead output rather than dead code — do not "simplify" it on
   * the assumption that the branch cannot be taken. It is kept because `null` is also what the
   * *sheet* below is handed for "the operator named no attribute", which the rows-only arm
   * produces, and collapsing the two would make that prop lie.
   */
  const families = useMemo<readonly AttributeFamily[] | null>(() => {
    if (askedFamilies != null) return askedFamilies
    if (targets.length > 0) return targetFamilies
    return null
  }, [askedFamilies, targets.length, targetFamilies])

  const visible = useMemo(() => {
    // The library's own order, which is by name — the same list `/palettes` draws. There is no
    // operator-set order to honour any more: order belongs to a pad's place in a busk bank.
    const all = templates ?? []
    if (families == null) return all
    return all.filter(
      (t) =>
        t.family != null &&
        families.includes(t.family) &&
        // The selection's mask, too (apply-model plan §4.2): a palette outside it is not offered,
        // since the desk would refuse it. An offer, never a refusal — the press itself is not
        // pre-refused, and with every attribute the mask is null and filters nothing.
        (pressFamilies == null || pressFamilies.length === 0 || pressFamilies.includes(t.family)) &&
        // Every emitter the template names has to be somewhere in the selection. A **union** over
        // the heads, matching how `targetFamilies` is built: with a hex and a par selected together
        // the amber template is still offered, and the par reports a skip on the press. Requiring
        // every head to have it would hide most of the library from most mixed selections.
        //
        // `?? []` is not defensive noise. `templateList` has no `transformResponse`, so this field
        // is whatever the desk sent — and lighting7 hot-swaps changed handler bodies but *not* new
        // response fields, so a desk mid-upgrade serves rows without it. Reading `.every` off
        // undefined there would take out the whole programmer toolbar until someone restarted the
        // backend. Absent means "names no emitter", which is what every template predating this
        // field actually is.
        (t.requiredEmitters ?? []).every((emitter) => targetEmitters.includes(emitter)),
    )
  }, [templates, families, pressFamilies, targetEmitters])

  /**
   * The chips the row actually draws: the templates this desk pressed most recently, most recent
   * first, up to eight — or the first eight by name where nothing has been pressed yet.
   *
   * The strip used to draw the whole offerable list. That was sized for six templates and a real
   * show has sixty; on an iPad portrait the row has space for one chip, and there was no way to
   * reach past it. So the row keeps what you reach for and `All · n` opens the rest.
   *
   * "For the selected family" needs no parameter: [visible] has already been filtered by family,
   * emitters and generic-vs-per-fixture, so the recents are per-family by construction.
   */
  const shown = useMemo(() => stripTemplates(visible), [visible])

  /**
   * Values, then a hairline, then effects (fx-templates D10) — the busk column's split, sideways.
   *
   * Order holds inside each half — recency now, name order in the fallback arm. The hairline is
   * drawn only when both halves have something in them, so a colour selection with no colour
   * effect templates looks exactly as it did.
   */
  const valueChips = useMemo(() => shown.filter((t) => t.kind !== 'effect'), [shown])
  const effectChips = useMemo(() => shown.filter((t) => t.kind === 'effect'), [shown])

  // The chips are a *scroller*, so the mask has to be conditional. A fade drawn over content that
  // fits says "there is more to the right" when there is not, and after the two-flex-1 fix below
  // the scroller is wide enough that a short library routinely fits — so the false affordance
  // would have been the common case, not the edge one. Measured rather than guessed: neither the
  // chip count nor the container width predicts it on its own.
  // `measureOnRender`: the chip list changes without the scroller resizing, and the mask has to
  // be right in the frame the chips land in. See `useScrollEdges`.
  const { overflows, attach } = useScrollEdges(scrollerRef, { measureOnRender: true })

  // D3. Not "and the library is empty" as well: a press needs a target whatever the library holds,
  // and the family badge, the counts and Deselect beside it still have something to say without it.
  //
  // **The sheet is deliberately OUTSIDE this guard.** It is rendered below, past the early return,
  // because it holds a draft: the desk selection is server-owned and shared (another client, a
  // MIDI select button, a group whose membership changed), so `targets` can empty while the
  // operator is halfway through typing a template name. Unmounting the sheet with the strip
  // discarded that name silently — `Sheet`'s `unsavedChanges` guard only intercepts the closes
  // *Radix* drives (Escape, outside-click, the X), never a parent unmount, so not even the
  // "Discard changes?" prompt would have fired. The old guard hid this: it also required the
  // library to be empty, which on a real project it never is.
  const strip =
    targets.length === 0 ? null : (
      <>
        {/* The hairline that separates what is selected from what can be pressed onto it. Drawn
            here rather than by the bar so it cannot outlive the chips — see the doc comment. The
            class is the internal separator's, verbatim, so this file has one hairline style and
            `SurfaceLibrary`'s "TemplateStrip's hairline, verbatim" keeps naming one thing. */}
        <span aria-hidden className={HAIRLINE_CLASS} />
        {/* The chips, on one line, scrolling sideways under a fade rather than wrapping. Wrapping
            is what made the old band cost four rows on a forty-template library; the mask says
            there is more to the right without spending a scrollbar's height on saying so, and the
            row is short enough that a trackpad or a shift-wheel is the whole gesture.

            `min-w-0` is load-bearing beside `flex-1`: a flex item's default `min-width: auto` is
            its content, so without it the chips would push the bar wider than the grid instead of
            scrolling inside it, and the mask would never have anything to fade. */}
        <div
          // `attach`, not `scrollerRef` — see `useScrollEdges`. This scroller mounts with the
          // selection, so the hook has to be told when it arrives.
          ref={attach}
          className={cn(
            // **Not drawn below 600px of row C.** A phone has room for one chip beside the counts,
            // and one chip of sixty is a worse answer than none: there the library is reached
            // through `All · n` alone, and Recent is the first section of the sheet it opens.
            // `hidden … flex` rather than an unmount, so the scroller's measured overflow and the
            // hook attached to it survive a rotation.
            'hidden min-w-0 flex-1 items-center gap-1.5 overflow-x-auto @[600px]:flex',
            overflows && [
              '[mask-image:linear-gradient(90deg,#000_92%,transparent)]',
              '[-webkit-mask-image:linear-gradient(90deg,#000_92%,transparent)]',
            ],
            // The scrollbar is the horizontal one on a 28px-tall row: showing it would take a
            // third of the chips' height. The overflow is still scrollable by wheel, trackpad
            // and keyboard.
            '[scrollbar-width:none] [&::-webkit-scrollbar]:hidden',
          )}
        >
          {visible.length === 0 && (templates?.length ?? 0) > 0 && (
            <span className="whitespace-nowrap text-[11px] text-muted-foreground">
              No palette fits what is selected.
            </span>
          )}

          {valueChips.map((template) => (
            <TemplateChip
              key={template.id}
              projectId={projectId}
              template={template}
              into={into}
              presence={presenceOf(template)}
              onGesture={onGesture}
            />
          ))}

          {valueChips.length > 0 && effectChips.length > 0 && (
            <span aria-hidden className={HAIRLINE_CLASS} />
          )}

          {effectChips.map((template) => (
            <TemplateChip
              key={template.id}
              projectId={projectId}
              template={template}
              into={into}
              presence={presenceOf(template)}
              onGesture={onGesture}
            />
          ))}
        </div>

        {/* The rest of the library, one button away, at **every** width — it is the only way in
            below 600px, where the scroller above is not drawn at all.

            `size="sm"` with `h-7`: it sits in the chips' row, so it takes their nested tier rather
            than the row's own 32px verbs. The count is what the selection *fits*, which is the
            same rule the scroller filters by (`visible`) rather than the whole library — the
            picker's footer is where the two numbers are compared. */}
        <Button
          ref={allRef}
          variant="outline"
          size="sm"
          className="h-7 shrink-0 gap-1.5 px-2 text-xs"
          aria-expanded={pickerOpen}
          title="Every palette that fits the selection, searchable"
          onClick={() => setPickerOpen((wasOpen) => !wasOpen)}
        >
          <LayoutGrid className="size-3.5" />
          All
          <Badge variant="secondary" className="px-1 py-0 text-[10px] tabular-nums">
            {visible.length}
          </Badge>
        </Button>

        {/* The chip that fills the library, pinned outside the scroller. `h-7` like the chips: a
            control inside a control is 28 — the nested tier of the chrome system, the same height
            as Update and Revert inside the source box. No `disabled` arm: the
            guard above has already returned, so it could only ever have rendered enabled, and a
            dead conditional whose `title` no longer explains the state it guards is worse than no
            conditional. `usePress`'s guard is the one that stays — it is the only one a future host
            rendering this component differently could still reach. */}
        {/* Not in a venue: a template is recorded from the programmer, and Record is off there (venue
            plan D11; the desk refuses one with `VENUE_RECORD_OFF`). */}
        {!isVenue && (
        <Button
          variant="outline"
          size="sm"
          className="h-7 shrink-0 gap-1 border-dashed px-2 text-xs"
          title="Record what you have selected as a new palette"
          aria-label="New palette"
          onClick={() => setNewOpen(true)}
        >
          <Plus className="size-3.5" />
          {/* The word folds on the phone arm (desk-follow plan D8): row C is full to the pixel at
              375 with a cell selected — glyph · count · All · New · Set · Clear · Deselect — and
              the desk chip's link badge (21 + a gap, drawn all night while following) had to come
              from somewhere without moving a control. The word is ~30 and the title says it. */}
          <span className={PHONE_FOLDED_CLASS}>New</span>
        </Button>
        )}
      </>
    )

  return (
    <>
      {strip}
      {/* Outside the guard for the sheet's reason below — and because Radix needs the content
          mounted for the close animation the moment `targets` empties under an open picker. */}
      <TemplatePicker
        projectId={projectId}
        open={pickerOpen}
        onOpenChange={setPickerOpen}
        anchorRef={allRef}
        cells={cells}
        askedFamilies={askedFamilies}
        targets={targets}
        offerable={visible}
        into={into}
        presenceOf={presenceOf}
        onGesture={onGesture}
      />
      <NewTemplateFromSelectionSheet
        open={newOpen}
        onOpenChange={setNewOpen}
        projectId={projectId}
        // The *asked* families, not the capability list: with rows selected and no cells the
        // gesture named no attribute, and the sheet should ask rather than pre-pick one of several.
        families={cells.length > 0 ? families : null}
        targets={targets}
      />
    </>
  )
}

/**
 * One hairline style for this file: the bar's leading separator and the values/effects divider.
 * They are the same kind of line at the same rank, and `SurfaceLibrary`'s hairline copies this
 * one by name — two sizes here would make that reference ambiguous about which it meant.
 */
const HAIRLINE_CLASS = 'mx-0.5 h-5 w-px shrink-0 bg-border'

/**
 * One palette on the recents row: tap puts it on as a layer, ⌥click sets its values, a hold or a
 * right-click opens the menu (`PalettePressButton`), and it wears the pad's ring.
 */
function TemplateChip({
  projectId,
  template,
  into,
  presence,
  onGesture,
}: {
  projectId: number
  template: TemplateSummary
  into: PressInto
  presence: EffectPresence
  onGesture: (template: TemplateSummary, gesture: PressGesture) => void
}) {
  const swatch = templateRowsSwatch(template.rows)
  return (
    <PalettePressButton
      projectId={projectId}
      template={template}
      into={into}
      presence={presence}
      onGesture={onGesture}
    >
      {(handlers) => (
        <button
          type="button"
          {...handlers}
          title={templatePressTitle(template, into)}
          aria-pressed={presence !== 'none'}
          data-presence={presence}
          className={cn(
            // `shrink-0` is what makes the row a scroller rather than a squeezer: without it flex
            // would compress every chip to fit and the mask would never fade anything. `h-7` is the
            // nested tier: a chip is a control inside the selection bar's 40px row, so it is 28 where
            // the row's own verbs are 32.
            'flex h-7 shrink-0 items-center gap-1.5 rounded-md border px-2 text-xs transition-colors',
            'hover:bg-accent/60 active:scale-95',
            padPresenceClass(presence),
          )}
        >
          {/* An effect template holds no rows, so there is no value to preview — the glyph the whole
              desk uses for FX says what the press will do instead of a blank gap. */}
          {template.kind === 'effect' ? (
            <AudioWaveform className={EFFECT_GLYPH_CLASS} />
          ) : swatch != null ? (
            <span className="size-3 rounded-sm border border-border/60" style={{ background: swatch }} />
          ) : (
            <span className="font-mono text-[10px] tabular-nums text-muted-foreground">
              {/* A per-fixture palette holds one value per head, so no one row describes it — the
                  library sheet's *n heads* rule; a generic one is its row. */}
              {!template.isGeneric
                ? `${template.rows?.length ?? 0} heads`
                : template.rows?.[0] != null
                  ? describeTemplateIntent(template.rows[0].value)
                  : ''}
            </span>
          )}
          <span className={cn('truncate max-w-32', presence !== 'none' && 'text-primary')}>{template.name}</span>
        </button>
      )}
    </PalettePressButton>
  )
}
