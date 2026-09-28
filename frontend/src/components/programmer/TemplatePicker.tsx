import { useCallback, useEffect, useMemo, useRef, useState, type RefObject } from 'react'
import { AudioWaveform, Plus, Search } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Popover, PopoverAnchor, PopoverContent } from '@/components/ui/popover'
import { Sheet, SheetBody, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import type { LookFamilyFilter } from '@/components/ViewSwitcher'
import { PartitionChips } from '@/components/sheet/LibraryRow'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { EffectPadDetail } from '@/components/busking/EffectPadDetail'
import {
  describeTemplate,
  EFFECT_GLYPH_CLASS,
  PAD_FACE_SHELL,
  PAD_SHELL,
  padPresenceClass,
  templateSwatch,
} from '@/components/busking/padFace'
import { templateLayerPresence } from '@/components/busking/lookPresence'
import { useEditorForm } from '@/components/editor/EditorSurface'
import type { CellRef } from '@/components/sheet/cellSelectionModel'
import type { ColumnKey } from '@/components/fixtures-list/columns'
import { FAMILY_LABELS, TEMPLATE_FAMILY_ORDER, formatFamilyList, type AttributeFamily } from '@/lib/attributeFamily'
import { cn } from '@/lib/utils'
import { recentTemplates } from '@/lib/templateRecents'
import { useProgrammerAppliedQuery } from '@/store/programmer'
import { useTemplateListQuery } from '@/store/templates'
import { NewTemplateFromSelectionSheet } from './NewTemplateFromSelectionSheet'
import { templatePressTitle, useTemplatePress, useTemplatePressHandlers } from './useTemplatePress'
import { usePressFamilies } from '@/store/selection'
import type { EffectPresence } from '@/components/busking/buskingTypes'
import type { TemplateSummary, TemplateTarget } from '@/api/templatesApi'

/**
 * The whole library that fits the selection, as a searchable grid of pads.
 *
 * The row above it keeps the eight templates this desk pressed most recently; this is where the
 * other seventy-six live. The strip used to be the library — every offerable template, by name,
 * under a fade — which was sized for six and unusable at sixty, and on an iPad portrait had room
 * for one chip and no way to reach past it.
 *
 * Four things about it are decisions rather than detail:
 *
 *  - **The pad is the busk page's pad.** Same shell, same swatch-or-wave face, same three-rung
 *    presence ring read the same way (`templateLayerPresence` against the desk's resolved applied
 *    state). A template is one thing whichever surface it is pressed from, and two pad faces would
 *    be two answers to "is this one on?".
 *  - **A press does not close it.** Auditioning three colours in a row is the normal case, and a
 *    picker that shut on the first would make the second and third a trip back to the button. It
 *    closes on Escape, an outside press, or its X — and every press moves that template to the
 *    front of Recent, so the row behind it is rearranging as you work.
 *  - **The two gestures are the chip's**, through the same `useTemplatePress`: click sets literals,
 *    hold or ⌥click adds a layer that tracks the template. The hold is touch and pen only, exactly
 *    as on the chip — a mouse has ⌥, and a mouse hold would be a second silent door to the
 *    tracking mutation.
 *  - **Three forms, from the cell editor.** A popover under the `All` button on a desk or an iPad, a
 *    bottom sheet on an upright phone, a right-hand sheet where the viewport is short — the same
 *    two media queries `EditorSurface` makes, so the two panels never disagree about which
 *    shape a screen gets.
 *
 * It takes the offerable list rather than filtering one, because the strip has already decided it
 * (family, emitters, generic-vs-per-fixture) and the two must offer the same set: the footer's
 * "71 of 84 fit the selection" is a claim about the *strip's* rule, and recomputing it here is how
 * the two would come to disagree.
 */
/** One band of the grid. A stable shape so the closed state can be one shared constant. */
interface PickerSection {
  key: string
  label: string
  caption: string
  templates: readonly TemplateSummary[]
}

/**
 * The closed panel's answers, as module constants so they keep one identity.
 *
 * Identity is the whole mechanism: `sections === NO_SECTIONS` is how the render below tells "shut,
 * computed nothing" from "open, and these are the bands" — which is what lets the exit animation
 * keep drawing the last real set. A fresh `[]` each time would break that test silently.
 */
const NO_TEMPLATES: readonly TemplateSummary[] = []
const NO_SECTIONS: readonly PickerSection[] = []

export function TemplatePicker({
  projectId,
  open,
  onOpenChange,
  anchorRef,
  cells,
  askedFamilies,
  targets,
  offerable,
}: {
  projectId: number
  open: boolean
  onOpenChange: (open: boolean) => void
  /**
   * The `All · n` button, which the popover form hangs under.
   *
   * A `virtualRef` anchor rather than a `PopoverTrigger`, because the button belongs to the row's
   * layout and not to this component — the same arrangement `EditorSurface` makes for Set.
   */
  anchorRef: RefObject<HTMLElement | null>
  /** The marquee's cells; empty when rows are selected but no cells. Names the scope line. */
  cells: readonly CellRef<ColumnKey>[]
  /** The families those cells name — null when the gesture named no attribute. */
  askedFamilies: readonly AttributeFamily[] | null
  /** Where a press lands. Renders nothing when empty, like the strip. */
  targets: readonly TemplateTarget[]
  /** The templates that fit the selection, in the library's own name order. */
  offerable: readonly TemplateSummary[]
}) {
  const form = useEditorForm()
  const [search, setSearch] = useState('')
  const [family, setFamily] = useState<LookFamilyFilter>('ALL')
  const [newOpen, setNewOpen] = useState(false)
  const searchRef = useRef<HTMLInputElement>(null)

  // The whole library, for the footer's denominator only — RTK Query dedupes this against the
  // strip's own subscription, so the second `useTemplateListQuery` costs no request.
  const { data: everything } = useTemplateListQuery({ projectId }, { skip: !projectId })

  // A fresh search each time it opens: the last one belonged to the last selection.
  useEffect(() => {
    if (!open) {
      setSearch('')
      setFamily('ALL')
    }
  }, [open])

  /**
   * The family segments are the **rows-only** arm's, and are not drawn where cells fix the family.
   *
   * A marquee has already said which attribute it means, and offering four segments over a list
   * that holds one family would invite the operator to pick a filter that empties the panel.
   */
  const rowsOnly = askedFamilies == null

  /**
   * **Nothing is computed while the panel is shut.**
   *
   * This component is mounted unconditionally beside the strip, and its inputs are volatile: a
   * marquee drag mints a fresh `cells` array every animation frame, `cellFamilies` returns a fresh
   * array from it, and that identity propagates through the strip's `families` and `visible` memos
   * into `offerable`. Left ungated, every frame of a drag re-filtered and re-sorted the whole
   * library and rebuilt the pad grid's JSX, all of it discarded because the panel was closed.
   */
  const shown = useMemo(() => {
    if (!open) return NO_TEMPLATES
    const term = search.trim().toLowerCase()
    return offerable.filter(
      (template) =>
        (!rowsOnly || family === 'ALL' || template.family === family) &&
        (term === '' || template.name.toLowerCase().includes(term)),
    )
  }, [open, offerable, rowsOnly, family, search])

  const sections = useMemo<readonly PickerSection[]>(
    () =>
      !open
        ? NO_SECTIONS
        : [
            {
              key: 'recent',
              label: 'Recent',
              caption: 'what you pressed last, for this family',
              templates: recentTemplates(shown),
            },
            {
              key: 'all',
              label: 'All',
              caption: 'A–Z',
              templates: shown.filter((t) => t.kind !== 'effect' && t.isGeneric),
            },
            {
              key: 'per-fixture',
              label: 'Per fixture',
              caption: 'name their own heads',
              // Never an effect template: an effect fans over whatever the layer names, so
              // `isGeneric` is true for every one of them (fx-templates D3). This arm is the
              // focus-position case.
              templates: shown.filter((t) => !t.isGeneric),
            },
            {
              key: 'effects',
              label: 'Effects',
              caption: 'one running instance each',
              templates: shown.filter((t) => t.kind === 'effect'),
            },
          ],
    [open, shown],
  )

  /**
   * What the panel actually draws, which is the last real set while it is closing.
   *
   * Radix keeps the content mounted through its exit animation, and re-renders it from *this*
   * render's children — so handing it the closed state's empty sections would fade an empty box out
   * rather than the panel the operator just used. Writing a ref during render is safe here for the
   * reason `SelectionBar`'s `idlePresenceRef` gives: it is idempotent and touches nothing outside
   * this component.
   *
   * **Not pinned by a test, and it cannot be**: jsdom runs no CSS animations, so Radix's `Presence`
   * unmounts the content on the same commit that closes it and the exit render never happens there.
   * The closed-state gate above *is* pinned; this is the half that only exists in a browser.
   */
  const lastSections = useRef(sections)
  if (sections !== NO_SECTIONS) lastSections.current = sections
  const drawnSections = sections === NO_SECTIONS ? lastSections.current : sections

  const pressFamilies = usePressFamilies(askedFamilies)
  const press = useTemplatePress(projectId, targets, pressFamilies)

  /**
   * The presence ring, folded **once** for the whole grid.
   *
   * `BuskingView` subscribes at the container and hands its pads a `presenceOf` callback; this grid
   * is unvirtualized, so a `useProgrammerAppliedQuery()` inside the pad meant one subscription and
   * one independent `templateLayerPresence` fold per visible pad — up to the whole library. One
   * subscription, one callback, same answer.
   */
  const { data: applied } = useProgrammerAppliedQuery()
  const presenceOf = useCallback(
    (template: TemplateSummary) => templateLayerPresence(applied ?? [], targets, template.id),
    [applied, targets],
  )

  const scopeLabel =
    askedFamilies != null && askedFamilies.length > 0 ? formatFamilyList(askedFamilies, ' · ') : null
  const noun =
    askedFamilies != null && askedFamilies.length === 1
      ? `${FAMILY_LABELS[askedFamilies[0]].singular.toLowerCase()} templates`
      : 'templates'

  // D3, as the strip states it: a press needs somewhere to land, so with no targets there is
  // nothing to pick. The New sheet sits outside this guard for the strip's reason — the desk
  // selection is shared, so `targets` can empty while a name is half typed.
  const body =
    targets.length === 0 ? null : (
      <div className="flex min-h-0 flex-1 flex-col">
        <div className="flex flex-none items-center gap-2 px-3 pt-3 pb-1">
          <div className="relative min-w-0 flex-1">
            <Search className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
            <Input
              ref={searchRef}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={`Search ${offerable.length} ${noun}…`}
              className="h-8 pl-8 text-sm"
              aria-label="Search templates"
            />
          </div>
          {rowsOnly ? (
            // The library's own chips, with state of their own here: the route's `looks.family` is the
            // library's remembered view, and this panel's filter belongs to one opening. They fold to
            // a select on their own container, which is what makes them work in a portalled popover.
            <PartitionChips<AttributeFamily>
              label="Family"
              value={family}
              onChange={setFamily}
              allCount={offerable.length}
              options={TEMPLATE_FAMILY_ORDER.map((f) => ({
                value: f,
                label: FAMILY_LABELS[f].singular,
                count: offerable.filter((t) => t.family === f).length,
              }))}
            />
          ) : (
            <span className="flex shrink-0 items-center gap-1.5 text-xs whitespace-nowrap text-muted-foreground">
              {scopeLabel != null && (
                <Badge variant="outline" className="px-1.5 py-0 text-[10px]">
                  {scopeLabel}
                </Badge>
              )}
              for {cells.length} cell{cells.length === 1 ? '' : 's'}
            </span>
          )}
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto px-3 pb-2">
          {shown.length === 0 && (
            <p className="px-0.5 py-6 text-center text-xs text-muted-foreground">
              {offerable.length === 0
                ? 'No template fits what is selected.'
                : 'Nothing here matches that.'}
            </p>
          )}
          {drawnSections.map((section) =>
            section.templates.length === 0 ? null : (
              <section key={section.key}>
                <div className="flex items-center gap-2 px-0.5 pt-2.5 pb-1.5">
                  {/* `EditorLabel`, not a hand-rolled span: it is the desk's one section label, it is
                      already used outside the busk view (`SurfaceLibrary`), and the copy this
                      replaced had already drifted to `tracking-[0.1em]` against its `0.08em`. */}
                  <EditorLabel className="whitespace-nowrap">{section.label}</EditorLabel>
                  <span className="text-[10px] text-muted-foreground">{section.caption}</span>
                </div>
                <div className="grid grid-cols-[repeat(auto-fill,minmax(110px,1fr))] gap-2">
                  {section.templates.map((template) => (
                    <TemplatePad
                      key={template.id}
                      template={template}
                      presence={presenceOf(template)}
                      onPress={press}
                    />
                  ))}
                </div>
              </section>
            ),
          )}
        </div>

        <div className="flex flex-none items-center gap-2 border-t px-3 py-2 text-[10px] text-muted-foreground">
          {/* The gesture hint is the popover's alone: on either sheet the press it describes is a
              hold, which the operator has just been making, and ⌥ is a key a phone has not got. */}
          {form === 'popover' && (
            <span className="min-w-0 truncate">
              Click sets values · hold or ⌥click adds a layer that tracks it
            </span>
          )}
          <span className="ml-auto shrink-0 tabular-nums">
            {offerable.length} of {everything?.length ?? offerable.length} fit the selection
          </span>
          <Button
            variant="outline"
            size="sm"
            className="h-7 shrink-0 gap-1 border-dashed px-2 text-xs"
            onClick={() => setNewOpen(true)}
          >
            <Plus className="size-3.5" />
            New from selection
          </Button>
        </div>
      </div>
    )

  return (
    <>
      {form === 'popover' ? (
        <Popover open={open} onOpenChange={onOpenChange}>
          <PopoverAnchor virtualRef={anchorRef} />
          <PopoverContent
            align="end"
            sideOffset={6}
            // 640 at a desk and an iPad landscape, 560 where the grid column is narrower — the two
            // widths the artboards draw. A **media** query and not a container one: this content is
            // portalled to `body`, so row C's `@container` is not an ancestor of it and a container
            // class here would match nothing at all, silently.
            className="flex h-[540px] max-h-[calc(100dvh-6rem)] w-[560px] max-w-[calc(100vw-1.5rem)] flex-col p-0 min-[900px]:w-[640px]"
            // A click on the `All` button while this is open is an outside press *and* the button's
            // own toggle. Left alone, the two fight: Radix closes, the click reopens, and the panel
            // appears not to respond. Refusing the dismissal leaves the button's `onClick` as the
            // single thing deciding, which is what makes it a toggle.
            onInteractOutside={(event) => {
              if (anchorRef.current?.contains(event.target as Node)) event.preventDefault()
            }}
            // Radix's own auto-focus is a parent effect and would take the focus straight back off a
            // field focused in an effect — the same reason `useEditorKeyboard` does this here.
            onOpenAutoFocus={(event) => {
              event.preventDefault()
              searchRef.current?.focus()
            }}
          >
            {body}
          </PopoverContent>
        </Popover>
      ) : (
        <Sheet open={open} onOpenChange={onOpenChange}>
          <SheetContent
            side={form === 'bottom-sheet' ? 'bottom' : 'right'}
            aria-describedby={undefined}
            className="flex flex-col gap-0 p-0"
            style={
              form === 'bottom-sheet'
                ? { height: '80svh' }
                : // `SheetContent`'s own `sm:max-w-sm` is a 384px cap that silently wins over an
                  // inline width, which reads as the width never having been applied.
                  { width: 'min(22.5rem, 92vw)', maxWidth: 'none' }
            }
            // Both sheets are reached by a finger, and focusing the field there raises the
            // on-screen keyboard over the grid for nothing — the same split `useEditorForm`
            // already makes for a cell editor.
            onOpenAutoFocus={(event) => event.preventDefault()}
          >
            <SheetHeader className="flex-none border-b py-3 pr-10 pl-4">
              <SheetTitle className="text-base">Templates</SheetTitle>
            </SheetHeader>
            {/* `SheetBody` even though `body` brings its own scroller: CLAUDE.md's sheet rule names
                a picker as the case that *overrides* its padding rather than skipping the primitive.
                Its own `overflow-y-auto` is neutralised here — two nested scrollers would put a
                scrollbar on the panel as well as on the grid — leaving the `flex-1` that makes the
                body fill the sheet below its header. */}
            <SheetBody className="flex min-h-0 flex-col space-y-0 overflow-hidden p-0">
              {body}
            </SheetBody>
          </SheetContent>
        </Sheet>
      )}
      <NewTemplateFromSelectionSheet
        open={newOpen}
        onOpenChange={setNewOpen}
        projectId={projectId}
        // The *asked* families, matching `TemplateStrip` exactly: with rows selected and no cells
        // the gesture named no attribute, and the sheet should ask rather than pre-pick one.
        families={cells.length > 0 ? askedFamilies : null}
        targets={targets}
      />
    </>
  )
}

/**
 * One pad. The busk page's face, pressed the programmer's way.
 *
 * A component rather than markup in the loop because the presence ring and the effect detail line
 * both read live state, and a hook cannot be conditional.
 */
function TemplatePad({
  template,
  presence,
  onPress,
}: {
  template: TemplateSummary
  /** Folded once by the grid — see `presenceOf`. */
  presence: EffectPresence
  onPress: (template: TemplateSummary, additive: boolean) => void
}) {
  const swatch = templateSwatch(template)
  const isEffect = template.kind === 'effect'
  // Click, ⌥click and the touch-only hold, shared with `TemplateChip` — see
  // `useTemplatePressHandlers`. It was duplicated verbatim between the two until review.
  const handlers = useTemplatePressHandlers(template, onPress)

  return (
    <button
      type="button"
      {...handlers}
      title={templatePressTitle(template)}
      className={cn(
        PAD_SHELL,
        PAD_FACE_SHELL,
        'w-full active:scale-95 hover:brightness-110',
        padPresenceClass(presence),
      )}
    >
      <span className="flex items-center gap-1.5">
        {isEffect && <AudioWaveform className={EFFECT_GLYPH_CLASS} />}
        {swatch && (
          <span
            aria-hidden
            className="size-3 shrink-0 rounded shadow-[inset_0_0_0_1px_rgba(255,255,255,0.15)]"
            style={{ background: swatch }}
          />
        )}
        <span
          className={cn(
            'text-sm leading-tight font-medium',
            presence !== 'none' ? 'text-primary' : 'text-foreground',
          )}
        >
          {template.name}
        </span>
      </span>
      <span className="mt-0.5 line-clamp-1 text-[10px] leading-tight text-muted-foreground">
        {isEffect ? <EffectPadDetail template={template} /> : describeTemplate(template)}
      </span>
      {presence !== 'none' && (
        <div
          className={cn(
            'absolute top-1.5 right-1.5 size-2 rounded-full',
            presence === 'all' ? 'bg-primary' : 'bg-primary/50',
          )}
        />
      )}
    </button>
  )
}
