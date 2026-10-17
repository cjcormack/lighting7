import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react'
import { LayoutGrid } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { templateLayerPresence } from '@/components/busking/lookPresence'
import { padPresenceClass, templateSwatch } from '@/components/busking/padFace'
import { targetEmitters, templateTargetsOf, type WriteTarget } from '@/components/fixtures-list/rowModel'
import { ApplyPicker } from '@/components/press/ApplyPicker'
import { PalettePressButton, templatePressTitle } from '@/components/press/PressGestures'
import { NewTemplateFromSelectionSheet } from '@/components/programmer/NewTemplateFromSelectionSheet'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { usePress, type PressGesture, type PressPair } from '@/hooks/usePress'
import { FAMILY_LABELS, formatFamilyList, type AttributeFamily } from '@/lib/attributeFamily'
import { paletteFits, type PaletteFit } from '@/lib/paletteOffer'
import { templatePropertyFor } from '@/lib/templateIntent'
import { recentFirst } from '@/lib/templateRecents'
import { cn } from '@/lib/utils'
import { useProgrammerAppliedQuery } from '@/store/programmer'
import { useTemplateListQuery } from '@/store/templates'
import { useVenueMode } from '@/store/venues'
import type { TemplateSummary, TemplateTarget } from '@/api/templatesApi'
import { EditorFooter } from './EditorFooter'
import { EditorLabel } from './EditorLabel'

/**
 * Can a value palette carry this property at all? Its rows are a closed vocabulary
 * (`TEMPLATE_PROPERTIES`), so a slider cell whose property is outside it — a speed, say — has no
 * Palettes band and nothing to save. A setting cell (gobo, prism) has none because `SettingCell` is
 * never wrapped in [PaletteBands]: a value palette cannot carry a slotted role (§4.3).
 */
export function paletteCarries(property: string): boolean {
  return templatePropertyFor(property) != null
}

/**
 * The per-property popup (apply-model plan §4.3, D4): the family's palettes **round** a value
 * cell's editor, in whichever of its three forms it opens.
 *
 *  - **Above**, the Palettes band ([PaletteBand]): its header names the heads the press lands on and
 *    the cell's family as the mask, then the family's value palettes that fit those heads, recent
 *    first and then by name, two rows of them, with *All …* for the family's whole picker.
 *  - **The editor**, unchanged: a drag or a typed value is a Local value, as it always was.
 *  - **Below**, the footer: *Save as palette…* from the cell's values, beside the family's own
 *    tools ([PaletteFooter]).
 *
 * Drawn only where a press has a door: the programmer passes `projectId`, and the plain lists and
 * the cue editor's Values grid pass none — a band press there would reach the programmer (R2 P2), so
 * those cells get their editor alone. A cell whose property no palette can carry has no band either.
 */
export function PaletteBands({
  projectId,
  family,
  property,
  targets,
  onSave,
  tools,
  children,
}: {
  projectId?: number
  family: AttributeFamily
  /** The cell's property — a Beam cell is offered the Beam palettes that set it. */
  property: string
  /** The cell batch's heads, as a commit lands on them. */
  targets: readonly WriteTarget[]
  /** *Save as palette…* — the host mounts the sheet, outside its popover ([usePaletteSave]). */
  onSave?: () => void
  /** The family's own tools, after the save in the footer. */
  tools?: ReactNode
  children: ReactNode
}) {
  const pressTargets = useMemo(() => templateTargetsOf(targets), [targets])
  const emitters = useMemo(() => targetEmitters(targets), [targets])
  const families = useMemo(() => [family], [family])
  if (projectId == null || !paletteCarries(property)) return <>{children}</>
  return (
    <>
      <PaletteBand
        projectId={projectId}
        family={family}
        property={property}
        targets={pressTargets}
        families={families}
        emitters={emitters}
      />
      {children}
      <PaletteFooter projectId={projectId} canSave={targets.length > 0} onSave={onSave} tools={tools} />
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
 * press goes instead (*Into Warm wash*) and the rings go, since a Look holds values.
 *
 * **The offer is the strip's** (`paletteFits`): the family, every emitter a palette names somewhere
 * in the heads — so a Shehds zone is offered the colour palettes its zone takes — and for a Beam cell
 * the palettes with a row for its property. Effect palettes are the Effects band's (session 6).
 *
 * **Two rows, never a scroller**: the chips wrap and the band clips at two rows, so how many show is
 * the panel's width to say. *All · n* opens the one picker ([ApplyPicker]) on this family.
 */
export function PaletteBand({
  projectId,
  family,
  property,
  targets,
  families,
  emitters,
  className,
}: {
  projectId: number
  family: AttributeFamily
  property?: string
  /** Where a press lands — fixtures by key, a cell by its element key. */
  targets: readonly TemplateTarget[]
  /** The press's mask. */
  families: readonly AttributeFamily[] | null
  /** The bundled emitters the heads have, for the offer. */
  emitters: readonly string[]
  className?: string
}) {
  const { data: templates } = useTemplateListQuery({ projectId })
  const { data: applied } = useProgrammerAppliedQuery()
  const pair = useMemo<PressPair>(() => ({ targets, families }), [targets, families])
  const { press, into } = usePress(projectId, pair)
  const onGesture = useCallback(
    (template: TemplateSummary, gesture: PressGesture) => void press({ kind: 'template', template }, gesture),
    [press],
  )
  const fit = useMemo<PaletteFit>(() => ({ families: [family], emitters, property }), [family, emitters, property])
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
      {into.kind === 'look' && (
        <div
          data-palette-band-into
          className="rounded border border-teal-600/30 bg-teal-500/10 px-2 py-1 text-[11px] text-teal-700 dark:text-teal-300"
        >
          Into {into.lookName}
        </div>
      )}
      {offered.length === 0 ? (
        <p className="text-[10px] text-muted-foreground">No {label.toLowerCase()} palette fits these heads</p>
      ) : (
        // Two rows of 28px chips and the gap between them; the rest are a press on *All* away.
        <div className="flex max-h-[62px] flex-wrap gap-1.5 overflow-hidden">
          {offered.map((template) => {
            const presence = into.kind === 'look' ? 'none' : templateLayerPresence(applied ?? [], targets, template.id)
            const swatch = templateSwatch(template)
            return (
              <PalettePressButton
                key={template.id}
                projectId={projectId}
                template={template}
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
                    title={templatePressTitle(template, into)}
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
        />
      )}
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
function HeadsLine({ targets }: { targets: readonly TemplateTarget[] }) {
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
}: {
  projectId: number
  canSave: boolean
  onSave?: () => void
  tools?: ReactNode
}) {
  const { isVenue } = useVenueMode(projectId)
  return (
    <EditorFooter
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
  const templateTargets = useMemo(() => templateTargetsOf(targets), [targets])
  const onSave = useCallback(() => {
    setMounted(true)
    setOpen(true)
  }, [])
  if (projectId == null) return { sheet: null }
  return {
    onSave,
    sheet: mounted ? (
      <NewTemplateFromSelectionSheet
        open={open}
        onOpenChange={setOpen}
        projectId={projectId}
        families={families}
        targets={templateTargets}
      />
    ) : null,
  }
}
