import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode, type RefObject } from 'react'
import { AudioWaveform, ChevronLeft, Layers, Plus, Search } from 'lucide-react'
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
import { FxPadDetail } from '@/components/busking/FxPadDetail'
import {
  describePalette,
  FX_GLYPH_CLASS,
  PAD_FACE_SHELL,
  PAD_SHELL,
  padPresenceClass,
  paletteSwatch,
} from '@/components/busking/padFace'
import { lookIsApplied } from '@/components/busking/lookPresence'
import type { FxPresence } from '@/components/busking/buskingTypes'
import { NewPaletteFromSelectionSheet } from '@/components/programmer/NewPaletteFromSelectionSheet'
import { LookPressButton, PalettePressButton, palettePressTitle } from './PressGestures'
import { usePress, usePressInCue, usePressLook, type PressGesture, type PressInto, type PressPair } from '@/hooks/usePress'
import { usePressCue } from './pressCue'
import { pressPresence } from './pressPresence'
import { selectionKeyOf, storedFxOf } from '@/components/fx/draftFx'
import { FxEditor } from '@/components/fx/FxEditor'
import { FxPicker, type AuditionedFx } from '@/components/fx/FxPicker'
import { pickerFamilyForSheet } from '@/components/fx/fxEditorModel'
import { fxTargetOf } from '@/components/fx/selectionFxTarget'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { useActiveFxQuery, useFxLibraryQuery } from '@/store/fixtureFx'
import { useGroupListQuery } from '@/store/groups'
import { useStopFx } from '@/components/fx/useFxControls'
import { FAMILY_LABELS, PALETTE_FAMILY_ORDER, type AttributeFamily } from '@/lib/attributeFamily'
import { lookOutsideMaskReason, outsideMaskReason, paletteFits, type PaletteFit } from '@/lib/paletteOffer'
import { recentPalettes } from '@/lib/paletteRecents'
import { cn } from '@/lib/utils'
import { useAbsorbLookFxMutation, useLookListQuery } from '@/store/looks'
import { useProgrammerAppliedQuery } from '@/store/programmer'
import { usePaletteListQuery } from '@/store/palettes'
import { useVenueMode } from '@/store/venues'
import type { LookSummary } from '@/api/looksApi'
import type { PaletteSummary } from '@/api/palettesApi'
import type { CueAdHocFx } from '@/api/cuesApi'

/**
 * Which library the picker draws. `fx` is the bare effects (`FxPicker` on the bare effect
 * press) above the effect palettes that fit — ⌘K *Apply FX*, the FX table's *+ Effect* and a band's
 * *+ Effect …*; the palettes' own Effects section reaches it too.
 */
export type ApplyLibrary = 'palettes' | 'looks' | 'fx'

/**
 * The one picker (apply-model plan §4.2): every library door opens it — the palette strip's
 * `All · n` and the Stack table's *Palette…* on every family the selection fits, a popup band's
 * *All …* on the cell's one family, the Stack table's *Look…* on the Looks.
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
 *  - **In the cue editor it writes the cue** (`usePressCue`, apply-model plan §3.6): a palette or a
 *    Look goes on as a cue layer, or comes off, and the effects arm stores a bare effect in draft;
 *    a pad is ringed where the cue layers its record. Nothing reaches the stage.
 *  - **Three forms, from the cell editor** (`useEditorForm`): a popover under the door on a desk, a
 *    bottom sheet on an upright phone, a right-hand sheet where the viewport is short. A door with no
 *    anchor of its own (the Stack table's *+ Look or palette*) takes the right-hand sheet on a desk.
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
  property,
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
  /** The effects library's property — a cell's — which `FxPicker` prefers and opens the family of. */
  property?: string
}) {
  const form = useEditorForm()
  const { isVenue } = useVenueMode(projectId)
  const [search, setSearch] = useState('')
  const [chip, setChip] = useState<LookFamilyFilter>('ALL')
  const [ownFixtures, setOwnFixtures] = useState(pair.targets.length === 0)
  // The palettes' Effects section's *+ Effect …*: the effects arm, until the operator goes back.
  const [fxView, setFxView] = useState(false)
  const shown: ApplyLibrary = library === 'fx' || fxView ? 'fx' : library
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
    setFxView(false)
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
  const cue = usePressCue()

  const onPalette = useCallback(
    (template: PaletteSummary, gesture: PressGesture) => void press({ kind: 'palette', palette: template }, gesture),
    [press],
  )
  const onLook = useCallback(
    (look: Pick<LookSummary, 'id' | 'name'>, gesture: PressGesture) => void press({ kind: 'look', look }, gesture),
    [press],
  )

  const { data: templates } = usePaletteListQuery({ projectId }, { skip: !projectId || library !== 'palettes' })
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
  // Effect palettes are narrowed by the property their effect drives, as the cell's Effects band
  // narrows them, so the band and its *All …* offer one set.
  const { data: fxLibrary } = useFxLibraryQuery(undefined, { skip: !open || library !== 'palettes' })
  const fitContext = useMemo(() => ({ library: fxLibrary, templates }), [fxLibrary, templates])
  const fitting = useMemo(
    () =>
      !open || library !== 'palettes'
        ? NO_PALETTES
        : (templates ?? NO_PALETTES).filter((t) => paletteFits(t, paletteFit, fitContext)),
    [open, library, templates, paletteFit, fitContext],
  )
  const familyChips = useMemo(
    () => PALETTE_FAMILY_ORDER.filter((f) => fitting.some((t) => t.family === f)),
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
          lookOwn && look.hasDeferredFx
            ? 'its FX take the selection — select fixtures first'
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
      return reason == null ? [] : [{ kind: 'palette' as const, palette: t, reason }]
    })
    const palettes = (list: readonly PaletteSummary[]) => list.map((palette) => ({ kind: 'palette' as const, palette }))
    return [
      { key: 'recent', label: 'Recent', caption: 'what you pressed last', items: palettes(recentPalettes(inside)) },
      { key: 'all', label: 'All', caption: 'A–Z', items: palettes(inside.filter((t) => t.kind !== 'fx' && t.isGeneric)) },
      // Never an effect palette: an effect fans over whatever the layer names, so `isGeneric` is
      // true for every one of them (fx-templates D3). This arm is the focus-position case.
      { key: 'per-fixture', label: 'Per fixture', caption: 'name their own heads', items: palettes(inside.filter((t) => !t.isGeneric)) },
      {
        key: 'fx',
        label: 'FX',
        caption: 'one running instance each',
        items: [...palettes(inside.filter((t) => t.kind === 'fx')), { kind: 'bare' as const }],
      },
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

  const presenceOfPalette = (template: PaletteSummary): FxPresence =>
    pressPresence(into, cue, applied, pair.targets, 'PALETTE', template.id)
  const presenceOfLook = (look: LookSummary): FxPresence =>
    into.kind === 'programmer' && lookOwn
      ? lookIsApplied(applied ?? [], look.id)
        ? 'all'
        : 'none'
      : pressPresence(into, cue, applied, pair.targets, 'LOOK', look.id)

  const total = library === 'looks' ? (looks?.length ?? 0) : (templates?.length ?? 0)
  const offered = library === 'looks' ? (looks?.length ?? 0) : fitting.length
  const noun =
    library === 'looks'
      ? 'Looks'
      : family != null
        ? `${FAMILY_LABELS[family].singular.toLowerCase()} palettes`
        : 'palettes'
  const empty = drawnSections.every((section) => section.items.every((item) => item.kind === 'bare'))
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
                  item.kind === 'bare' ? (
                    <button
                      key="bare"
                      type="button"
                      data-picker-bare-effects
                      title="A library FX over the selection — one Local FX, taking these heads off the family’s others"
                      onClick={() => setFxView(true)}
                      className={cn(padClass('none'), 'border-dashed')}
                    >
                      <span className="flex items-center gap-1.5">
                        <Plus className={FX_GLYPH_CLASS} />
                        <PadName presence="none">FX…</PadName>
                      </span>
                      <span className="mt-0.5 line-clamp-1 text-[10px] leading-tight text-muted-foreground">from the library</span>
                    </button>
                  ) : item.kind === 'palette' ? (
                    <PalettePad
                      key={`t${item.palette.id}`}
                      projectId={projectId}
                      palette={item.palette}
                      into={into}
                      presence={presenceOfPalette(item.palette)}
                      hasTargets={heads > 0}
                      reason={item.reason}
                      onGesture={onPalette}
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
              : into.kind === 'cue'
                ? `Click puts a layer on in ${into.cueLabel}, or takes it off${library === 'palettes' ? " · ⌥click sets a value palette's values" : ''} · hold or right-click for more`
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

  const title =
    shown === 'fx'
      ? 'FX'
      : library === 'looks'
        ? 'Looks'
        : family != null
          ? `${FAMILY_LABELS[family].singular} palettes`
          : 'Palettes'
  const asPopover = form === 'popover' && anchorRef != null
  const content =
    shown === 'fx' ? (
      <EffectsArm
        projectId={projectId}
        open={open}
        pair={pair}
        fit={paletteFit}
        family={family}
        property={property}
        scope={scope}
        onBack={library === 'fx' ? undefined : () => setFxView(false)}
      />
    ) : (
      body
    )

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
            // The palettes and Looks hold their height so a search or a family chip never moves the
            // panel under the pointer; the effects size to their list, which can be one row (a gobo
            // cell's Slot Steps), up to the same 540px.
            className={cn(
              'flex max-h-[calc(100dvh-6rem)] w-[560px] max-w-[calc(100vw-1.5rem)] flex-col p-0 min-[900px]:w-[640px]',
              shown === 'fx' ? 'max-h-[min(540px,calc(100dvh-6rem))]' : 'h-[540px]',
            )}
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
            {content}
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
                ? shown === 'fx'
                  ? { maxHeight: '80svh' }
                  : { height: '80svh' }
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
            <SheetBody className="flex min-h-0 flex-col space-y-0 overflow-hidden p-0">{content}</SheetBody>
          </SheetContent>
        </Sheet>
      )}
      {newMounted && (
        <NewPaletteFromSelectionSheet
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
  | { kind: 'palette'; palette: PaletteSummary; reason?: string }
  | { kind: 'look'; look: LookSummary; reason?: string }
  /** The Effects section's way to the bare effects. */
  | { kind: 'bare' }

interface PickerSection {
  key: string
  label: string
  caption: string
  items: readonly PickerItem[]
}

/** The closed panel's answers, as module constants so `=== NO_SECTIONS` can tell "shut" from "empty". */
const NO_PALETTES: readonly PaletteSummary[] = []
const NO_SECTIONS: readonly PickerSection[] = []

/** The pad's dimmed arm and its reason, under the face. */
function PadReason({ reason }: { reason?: string }) {
  return reason == null ? null : (
    <span data-outside-reason className="mt-0.5 line-clamp-2 text-[10px] leading-tight text-amber-600 dark:text-amber-400">
      {reason}
    </span>
  )
}

function PadDot({ presence }: { presence: FxPresence }) {
  return presence === 'none' ? null : (
    <div className={cn('absolute top-1.5 right-1.5 size-2 rounded-full', presence === 'all' ? 'bg-primary' : 'bg-primary/50')} />
  )
}

function padClass(presence: FxPresence, reason?: string) {
  return cn(PAD_SHELL, PAD_FACE_SHELL, 'w-full active:scale-95 hover:brightness-110', padPresenceClass(presence), reason != null && 'opacity-55')
}

function PadName({ presence, children }: { presence: FxPresence; children: ReactNode }) {
  return (
    <span className={cn('text-sm leading-tight font-medium', presence !== 'none' ? 'text-primary' : 'text-foreground')}>
      {children}
    </span>
  )
}

/** One palette: the busk page's face, pressed the chip's way. */
function PalettePad({
  projectId,
  palette: template,
  into,
  presence,
  hasTargets,
  reason,
  onGesture,
}: {
  projectId: number
  palette: PaletteSummary
  into: PressInto
  presence: FxPresence
  hasTargets: boolean
  reason?: string
  onGesture: (template: PaletteSummary, gesture: PressGesture) => void
}) {
  const swatch = paletteSwatch(template)
  const isFx = template.kind === 'fx'
  return (
    <PalettePressButton
      projectId={projectId}
      palette={template}
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
          title={reason ?? palettePressTitle(template, into)}
          className={padClass(presence, reason)}
        >
          <span className="flex items-center gap-1.5">
            {isFx && <AudioWaveform className={FX_GLYPH_CLASS} />}
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
            {isFx ? <FxPadDetail palette={template} /> : describePalette(template)}
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
  presence: FxPresence
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
              : into.kind === 'cue'
                ? `Click to put “${look.name}” on in ${into.cueLabel} as a layer, or take it off · hold or right-click for more`
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
            {look.fxCount > 0 && ` · ${look.fxCount} FX`}
          </span>
          <PadReason reason={reason} />
          <PadDot presence={presence} />
        </button>
      )}
    </LookPressButton>
  )
}

/**
 * The effects arm: the add sheet ⌘K and *+ Effect* had, folded into the one picker (apply-model plan §5). Above,
 * **`FxPicker` on the bare effect press** — a tap starts one Local effect over the pair the door
 * handed it (`POST /programmer/fx`, its mask the pair's), which first takes those heads off
 * every other Local effect of its family; another tap swaps it, phase kept; *Edit <name>* opens the
 * live editor here, whose Done comes back to the list. Below, the **effect palettes** that fit,
 * pressed the chip's way through `usePress`.
 *
 * **Under a focused Look** the bare effect starts on `/fx/add` alone and is absorbed into the Look
 * once the session ends — the panel closed or unmounted — by the id the create answered, after the
 * editor has landed any move its floor still held: the press's replacement would take heads off
 * other Local effects, which a press into a Look must not do (§3.2 rule 6). An effect palette goes in
 * through `usePress`'s own Look arm.
 *
 * **In the cue editor** the bare effect is `FxPicker`'s draft arm (show-cue-editing plan D17): a tap
 * stores the effect in the cue and runs nothing, another tap swaps it, and there is no *Edit* — the
 * FX tab edits a stored effect. An effect palette goes on as a cue layer.
 */
function EffectsArm({
  projectId,
  open,
  pair,
  fit,
  family,
  property,
  scope,
  onBack,
}: {
  projectId: number
  open: boolean
  pair: PressPair
  fit: PaletteFit
  family: AttributeFamily | null
  property?: string
  scope?: 'window' | 'programmer'
  /** Back to the palettes — absent where the door opened the effects. */
  onBack?: () => void
}) {
  const options = useMemo(() => (scope == null ? undefined : { scope }), [scope])
  const { press, into } = usePress(projectId, pair, options)
  const look = usePressLook(options)
  const cue = usePressInCue(options)
  // The cue's draft this session picked, on the selection it was picked over — what a second tap
  // swaps (show-cue-editing plan D17). A tap over another selection adds afresh.
  const [picked, setPicked] = useState<{ effect: CueAdHocFx; over: string } | null>(null)
  const [absorb] = useAbsorbLookFxMutation()
  const { fixtures } = useFixtureLookup()
  const { data: groups } = useGroupListQuery()
  const { data: active } = useActiveFxQuery()
  const { data: templates } = usePaletteListQuery({ projectId }, { skip: !projectId })
  const { data: applied } = useProgrammerAppliedQuery()
  const { data: library } = useFxLibraryQuery()
  const stopFx = useStopFx()
  const target = useMemo(() => fxTargetOf(pair.targets, fixtures, groups), [pair.targets, fixtures, groups])
  const over = selectionKeyOf(pair.targets)
  const swapping = picked != null && picked.over === over ? picked.effect : null
  const [current, setCurrent] = useState<AuditionedFx | null>(null)
  const [editing, setEditing] = useState(false)
  const live = current != null ? ((active ?? []).find((e) => e.id === current.fxId) ?? null) : null

  /** The open editor's "land what the floor holds" — written before the effect goes into the Look. */
  const flushEditor = useRef<(() => void) | null>(null)
  const session = useRef<{ current: AuditionedFx | null; look: typeof look; finished: boolean }>({
    current: null,
    look,
    finished: false,
  })
  session.current.current = current
  session.current.look = look
  const finish = useCallback(() => {
    const s = session.current
    if (s.finished) return
    s.finished = true
    if (s.current == null || s.look == null) return
    flushEditor.current?.()
    void absorb({ projectId: s.look.projectId, lookId: s.look.lookId, fxIds: [s.current.fxId] })
  }, [absorb])
  // A fresh session per open, the one before it finished first; a close or an unmount finishes it.
  useEffect(() => {
    if (open) {
      session.current.finished = false
      setCurrent(null)
      setEditing(false)
      setPicked(null)
      return
    }
    finish()
  }, [open, finish])
  useEffect(() => finish, [finish])

  const onPalette = useCallback(
    (template: PaletteSummary, gesture: PressGesture) => void press({ kind: 'palette', palette: template }, gesture),
    [press],
  )
  const context = useMemo(() => ({ library, templates }), [library, templates])
  const fxFit = useMemo<PaletteFit>(
    () => ({ ...fit, families: family != null ? [family] : fit.families, property: property ?? fit.property }),
    [fit, family, property],
  )
  const mask = pair.families
  const palettes = useMemo(
    () =>
      (templates ?? NO_PALETTES).filter((t) => t.kind === 'fx' && paletteFits(t, fxFit, context)).map((t) => ({
        palette: t,
        reason: outsideMaskReason(t.family, mask) ?? undefined,
      })),
    [templates, fxFit, context, mask],
  )
  const heads = pair.targets.length
  const pressFamilies = useMemo(() => ({ projectId, families: pair.families }), [projectId, pair.families])

  return (
    <div data-picker-effects className="flex min-h-0 flex-1 flex-col">
      <div className="flex flex-none items-center gap-2 px-3 pt-3 pb-1 text-xs text-muted-foreground">
        {onBack != null && (
          <Button variant="ghost" size="sm" className="h-7 gap-1 px-1.5 text-xs" onClick={onBack}>
            <ChevronLeft className="size-3.5" />
            Palettes
          </Button>
        )}
        <span className="min-w-0 truncate">
          on {heads} head{heads === 1 ? '' : 's'}
          {into.kind === 'look' ? ` · into ${into.lookName}` : into.kind === 'cue' ? ` · stored in ${into.cueLabel}` : ' · Local'}
        </span>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto px-3 pb-2">
        {target == null ? (
          <p className="px-0.5 py-6 text-center text-xs text-muted-foreground">Select the heads the FX should drive first.</p>
        ) : cue != null ? (
          <div className="pt-1">
            <FxPicker
              target={target}
              initialFamily={pickerFamilyForSheet(family)}
              preferredProperty={property ?? fit.property}
              draft={{
                current: swapping == null ? null : { fxType: swapping.fxType, propertyName: swapping.propertyName ?? '' },
                onPick: (pick) => {
                  const effect = storedFxOf(pick, target)
                  // Taken as picked at once, so a quick second tap swaps it; a write that did not
                  // land puts back what was picked before, so no later tap swaps a stranger.
                  const before = picked
                  setPicked({ effect, over })
                  void cue.pickFx(effect, swapping).then((ok) => {
                    if (!ok) setPicked((now) => (now?.effect === effect ? before : now))
                  })
                },
              }}
            />
          </div>
        ) : editing && live != null ? (
          <div className="flex flex-col gap-2 pt-1">
            <Button variant="ghost" size="sm" className="h-7 self-start gap-1 px-1.5 text-xs" onClick={() => setEditing(false)}>
              <ChevronLeft className="size-3.5" />
              FX
            </Button>
            <FxEditor
              key={`${live.id}:${live.fxType}`}
              effect={live}
              onDone={() => setEditing(false)}
              onStop={() => {
                // Stopped, so nothing is left to absorb: the session's effect is cleared with it.
                stopFx(live)
                setCurrent(null)
                setEditing(false)
              }}
              flushRef={flushEditor}
            />
          </div>
        ) : (
          <div className="pt-1">
            <FxPicker
              target={target}
              initialFamily={pickerFamilyForSheet(family)}
              preferredProperty={property ?? fit.property}
              current={current}
              onCurrent={setCurrent}
              onEdit={() => setEditing(true)}
              press={into.kind === 'look' ? undefined : pressFamilies}
            />
          </div>
        )}
        {palettes.length > 0 && !(editing && live != null) && (
          <section data-picker-section="effect-palettes">
            <div className="flex items-center gap-2 px-0.5 pt-3 pb-1.5">
              <EditorLabel className="whitespace-nowrap">FX palettes</EditorLabel>
              <span className="text-[10px] text-muted-foreground">a layer tracking the palette</span>
            </div>
            <div className="grid grid-cols-[repeat(auto-fill,minmax(110px,1fr))] gap-2">
              {palettes.map(({ palette: template, reason }) => (
                <PalettePad
                  key={`t${template.id}`}
                  projectId={projectId}
                  palette={template}
                  into={into}
                  presence={pressPresence(into, cue, applied, pair.targets, 'PALETTE', template.id)}
                  hasTargets={heads > 0}
                  reason={reason}
                  onGesture={onPalette}
                />
              ))}
            </div>
          </section>
        )}
      </div>
    </div>
  )
}
