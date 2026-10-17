import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode, type RefObject } from 'react'
import { AudioWaveform, Layers, Plus, Search } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Popover, PopoverAnchor, PopoverContent } from '@/components/ui/popover'
import { Sheet, SheetBody, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import type { LookFamilyFilter } from '@/components/ViewSwitcher'
import { PartitionChips } from '@/components/sheet/LibraryRow'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { useEditorForm } from '@/components/editor/EditorSurface'
import { EffectPadDetail } from '@/components/busking/EffectPadDetail'
import {
  describeTemplate,
  EFFECT_GLYPH_CLASS,
  PAD_FACE_SHELL,
  PAD_SHELL,
  padPresenceClass,
  templateSwatch,
} from '@/components/busking/padFace'
import { lookIsApplied, lookLayerPresence, templateLayerPresence } from '@/components/busking/lookPresence'
import type { EffectPresence } from '@/components/busking/buskingTypes'
import { NewTemplateFromSelectionSheet } from '@/components/programmer/NewTemplateFromSelectionSheet'
import { LookPressButton, PalettePressButton, templatePressTitle } from './PressGestures'
import { usePress, type PressGesture, type PressInto, type PressPair } from '@/hooks/usePress'
import { FAMILY_LABELS, TEMPLATE_FAMILY_ORDER, type AttributeFamily } from '@/lib/attributeFamily'
import { lookOutsideMaskReason, outsideMaskReason, paletteFits, type PaletteFit } from '@/lib/paletteOffer'
import { recentTemplates } from '@/lib/templateRecents'
import { cn } from '@/lib/utils'
import { useLookListQuery } from '@/store/looks'
import { useProgrammerAppliedQuery } from '@/store/programmer'
import { useTemplateListQuery } from '@/store/templates'
import { useVenueMode } from '@/store/venues'
import type { LookSummary } from '@/api/looksApi'
import type { TemplateSummary } from '@/api/templatesApi'

/** Which library the picker draws. */
export type ApplyLibrary = 'palettes' | 'looks'

/**
 * The one picker (apply-model plan §4.2): every library door opens it — the palette strip's
 * `All · n` and the rail's *+ Palette* on every family the selection fits, a popup band's *All …* on
 * the cell's one family, the rail's *+ Look* on the Looks.
 *
 * Decisions rather than detail:
 *
 *  - **It presses; it never asks for targets.** A press lands on the pair the door hands it — the
 *    window's selection, or a cell batch under the cell's family — through `usePress`, with the
 *    chip's gestures (tap a tracking layer, ⌥click *Apply as values*, a hold or right-click the
 *    menu). There is no target step: what is selected is where it lands.
 *  - **A press does not close it.** Auditioning three colours in a row is the normal case.
 *  - **The pad is the busk page's pad**, its ring the desk's resolved applied state, so a record is
 *    one thing whichever surface it is pressed from. None while a Look layer is focused: a press
 *    writes that Look there.
 *  - **Outside the mask is shown, dimmed, with the reason** — what the selection's heads could take
 *    but its attribute mask leaves out. Still pressable: the desk tests the mask on the on arm only,
 *    so a lit one comes off, and an on press is refused there with the desk's own sentence.
 *  - **A Look can land on its own fixtures** — *On its own fixtures*, chosen by default with nothing
 *    selected. A Look whose effects take the layer's targets has none to land on, and is shown
 *    outside with that reason.
 *  - **Three forms, from the cell editor** (`useEditorForm`): a popover under the door on a desk, a
 *    bottom sheet on an upright phone, a right-hand sheet where the viewport is short. A door with no
 *    anchor of its own (the rail's footer, strip and handle) takes the right-hand sheet on a desk.
 */
export function ApplyPicker({
  projectId,
  open,
  onOpenChange,
  anchorRef,
  library,
  family = null,
  pair,
  fit,
  saveFamilies,
  offerNew = true,
  scope,
}: {
  projectId: number
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The door the popover hangs under — a `virtualRef`, since the button belongs to its host's layout. */
  anchorRef?: RefObject<HTMLElement | null>
  library: ApplyLibrary
  /** One family — a band's *All …* — or null for every family the heads fit. Palettes only. */
  family?: AttributeFamily | null
  /** Where a press lands and the mask it carries. */
  pair: PressPair
  /** What the heads can take. Palettes only; a Look is offered whatever the heads are. */
  fit: PaletteFit
  /**
   * The families *New from selection* opens on — null asks. Defaults to the one family when there is
   * one: with rows selected and no cells the gesture named no attribute, and the sheet should ask.
   */
  saveFamilies?: readonly AttributeFamily[] | null
  /**
   * Draw *New from selection*. A popup band's picker does not: it lives inside the cell editor, and
   * a sheet mounted there would close with the editor, draft and all — the editor's own footer has
   * *Save as palette…*.
   */
  offerNew?: boolean
  /** `usePress`'s scope — a door that always writes Local passes `'programmer'`. */
  scope?: 'window' | 'programmer'
}) {
  const form = useEditorForm()
  const { isVenue } = useVenueMode(projectId)
  const [search, setSearch] = useState('')
  const [chip, setChip] = useState<LookFamilyFilter>('ALL')
  const [ownFixtures, setOwnFixtures] = useState(pair.targets.length === 0)
  const [newOpen, setNewOpen] = useState(false)
  // Mounted on the first *New from selection* and kept: it holds a draft, which must survive the
  // selection emptying under it, and its hooks are not worth paying for until it is asked for.
  const [newMounted, setNewMounted] = useState(false)
  const searchRef = useRef<HTMLInputElement>(null)

  // A fresh search each time it opens: the last one belonged to the last selection. A Look lands
  // on its own fixtures by default only when there is nothing selected for it to land on.
  useEffect(() => {
    if (!open) return
    setSearch('')
    setChip('ALL')
    setOwnFixtures(pair.targets.length === 0)
    // Only on opening — a selection moving under an open picker must not flip the operator's switch.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  const lookOwn = library === 'looks' && ownFixtures
  const pressPair = useMemo<PressPair>(
    () => (lookOwn ? { targets: [], families: pair.families } : pair),
    [lookOwn, pair],
  )
  const { press, into } = usePress(projectId, pressPair, scope == null ? undefined : { scope })
  const { data: applied } = useProgrammerAppliedQuery()

  const onTemplate = useCallback(
    (template: TemplateSummary, gesture: PressGesture) => void press({ kind: 'template', template }, gesture),
    [press],
  )
  const onLook = useCallback(
    (look: Pick<LookSummary, 'id' | 'name'>, gesture: PressGesture) => void press({ kind: 'look', look }, gesture),
    [press],
  )

  const { data: templates } = useTemplateListQuery({ projectId }, { skip: !projectId || library !== 'palettes' })
  const { data: looks } = useLookListQuery({ projectId }, { skip: !projectId || library !== 'looks' })

  const term = search.trim().toLowerCase()
  const matches = (name: string) => term === '' || name.toLowerCase().includes(term)
  const mask = pair.families

  /**
   * **Nothing is computed while the panel is shut.** A host mounts this beside its door, and its
   * inputs are volatile: a marquee drag mints a fresh pair every animation frame.
   */
  const paletteFit = useMemo<PaletteFit>(
    () => (family != null ? { ...fit, families: [family] } : fit),
    [family, fit],
  )
  const fitting = useMemo(
    () => (!open || library !== 'palettes' ? NO_TEMPLATES : (templates ?? NO_TEMPLATES).filter((t) => paletteFits(t, paletteFit))),
    [open, library, templates, paletteFit],
  )
  const familyChips = useMemo(
    () => TEMPLATE_FAMILY_ORDER.filter((f) => fitting.some((t) => t.family === f)),
    [fitting],
  )
  const shownPalettes = useMemo(
    // The chip filters only while the chips are drawn: an offer that narrowed to one family under
    // an open panel must not leave it filtered by a chip nobody can see.
    () => fitting.filter((t) => (chip === 'ALL' || familyChips.length < 2 || t.family === chip) && matches(t.name)),
    // `matches` reads `term`.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [fitting, familyChips, chip, term],
  )

  const sections = useMemo<readonly PickerSection[]>(() => {
    if (!open) return NO_SECTIONS
    if (library === 'looks') {
      const all = (looks ?? []).filter((look) => matches(look.name))
      const inside: LookSummary[] = []
      const outside: { look: LookSummary; reason: string }[] = []
      for (const look of all) {
        const reason =
          lookOwn && look.hasDeferredEffects
            ? 'its effects take the selection — select fixtures first'
            : lookOutsideMaskReason(look.families, mask)
        if (reason == null) inside.push(look)
        else outside.push({ look, reason })
      }
      return [
        { key: 'all', label: 'All', caption: 'A–Z', items: inside.map((look) => ({ kind: 'look' as const, look })) },
        {
          key: 'outside',
          label: 'Outside the mask',
          caption: lookOwn ? 'what this press cannot land' : 'nothing of theirs is in the selection’s attributes',
          items: outside.map(({ look, reason }) => ({ kind: 'look' as const, look, reason })),
        },
      ]
    }
    const inside = shownPalettes.filter((t) => outsideMaskReason(t.family, mask) == null)
    const outside = shownPalettes.flatMap((t) => {
      const reason = outsideMaskReason(t.family, mask)
      return reason == null ? [] : [{ kind: 'palette' as const, template: t, reason }]
    })
    const palettes = (list: readonly TemplateSummary[]) => list.map((template) => ({ kind: 'palette' as const, template }))
    return [
      { key: 'recent', label: 'Recent', caption: 'what you pressed last', items: palettes(recentTemplates(inside)) },
      { key: 'all', label: 'All', caption: 'A–Z', items: palettes(inside.filter((t) => t.kind !== 'effect' && t.isGeneric)) },
      // Never an effect palette: an effect fans over whatever the layer names, so `isGeneric` is
      // true for every one of them (fx-templates D3). This arm is the focus-position case.
      { key: 'per-fixture', label: 'Per fixture', caption: 'name their own heads', items: palettes(inside.filter((t) => !t.isGeneric)) },
      { key: 'effects', label: 'Effects', caption: 'one running instance each', items: palettes(inside.filter((t) => t.kind === 'effect')) },
      { key: 'outside', label: 'Outside the mask', caption: 'the heads could take these; the mask leaves them out', items: outside },
    ]
    // `matches` reads `term`.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, library, looks, lookOwn, mask, shownPalettes, term])

  /**
   * What the panel draws, which is the last real set while it is closing: Radix keeps the content
   * mounted through its exit animation and re-renders it from this render's children. Not pinned by
   * a test — jsdom runs no animations, so the exit render never happens there.
   */
  const lastSections = useRef(sections)
  if (sections !== NO_SECTIONS) lastSections.current = sections
  const drawnSections = sections === NO_SECTIONS ? lastSections.current : sections

  const presenceOfTemplate = (template: TemplateSummary): EffectPresence =>
    into.kind === 'look' ? 'none' : templateLayerPresence(applied ?? [], pair.targets, template.id)
  const presenceOfLook = (look: LookSummary): EffectPresence =>
    into.kind === 'look'
      ? 'none'
      : lookOwn
        ? lookIsApplied(applied ?? [], look.id)
          ? 'all'
          : 'none'
        : lookLayerPresence(applied ?? [], pair.targets, look.id)

  const total = library === 'looks' ? (looks?.length ?? 0) : (templates?.length ?? 0)
  const offered = library === 'looks' ? (looks?.length ?? 0) : fitting.length
  const noun =
    library === 'looks'
      ? 'Looks'
      : family != null
        ? `${FAMILY_LABELS[family].singular.toLowerCase()} palettes`
        : 'palettes'
  const empty = drawnSections.every((section) => section.items.length === 0)
  const heads = pair.targets.length

  const body = (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex flex-none flex-wrap items-center gap-2 px-3 pt-3 pb-1">
        <div className="relative min-w-0 flex-1">
          <Search className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input
            ref={searchRef}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={`Search ${offered} ${noun}…`}
            className="h-8 pl-8 text-sm"
            aria-label={`Search ${noun}`}
          />
        </div>
        {library === 'palettes' && family == null && familyChips.length > 1 ? (
          <PartitionChips<AttributeFamily>
            label="Family"
            value={chip}
            onChange={setChip}
            allCount={fitting.length}
            options={familyChips.map((f) => ({
              value: f,
              label: FAMILY_LABELS[f].singular,
              count: fitting.filter((t) => t.family === f).length,
            }))}
          />
        ) : library === 'palettes' && family != null ? (
          <span className="flex shrink-0 items-center gap-1.5 text-xs whitespace-nowrap text-muted-foreground">
            <Badge variant="outline" className="px-1.5 py-0 text-[10px]">
              {FAMILY_LABELS[family].singular}
            </Badge>
            on {heads} head{heads === 1 ? '' : 's'}
          </span>
        ) : library === 'looks' ? (
          <ToggleGroup
            type="single"
            aria-label="Where a Look lands"
            value={ownFixtures ? 'own' : 'selection'}
            onValueChange={(next) => next && setOwnFixtures(next === 'own')}
            className="h-8 shrink-0"
          >
            <ToggleGroupItem value="selection" disabled={heads === 0} className="h-8 px-2 text-xs">
              On {heads} selected
            </ToggleGroupItem>
            <ToggleGroupItem value="own" className="h-8 px-2 text-xs">
              On its own fixtures
            </ToggleGroupItem>
          </ToggleGroup>
        ) : null}
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-3 pb-2">
        {library === 'palettes' && heads === 0 && (
          <p className="px-0.5 pt-2 text-[11px] text-muted-foreground">
            Nothing selected — only a per-fixture palette has heads of its own to land on.
          </p>
        )}
        {empty && (
          <p className="px-0.5 py-6 text-center text-xs text-muted-foreground">
            {offered === 0
              ? library === 'looks'
                ? 'No Looks yet. Record one from the programmer.'
                : 'No palette fits what is selected.'
              : 'Nothing here matches that.'}
          </p>
        )}
        {drawnSections.map((section) =>
          section.items.length === 0 ? null : (
            <section key={section.key} data-picker-section={section.key}>
              <div className="flex items-center gap-2 px-0.5 pt-2.5 pb-1.5">
                <EditorLabel className="whitespace-nowrap">{section.label}</EditorLabel>
                <span className="text-[10px] text-muted-foreground">{section.caption}</span>
              </div>
              <div className="grid grid-cols-[repeat(auto-fill,minmax(110px,1fr))] gap-2">
                {section.items.map((item) =>
                  item.kind === 'palette' ? (
                    <TemplatePad
                      key={`t${item.template.id}`}
                      projectId={projectId}
                      template={item.template}
                      into={into}
                      presence={presenceOfTemplate(item.template)}
                      hasTargets={heads > 0}
                      reason={item.reason}
                      onGesture={onTemplate}
                    />
                  ) : (
                    <LookPad
                      key={`l${item.look.id}`}
                      look={item.look}
                      into={into}
                      presence={presenceOfLook(item.look)}
                      hasTargets={!lookOwn && heads > 0}
                      reason={item.reason}
                      onGesture={onLook}
                    />
                  ),
                )}
              </div>
            </section>
          ),
        )}
      </div>

      <div className="flex flex-none items-center gap-2 border-t px-3 py-2 text-[10px] text-muted-foreground">
        {/* The gesture hint is the popover's alone: on either sheet the operator is holding and
            tapping, and ⌥ is a key a phone has not got. */}
        {form === 'popover' && (
          <span className="min-w-0 truncate">
            {into.kind === 'look'
              ? `Click writes into ${into.lookName} · hold or right-click for more`
              : 'Click puts a layer on · ⌥click sets values · hold or right-click for more'}
          </span>
        )}
        <span className="ml-auto shrink-0 tabular-nums">
          {library === 'looks' ? `${total} Looks` : `${offered} of ${total} fit the selection`}
        </span>
        {/* Not in a venue: a palette is recorded from the programmer, and Record is off there. */}
        {library === 'palettes' && offerNew && !isVenue && (
          <Button
            variant="outline"
            size="sm"
            className="h-7 shrink-0 gap-1 border-dashed px-2 text-xs"
            disabled={heads === 0}
            onClick={() => {
              setNewMounted(true)
              setNewOpen(true)
            }}
          >
            <Plus className="size-3.5" />
            New from selection
          </Button>
        )}
      </div>
    </div>
  )

  const title = library === 'looks' ? 'Looks' : family != null ? `${FAMILY_LABELS[family].singular} palettes` : 'Palettes'
  const asPopover = form === 'popover' && anchorRef != null

  return (
    <>
      {asPopover ? (
        <Popover open={open} onOpenChange={onOpenChange}>
          <PopoverAnchor virtualRef={anchorRef} />
          <PopoverContent
            align="end"
            sideOffset={6}
            aria-label={title}
            // A **media** query and not a container one: this content is portalled to `body`, so no
            // host's `@container` is an ancestor of it and a container class would match nothing.
            className="flex h-[540px] max-h-[calc(100dvh-6rem)] w-[560px] max-w-[calc(100vw-1.5rem)] flex-col p-0 min-[900px]:w-[640px]"
            // A click on the door while this is open is an outside press *and* the door's own
            // toggle; refusing the dismissal leaves the door's `onClick` the single thing deciding.
            onInteractOutside={(event) => {
              if (anchorRef.current?.contains(event.target as Node)) event.preventDefault()
            }}
            // Radix's own auto-focus is a parent effect and would take the focus straight back off a
            // field focused in an effect.
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
                : // `SheetContent`'s own `sm:max-w-sm` silently wins over an inline width.
                  { width: form === 'popover' ? 'min(40rem, 92vw)' : 'min(22.5rem, 92vw)', maxWidth: 'none' }
            }
            // A finger's sheets: focusing the field raises the on-screen keyboard for nothing.
            onOpenAutoFocus={(event) => {
              event.preventDefault()
              if (form === 'popover') searchRef.current?.focus()
            }}
          >
            <SheetHeader className="flex-none border-b py-3 pr-10 pl-4">
              <SheetTitle className="text-base">{title}</SheetTitle>
            </SheetHeader>
            {/* `SheetBody`'s own scroller neutralised: `body` brings its own, and two would put a
                scrollbar on the panel as well as on the grid. */}
            <SheetBody className="flex min-h-0 flex-col space-y-0 overflow-hidden p-0">{body}</SheetBody>
          </SheetContent>
        </Sheet>
      )}
      {newMounted && (
        <NewTemplateFromSelectionSheet
          open={newOpen}
          onOpenChange={setNewOpen}
          projectId={projectId}
          families={saveFamilies !== undefined ? saveFamilies : family != null ? [family] : null}
          targets={pair.targets}
        />
      )}
    </>
  )
}

type PickerItem =
  | { kind: 'palette'; template: TemplateSummary; reason?: string }
  | { kind: 'look'; look: LookSummary; reason?: string }

interface PickerSection {
  key: string
  label: string
  caption: string
  items: readonly PickerItem[]
}

/** The closed panel's answers, as module constants so `=== NO_SECTIONS` can tell "shut" from "empty". */
const NO_TEMPLATES: readonly TemplateSummary[] = []
const NO_SECTIONS: readonly PickerSection[] = []

/** The pad's dimmed arm and its reason, under the face. */
function PadReason({ reason }: { reason?: string }) {
  return reason == null ? null : (
    <span data-outside-reason className="mt-0.5 line-clamp-2 text-[10px] leading-tight text-amber-600 dark:text-amber-400">
      {reason}
    </span>
  )
}

function PadDot({ presence }: { presence: EffectPresence }) {
  return presence === 'none' ? null : (
    <div className={cn('absolute top-1.5 right-1.5 size-2 rounded-full', presence === 'all' ? 'bg-primary' : 'bg-primary/50')} />
  )
}

function padClass(presence: EffectPresence, reason?: string) {
  return cn(PAD_SHELL, PAD_FACE_SHELL, 'w-full active:scale-95 hover:brightness-110', padPresenceClass(presence), reason != null && 'opacity-55')
}

function PadName({ presence, children }: { presence: EffectPresence; children: ReactNode }) {
  return (
    <span className={cn('text-sm leading-tight font-medium', presence !== 'none' ? 'text-primary' : 'text-foreground')}>
      {children}
    </span>
  )
}

/** One palette: the busk page's face, pressed the chip's way. */
function TemplatePad({
  projectId,
  template,
  into,
  presence,
  hasTargets,
  reason,
  onGesture,
}: {
  projectId: number
  template: TemplateSummary
  into: PressInto
  presence: EffectPresence
  hasTargets: boolean
  reason?: string
  onGesture: (template: TemplateSummary, gesture: PressGesture) => void
}) {
  const swatch = templateSwatch(template)
  const isEffect = template.kind === 'effect'
  return (
    <PalettePressButton
      projectId={projectId}
      template={template}
      into={into}
      presence={presence}
      hasTargets={hasTargets}
      onGesture={onGesture}
    >
      {(handlers) => (
        <button
          type="button"
          {...handlers}
          data-picker-palette={template.id}
          title={reason ?? templatePressTitle(template, into)}
          className={padClass(presence, reason)}
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
            <PadName presence={presence}>{template.name}</PadName>
          </span>
          <span className="mt-0.5 line-clamp-1 text-[10px] leading-tight text-muted-foreground">
            {isEffect ? <EffectPadDetail template={template} /> : describeTemplate(template)}
          </span>
          <PadReason reason={reason} />
          <PadDot presence={presence} />
        </button>
      )}
    </PalettePressButton>
  )
}

/** One Look: its families and counts under its name, pressed the chip's way. */
function LookPad({
  look,
  into,
  presence,
  hasTargets,
  reason,
  onGesture,
}: {
  look: LookSummary
  into: PressInto
  presence: EffectPresence
  hasTargets: boolean
  reason?: string
  onGesture: (look: Pick<LookSummary, 'id' | 'name'>, gesture: PressGesture) => void
}) {
  return (
    <LookPressButton look={look} into={into} presence={presence} hasTargets={hasTargets} onGesture={onGesture}>
      {(handlers) => (
        <button
          type="button"
          {...handlers}
          data-picker-look={look.id}
          title={
            reason ??
            (into.kind === 'look'
              ? 'Looks don’t nest — focus Local to press one'
              : `Click to put “${look.name}” on as a layer that tracks it · ⌥click to set its values · hold or right-click for more`)
          }
          className={padClass(presence, reason)}
        >
          <span className="flex items-center gap-1.5">
            <Layers className="size-3 shrink-0 text-muted-foreground" />
            <PadName presence={presence}>{look.name}</PadName>
          </span>
          <span className="mt-0.5 line-clamp-1 text-[10px] leading-tight text-muted-foreground">
            {look.families.map((f) => FAMILY_LABELS[f].singular).join(' · ')}
            {look.effectCount > 0 && ` · ${look.effectCount} effect${look.effectCount === 1 ? '' : 's'}`}
          </span>
          <PadReason reason={reason} />
          <PadDot presence={presence} />
        </button>
      )}
    </LookPressButton>
  )
}
