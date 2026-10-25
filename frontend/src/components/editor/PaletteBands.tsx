import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react'
import { AudioWaveform, LayoutGrid, Plus } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { padPresenceClass, paletteSwatch } from '@/components/busking/padFace'
import { targetEmitters, paletteTargetsOf, type WriteTarget } from '@/components/fixtures-list/rowModel'
import { ApplyPicker } from '@/components/press/ApplyPicker'
import { PalettePressButton, palettePressTitle } from '@/components/press/PressGestures'
import { NewPaletteFromSelectionSheet } from '@/components/programmer/NewPaletteFromSelectionSheet'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { usePress, type PressGesture, type PressInto, type PressPair } from '@/hooks/usePress'
import { FAMILY_LABELS, attributeFamilyForFxFamily, formatFamilyList, type AttributeFamily } from '@/lib/attributeFamily'
import { paletteFits, type PaletteFit } from '@/lib/paletteOffer'
import { paletteAttributeFor } from '@/lib/paletteIntent'
import { recentFirst } from '@/lib/paletteRecents'
import { cn } from '@/lib/utils'
import { isCueFx } from '@/components/fx/fxEditorModel'
import { fxReaches, storedFxReaches } from '@/components/fx/fxSplit'
import { useCueBandProject, usePressCue } from '@/components/press/pressCue'
import { pressPresence } from '@/components/press/pressPresence'
import { FxEditorPopover } from '@/components/fx/FxEditorPopover'
import { useStopFx } from '@/components/fx/useFxControls'
import { useActiveFxQuery, useFxLibraryQuery } from '@/store/fixtureFx'
import { useProgrammerAppliedQuery } from '@/store/programmer'
import { usePaletteListQuery } from '@/store/palettes'
import { useVenueMode } from '@/store/venues'
import type { PaletteSummary, PaletteTarget } from '@/api/palettesApi'
import { EditorFooter } from './EditorFooter'
import { EditorLabel } from './EditorLabel'

/**
 * Can a value palette carry this property at all? Its rows are a closed vocabulary
 * (`PALETTE_ATTRIBUTES`), so a slider cell whose property is outside it — a speed, say — has no
 * Palettes band and nothing to save. A setting cell (gobo, prism) passes `palettes={false}` to
 * [PaletteBands] whatever its property: a value palette cannot carry a slotted role (§4.3).
 */
export function paletteCarries(attribute: string): boolean {
  return paletteAttributeFor(attribute) != null
}

/**
 * The per-property popup (apply-model plan §4.3, D4): the family's palettes **round** a value
 * cell's editor, in whichever of its three forms it opens.
 *
 *  - **Above**, the Palettes band ([PaletteBand]): its header names the heads the press lands on and
 *    the cell's family as the mask, then the family's value palettes that fit those heads, recent
 *    first and then by name, two rows of them, with *All …* for the family's whole picker.
 *  - **The editor**, unchanged: a drag or a typed value is a Local value, as it always was.
 *  - **Below**, the Effects band ([FxBand]): the effects running on these heads, the family's
 *    effect palettes and *+ Effect …*.
 *  - **Then** the footer: *Save as palette…* from the cell's values, beside the family's own tools
 *    ([PaletteFooter]).
 *
 * Drawn only where a press has a door: the programmer passes `projectId`, and the cue editor's
 * Values grid lands its presses in the cue (apply-model plan §3.6, `usePressCue`) — there the bands
 * are drawn and the footer is not, since *Save as palette…* records from the programmer. The plain
 * lists have neither and draw their editor alone. A cell whose property no value palette can carry —
 * a speed, a setting — has no Palettes band and no footer, and keeps its Effects band.
 */
export function PaletteBands({
  projectId,
  family,
  attribute,
  targets,
  onSave,
  tools,
  palettes = true,
  children,
}: {
  projectId?: number
  family: AttributeFamily
  /** The cell's property — a Beam cell is offered the Beam palettes, and the effects, that set it. */
  attribute: string
  /** The cell batch's heads, as a commit lands on them. */
  targets: readonly WriteTarget[]
  /** *Save as palette…* — the host mounts the sheet, outside its popover ([usePaletteSave]). */
  onSave?: () => void
  /** The family's own tools, after the save in the footer. */
  tools?: ReactNode
  /**
   * Draw the Palettes band and the footer — false for a setting cell (gobo, prism): a value palette
   * cannot carry a slotted role, so its popup is the Effects band alone (§4.3, §11 question 11).
   */
  palettes?: boolean
  children: ReactNode
}) {
  const pressTargets = useMemo(() => paletteTargetsOf(targets), [targets])
  const emitters = useMemo(() => targetEmitters(targets), [targets])
  const families = useMemo(() => [family], [family])
  const cueProject = useCueBandProject()
  const bandProject = projectId ?? cueProject
  if (bandProject == null) return <>{children}</>
  const carries = palettes && paletteCarries(attribute)
  return (
    <>
      {carries && (
        <PaletteBand
          projectId={bandProject}
          family={family}
          attribute={attribute}
          targets={pressTargets}
          families={families}
          emitters={emitters}
        />
      )}
      {children}
      <FxBand projectId={bandProject} family={family} attribute={attribute} targets={pressTargets} families={families} />
      {carries && projectId != null && <PaletteFooter projectId={projectId} canSave={targets.length > 0} onSave={onSave} tools={tools} />}
    </>
  )
}

/**
 * The Palettes band: the family's value palettes that fit the heads, pressed onto them.
 *
 * **A press lands on the cell batch under the cell's family** — `families` is the press's mask, the
 * cell's own family in a cell (§3.2 rule 5), the busk pair's in the busk Colour tab — through
 * `usePress`, with the chip's gestures: tap a tracking layer, ⌥click *Apply as values*, a hold or a
 * right-click the menu. Its chips wear the pad's ring; under a focused Look a teal line says where a
 * press goes instead (*Into Warm wash*) and the rings go, since a Look holds values. In the cue
 * editor the line says *Into Q1 · as a layer* and a chip is ringed (partly) where the cue layers it.
 *
 * **The offer is `paletteFits`'s**, the picker's too: the family, every emitter a palette names somewhere
 * in the heads — so a Shehds zone is offered the colour palettes its zone takes — and for a Beam cell
 * the palettes with a row for its property. Effect palettes are the Effects band's.
 *
 * **Two rows, never a scroller**: the chips wrap and the band clips at two rows, so how many show is
 * the panel's width to say. *All · n* opens the one picker ([ApplyPicker]) on this family.
 */
export function PaletteBand({
  projectId,
  family,
  attribute,
  targets,
  families,
  emitters,
  scope,
  className,
}: {
  projectId: number
  family: AttributeFamily
  attribute?: string
  /** Where a press lands — fixtures by key, a cell by its element key. */
  targets: readonly PaletteTarget[]
  /** The press's mask. */
  families: readonly AttributeFamily[] | null
  /** The bundled emitters the heads have, for the offer. */
  emitters: readonly string[]
  /** `usePress`'s scope — the busk tabs pass `'programmer'`: they write Local whatever the grid is pointed at. */
  scope?: 'window' | 'programmer'
  className?: string
}) {
  const { data: templates } = usePaletteListQuery({ projectId })
  const { data: applied } = useProgrammerAppliedQuery()
  const pair = useMemo<PressPair>(() => ({ targets, families }), [targets, families])
  const pressOptions = useMemo(() => (scope == null ? undefined : { scope }), [scope])
  const { press, into } = usePress(projectId, pair, pressOptions)
  const cue = usePressCue()
  const onGesture = useCallback(
    (template: PaletteSummary, gesture: PressGesture) => void press({ kind: 'palette', palette: template }, gesture),
    [press],
  )
  const presenceOf = (template: PaletteSummary) => pressPresence(into, cue, applied, targets, 'PALETTE', template.id)
  const fit = useMemo<PaletteFit>(() => ({ families: [family], emitters, attribute }), [family, emitters, attribute])
  const offered = useMemo(
    () => recentFirst((templates ?? []).filter((t) => t.kind === 'value' && paletteFits(t, fit))),
    [templates, fit],
  )
  const [pickerOpen, setPickerOpen] = useState(false)
  // Mounted on the first *All* and kept, so a band costs no picker until one is asked for and the
  // picker still slides out when it closes.
  const [pickerMounted, setPickerMounted] = useState(false)
  const allRef = useRef<HTMLButtonElement>(null)
  const label = FAMILY_LABELS[family].singular

  return (
    <div data-palette-band={family} className={cn('space-y-1.5', className)}>
      <div className="flex min-w-0 items-center gap-1.5">
        <EditorLabel className="shrink-0">Palettes</EditorLabel>
        <Badge
          variant="outline"
          className="shrink-0 px-1.5 py-0 text-[10px]"
          title={maskTitle(label, family, families)}
        >
          {label}
        </Badge>
        <HeadsLine targets={targets} />
        <Button
          ref={allRef}
          type="button"
          variant="ghost"
          size="sm"
          className="ml-auto h-6 shrink-0 gap-1 px-1.5 text-[11px]"
          aria-expanded={pickerOpen}
          title={`Every ${label.toLowerCase()} palette that fits these heads`}
          onClick={() => {
            setPickerMounted(true)
            setPickerOpen((wasOpen) => !wasOpen)
          }}
        >
          <LayoutGrid className="size-3" />
          All
          <span className="tabular-nums text-muted-foreground">{offered.length}</span>
        </Button>
      </div>
      {into.kind !== 'programmer' && <IntoLine into={into} />}
      {offered.length === 0 ? (
        <p className="text-[10px] text-muted-foreground">No {label.toLowerCase()} palette fits these heads</p>
      ) : (
        // Two rows of 28px chips and the gap between them; the rest are a press on *All* away.
        <div className="flex max-h-[62px] flex-wrap gap-1.5 overflow-hidden">
          {offered.map((template) => {
            const presence = presenceOf(template)
            const swatch = paletteSwatch(template)
            return (
              <PalettePressButton
                key={template.id}
                projectId={projectId}
                palette={template}
                into={into}
                presence={presence}
                hasTargets={targets.length > 0}
                onGesture={onGesture}
              >
                {(handlers) => (
                  <button
                    type="button"
                    data-band-palette={template.id}
                    {...handlers}
                    title={palettePressTitle(template, into)}
                    aria-pressed={presence !== 'none'}
                    className={cn(
                      'inline-flex h-7 items-center gap-1.5 rounded-md border px-2 text-xs hover:bg-accent',
                      padPresenceClass(presence),
                    )}
                  >
                    {swatch != null && (
                      <span aria-hidden className="size-3 rounded-full border border-border" style={{ background: swatch }} />
                    )}
                    <span className={cn('max-w-[9rem] truncate', presence !== 'none' && 'text-primary')}>
                      {template.name}
                    </span>
                  </button>
                )}
              </PalettePressButton>
            )
          })}
        </div>
      )}
      {pickerMounted && (
        <ApplyPicker
          projectId={projectId}
          open={pickerOpen}
          onOpenChange={setPickerOpen}
          anchorRef={allRef}
          library="palettes"
          family={family}
          pair={pair}
          fit={fit}
          offerNew={false}
          scope={scope}
        />
      )}
    </div>
  )
}

/** Where a press goes when it is not the programmer: the focused Look, or the cue being edited. */
function IntoLine({ into }: { into: Exclude<PressInto, { kind: 'programmer' }> }) {
  return into.kind === 'look' ? (
    <div
      data-palette-band-into
      className="rounded border border-teal-600/30 bg-teal-500/10 px-2 py-1 text-[11px] text-teal-700 dark:text-teal-300"
    >
      Into {into.lookName}
    </div>
  ) : (
    <div
      data-palette-band-into
      className="rounded border border-sky-600/30 bg-sky-500/10 px-2 py-1 text-[11px] text-sky-700 dark:text-sky-300"
    >
      Into {into.cueLabel} · as a layer
    </div>
  )
}

/** What the badge says about the mask a press carries — the cell's family in a cell, the host's elsewhere. */
function maskTitle(label: string, family: AttributeFamily, families: readonly AttributeFamily[] | null): string {
  if (families == null || families.length === 0) return `${label} palettes · the press carries every attribute`
  if (families.length === 1 && families[0] === family) return `The press is masked to ${label.toLowerCase()} — this cell’s family`
  return `${label} palettes · the press is masked to ${formatFamilyList(families, ' · ')}`
}

/** The heads a press lands on, by name — a cell by its element key, which is what the press sends. */
function HeadsLine({ targets }: { targets: readonly PaletteTarget[] }) {
  const { fixtureByKey } = useFixtureLookup()
  const names = useMemo(
    () => targets.map((target) => fixtureByKey.get(target.key)?.name ?? target.key),
    [fixtureByKey, targets],
  )
  if (names.length === 0) return <span className="truncate text-[10px] text-muted-foreground">no heads</span>
  const shown = names.slice(0, 3).join(', ') + (names.length > 3 ? ` +${names.length - 3}` : '')
  return (
    <span data-palette-band-heads className="min-w-0 truncate text-[10px] text-muted-foreground" title={names.join(', ')}>
      on {shown}
    </span>
  )
}

/**
 * The popup's footer: *Save as palette…* for every family, then the family's own tools. Not in a
 * venue — a palette is recorded from the programmer, and Record is off there.
 */
export function PaletteFooter({
  projectId,
  canSave,
  onSave,
  tools,
  className,
}: {
  projectId: number
  canSave: boolean
  onSave?: () => void
  tools?: ReactNode
  /** A docked host's own padding — the busk tabs' `px-3 py-2`. */
  className?: string
}) {
  const { isVenue } = useVenueMode(projectId)
  return (
    <EditorFooter
      className={className}
      save={
        isVenue ? undefined : (
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="h-7 min-w-0 text-xs"
            disabled={onSave == null || !canSave}
            title="Record these heads’ values as a palette — the route to something a layer can track"
            onClick={onSave}
          >
            Save as palette…
          </Button>
        )
      }
    >
      {tools}
    </EditorFooter>
  )
}

/**
 * *Save as palette…*'s sheet for a cell: mounted once Save has been pressed and kept from then on,
 * and drawn by the host **outside** its editor surface — a press inside the sheet is an outside
 * press to a popover, which closes, and a sheet inside it would close with it.
 */
export function usePaletteSave(
  projectId: number | undefined,
  family: AttributeFamily,
  targets: readonly WriteTarget[],
): { onSave?: () => void; sheet: ReactNode } {
  const [mounted, setMounted] = useState(false)
  const [open, setOpen] = useState(false)
  const families = useMemo(() => [family], [family])
  const paletteTargets = useMemo(() => paletteTargetsOf(targets), [targets])
  const onSave = useCallback(() => {
    setMounted(true)
    setOpen(true)
  }, [])
  if (projectId == null) return { sheet: null }
  return {
    onSave,
    sheet: mounted ? (
      <NewPaletteFromSelectionSheet
        open={open}
        onOpenChange={setOpen}
        projectId={projectId}
        families={families}
        targets={paletteTargets}
      />
    ) : null,
  }
}

/**
 * The Effects band (apply-model plan §4.3, D5): below the editor, what runs on the cell's heads and
 * what can be started on them.
 *
 *  - **Running**: every effect on this property that paints one of the heads, lit. A tap opens the
 *    live editor beside the chip ([FxEditorPopover]) over the cell batch, so a Local effect on more
 *    heads than these offers the split — *Apply to Hex 1–2 · Edit all 4*; a palette layer's is
 *    edited as its layer's (P5) and a cue's is read-only, its home being the cue.
 *  - **The family's effect palettes** that fit the heads, filtered as the Palettes band filters
 *    ([paletteFits]) — so a zoom cell is offered the Beam effect palettes that drive zoom, a gobo cell
 *    the Slot Steps of its gobo — pressed through `usePress` onto the cell batch under the cell's
 *    family, with the chip's gestures and ring.
 *  - ***+ Effect …*** opens the picker's effects arm ([ApplyPicker], `library="fx"`) on this
 *    property: `FxPicker` on the family of the cell and the bare effect press, `POST
 *    /programmer/fx` with the same pair, so a Pulse on a uv cell is masked as Colour (the
 *    family the desk reads off the driven property) — on the programmer page asking *Replace · Stack*
 *    where a Local effect of that family already runs on these heads. Under a focused Look it starts
 *    on `/fx/add` and is absorbed into the Look instead.
 *
 * **In the cue editor** (apply-model plan §3.6, session 8) the band is about the cue, not the stage:
 * it lists the cue's **stored** effects on this property that reach these heads, drawn and not
 * opened — the FX tab edits them, and no live editor or split is offered — its effect palettes go on
 * as cue layers, and *+ Effect …* stores a bare effect in the cue in draft, running nothing.
 */
export function FxBand({
  projectId,
  family,
  attribute,
  targets,
  families,
  scope,
  className,
}: {
  projectId: number
  family: AttributeFamily
  attribute: string
  /** Where a press lands — fixtures by key, a cell by its element key. */
  targets: readonly PaletteTarget[]
  /** The press's mask. */
  families: readonly AttributeFamily[] | null
  /** `usePress`'s scope — the busk tabs pass `'programmer'`. */
  scope?: 'window' | 'programmer'
  className?: string
}) {
  const { data: templates } = usePaletteListQuery({ projectId })
  const { data: library } = useFxLibraryQuery()
  const { data: active } = useActiveFxQuery()
  const { data: applied } = useProgrammerAppliedQuery()
  const { fixtures } = useFixtureLookup()
  const pressCue = usePressCue()
  const stopFx = useStopFx()
  const pair = useMemo<PressPair>(() => ({ targets, families }), [targets, families])
  const pressOptions = useMemo(() => (scope == null ? undefined : { scope }), [scope])
  const { press, into } = usePress(projectId, pair, pressOptions)
  const cue = into.kind === 'cue' ? pressCue : null
  const onGesture = useCallback(
    (template: PaletteSummary, gesture: PressGesture) => void press({ kind: 'palette', palette: template }, gesture),
    [press],
  )
  const fit = useMemo<PaletteFit>(() => ({ families: [family], emitters: null, attribute }), [family, attribute])
  const context = useMemo(() => ({ library, templates }), [library, templates])
  const offered = useMemo(
    () => recentFirst((templates ?? []).filter((t) => t.kind === 'fx' && paletteFits(t, fit, context))),
    [templates, fit, context],
  )
  const running = useMemo(
    () =>
      cue != null
        ? []
        : (active ?? []).filter(
            (e) => !e.leaving && sameAttribute(e.attribute, attribute) && fxReaches(e, targets, fixtures ?? []),
          ),
    [cue, active, attribute, targets, fixtures],
  )
  // An effect stored with no property of its own is the desk's to resolve per head, so it is listed
  // under its family — on any of the family's cells it reaches — rather than guessed at here.
  const stored = useMemo(() => {
    if (cue == null) return []
    return cue.effects.filter((e) =>
      e.attribute != null
        ? sameAttribute(e.attribute, attribute) && storedFxReaches(e, targets, fixtures ?? [])
        : attributeFamilyForFxFamily(e.family) === family && storedFxReaches({ ...e, attribute: attribute }, targets, fixtures ?? []),
    )
  }, [cue, family, attribute, targets, fixtures])

  const [editingId, setEditingId] = useState<number | null>(null)
  const editAnchor = useRef<HTMLElement | null>(null)
  const editing = editingId != null ? (active ?? []).find((e) => e.id === editingId) ?? null : null
  const [pickerOpen, setPickerOpen] = useState(false)
  const [pickerMounted, setPickerMounted] = useState(false)
  const addRef = useRef<HTMLButtonElement>(null)
  const label = FAMILY_LABELS[family].singular

  return (
    <div data-effects-band={family} className={cn('space-y-1.5', className)}>
      <div className="flex min-w-0 items-center gap-1.5">
        <EditorLabel className="shrink-0">FX</EditorLabel>
        {into.kind === 'look' && (
          <span className="min-w-0 truncate text-[10px] text-teal-700 dark:text-teal-300">into {into.lookName}</span>
        )}
        {into.kind === 'cue' && (
          <span className="min-w-0 truncate text-[10px] text-sky-700 dark:text-sky-300">in {into.cueLabel}</span>
        )}
        <Button
          ref={addRef}
          type="button"
          variant="ghost"
          size="sm"
          className="ml-auto h-6 shrink-0 gap-1 px-1.5 text-[11px]"
          aria-expanded={pickerOpen}
          disabled={targets.length === 0}
          title={
            cue != null
              ? `A library FX on these heads — stored in ${cue.cueLabel}, nothing runs`
              : `A library FX on these heads — Local, taking them off the ${label.toLowerCase()} FX they run`
          }
          onClick={() => {
            setPickerMounted(true)
            setPickerOpen((wasOpen) => !wasOpen)
          }}
        >
          <Plus className="size-3" />
          FX…
        </Button>
      </div>
      {running.length > 0 && (
        <div data-effects-band-running className="flex flex-wrap gap-1.5">
          {running.map((effect) => {
            const cue = isCueFx(effect)
            return (
              <button
                key={effect.id}
                type="button"
                data-band-effect={effect.id}
                disabled={cue}
                title={
                  cue
                    ? `${effect.fxType} — its cue’s; edit it in the cue`
                    : `${effect.fxType} — running on these heads; click to edit`
                }
                onClick={(event) => {
                  editAnchor.current = event.currentTarget
                  setEditingId(effect.id)
                }}
                className={cn(
                  'inline-flex h-7 items-center gap-1.5 rounded-md border border-violet-500/55 bg-violet-500/[0.08] px-2 text-xs hover:bg-violet-500/15 disabled:opacity-60',
                  !effect.isRunning && 'opacity-60',
                )}
              >
                <AudioWaveform className="size-3 shrink-0 text-violet-600 dark:text-violet-300" />
                <span className="max-w-[9rem] truncate">{effect.fxType}</span>
              </button>
            )
          })}
        </div>
      )}
      {stored.length > 0 && (
        <div data-effects-band-stored className="flex flex-wrap gap-1.5">
          {stored.map((effect, index) => (
            <span
              key={effect.uuid ?? `${effect.fxType}-${index}`}
              data-band-stored-effect
              title={`${effect.fxType} — stored in ${cue?.cueLabel ?? 'this cue'}; edit it in the FX tab`}
              className="inline-flex h-7 items-center gap-1.5 rounded-md border border-sky-500/50 bg-sky-500/[0.08] px-2 text-xs"
            >
              <AudioWaveform className="size-3 shrink-0 text-sky-600 dark:text-sky-300" />
              <span className="max-w-[9rem] truncate">{effect.fxType}</span>
            </span>
          ))}
        </div>
      )}
      {offered.length > 0 && (
        <div className="flex max-h-[62px] flex-wrap gap-1.5 overflow-hidden">
          {offered.map((template) => {
            const presence = pressPresence(into, cue, applied, targets, 'PALETTE', template.id)
            return (
              <PalettePressButton
                key={template.id}
                projectId={projectId}
                palette={template}
                into={into}
                presence={presence}
                hasTargets={targets.length > 0}
                onGesture={onGesture}
              >
                {(handlers) => (
                  <button
                    type="button"
                    data-band-effect-palette={template.id}
                    {...handlers}
                    title={palettePressTitle(template, into)}
                    aria-pressed={presence !== 'none'}
                    className={cn(
                      'inline-flex h-7 items-center gap-1.5 rounded-md border px-2 text-xs hover:bg-accent',
                      padPresenceClass(presence),
                    )}
                  >
                    <AudioWaveform className="size-3 shrink-0 text-muted-foreground" />
                    <span className={cn('max-w-[9rem] truncate', presence !== 'none' && 'text-primary')}>{template.name}</span>
                  </button>
                )}
              </PalettePressButton>
            )
          })}
        </div>
      )}
      {running.length === 0 && stored.length === 0 && offered.length === 0 && (
        <p className="text-[10px] text-muted-foreground">
          {cue != null ? `${cue.cueLabel} runs no FX on these heads` : 'Nothing running on these heads'}
        </p>
      )}
      {editing != null && (
        <FxEditorPopover
          effect={editing}
          anchorRef={editAnchor}
          selection={targets}
          onClose={() => setEditingId(null)}
          onFollow={setEditingId}
          onStop={(effect) => {
            stopFx(effect)
            setEditingId(null)
          }}
        />
      )}
      {pickerMounted && (
        <ApplyPicker
          projectId={projectId}
          open={pickerOpen}
          onOpenChange={setPickerOpen}
          anchorRef={addRef}
          library="fx"
          family={family}
          attribute={attribute}
          pair={pair}
          fit={fit}
          offerNew={false}
          scope={scope}
        />
      )}
    </div>
  )
}

/** An effect's property and a cell's are one where they name one thing — a colour is `rgbColour` or `colour`. */
function sameAttribute(effect: string, cell: string): boolean {
  if (effect === cell) return true
  const colour = (name: string) => name === 'rgbColour' || name === 'colour'
  return colour(effect) && colour(cell)
}
