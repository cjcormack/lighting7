import { memo, useMemo, useState, type ReactNode } from "react"
import { useDraggable, useDndMonitor } from "@dnd-kit/core"
import { GripVertical, Search } from "lucide-react"
import { cn } from "@/lib/utils"
import { Input } from "@/components/ui/input"
import { registerDragOverlay } from "@/components/dnd/dragOverlayRegistry"
import { EditorLabel } from "@/components/editor/EditorLabel"
import {
  ATTRIBUTE_FAMILIES,
  FAMILY_LABELS,
  familyForCategory,
  type AttributeFamily,
} from "@/lib/attributeFamily"
import {
  COLOUR_AXES,
  COLOUR_AXIS_LABELS,
  COLOUR_AXIS_SWATCHES,
  withAxis,
  type ColourAxis,
} from "@/lib/colourAxis"
import { useVisibleFixtureListQuery } from "@/store/fixtures"
import { useGroupListQuery } from "@/store/groups"
import { useProjectCueStackListQuery } from "@/store/cueStacks"
import { useLookListQuery } from "@/store/looks"
import { useTemplateListQuery } from "@/store/templates"
import { useBuskPagesQuery } from "@/store/busk"
import { useDeskWindows } from "@/store/windows"
import { BUSK_FOCUSES } from "@/lib/buskWindow"
import { SUBSELECT_MODE_LABELS } from "@/lib/cellsSubSelection"
import { FOCUS_LABELS } from "./targetUtils"
import { allBanks, allPads } from "@/lib/buskLayout"
import { padFaceOf, templateSwatch } from "@/components/busking/padFace"
import { useRigProperties, useTargetProperties, type AvailableProperty } from "@/hooks/useTargetProperties"
import { surfaceDragData, type SurfaceDragData } from "@/lib/surfaceDrop"
import type { BindingTarget, BankDefinition } from "@/store/surfaces"
import type { CueTarget } from "@/api/cuesApi"

/**
 * The binding library, as a palette rather than a picker (D9).
 *
 * It takes the inspector's slot while *Edit bindings* is on, and every row is a thing waiting to
 * be dragged onto the picture: **a row lands on a strip, a chip lands on one control**. The rule
 * itself lives in `lib/surfaceDrop.ts`, which is also where the drop's binding request is decided;
 * this file is the rows, the chips and the dimming.
 *
 * **Its dnd is the app's one `DndContext`** (`dnd/DeskDndProvider.tsx`), joined here with
 * `useDndMonitor` and never nested, for the busk page's reason. Foreign drags are ignored by id on
 * both sides.
 *
 * **The list is sectioned by kind, and Desk leads.** One sticky `EditorLabel` per kind that has rows,
 * in the order of the kind row — `Desk · Groups · Fixtures · Looks · Cues` — so the whole library
 * reads as five short lists rather than one long one. Desk (Selection, Encoder bank, the busk
 * pages, the whole-rig row) moved to the front of both the sections and the segmented control
 * because on a real rig it sat under every fixture in the patch, and the Selection row is the one
 * a selection-driven desk reaches for first. Under a kind filter the one section shows without its
 * heading; the control already says it.
 *
 * **The kind row stays at the artboard's six** — All · Desk · Groups · Fixtures · Looks · Cues —
 * so the two record kinds session 4 added fold into it rather than widening a 360px segmented
 * control to seven: a **template** files under *Looks*, the row of named recallable records, and a
 * **busk page** under *Desk*, which already holds the encoder bank.
 *
 * **Chips are grouped by family inside a row**, with a hairline between groups — intensity,
 * position, colour, beam, then the actions — `TemplateStrip`'s split, sideways. It is what keeps a
 * fixture row legible now that a colour is **four chips** (hue, hue fine, sat, bright: one per
 * `ColourAxis`, each carrying its axis on the target and its own swatch) and the bundled emitters
 * (white, amber, UV) sit beside them as faders of their own. `groupChipsByFamily` is the pure half.
 *
 * *Next page* / *Prev page* live on the Desk row and not on each page's row. `Edit.dc.html` draws
 * them on its single *Busk · Verse* row and so cannot distinguish "on this page's row" from "on
 * every page's row"; a project with ten pages would repeat two identical chips ten times, and a
 * chip repeated per page reads as page-*specific*, which is the one thing those two are not.
 *
 * **The busk-further plan's five (D14) split the same way.** *Focus · Split / Pads / Rig* and
 * *Sheet* are per **window** — a row per registry name under *Desk*, from `useDeskWindows`, because
 * a binding names a window by that name and a chip per window reads as window-specific, which it
 * is; a duplicated name (two screens announcing *Screen 2*) is one row, since the desk sends the
 * command to every row of the name. *Next · Prev · Odd · Even · Masters* sit on the Desk row
 * **once**: they rewrite the one desk selection and name no window. The kind row stays at six.
 */

/** The `Strip` drag handle's label, lowercased — searchable, but not a chip. */
const STRIP_HANDLE_LABEL = "strip"

type KindFilter = "all" | "desk" | "group" | "fixture" | "look" | "cue"
type FamilyFilter = "any" | AttributeFamily

/** Insertion order is the segmented control's order and the sections' order. */
const KIND_LABELS: Record<KindFilter, string> = {
  all: "All",
  desk: "Desk",
  group: "Groups",
  fixture: "Fixtures",
  look: "Looks",
  cue: "Cues",
}

/** The sections, in kind-row order. */
const SECTION_KINDS = (Object.keys(KIND_LABELS) as KindFilter[]).filter((k) => k !== "all")

interface LibraryChip {
  key: string
  label: string
  target: BindingTarget
  swatch: string | null
  /** Which family filter shows this chip. Null means "every family" — an action, not an attribute. */
  family: AttributeFamily | null
}

interface LibraryRow {
  key: string
  name: string
  detail: string
  badge: string
  kind: KindFilter
  /** Present on a group or fixture row: the whole row is draggable, and lands on a strip. */
  strip: { target: CueTarget } | null
  chips: LibraryChip[]
}

/**
 * The chips one property offers. A slider is one chip; a **colour is four**, one per axis, each
 * with its axis on the target (absent for hue — `withAxis`) and its own swatch. The label is the
 * axis alone when the target has one colour property, which is nearly every head; a second colour
 * property gets its name in front so the two rows of axes can be told apart.
 */
function propertyChips(
  keyPrefix: string,
  property: AvailableProperty,
  targetFor: (propertyName: string) => BindingTarget & { colourAxis?: ColourAxis | null },
  soleColour: boolean,
): LibraryChip[] {
  const family = familyForCategory(property.category)
  if (property.type !== "colour") {
    return [
      {
        key: `${keyPrefix}:${property.name}`,
        label: property.displayName,
        target: targetFor(property.name),
        swatch: null,
        family,
      },
    ]
  }
  return COLOUR_AXES.map((axis) => ({
    key: `${keyPrefix}:${property.name}:${axis}`,
    label: soleColour
      ? COLOUR_AXIS_LABELS[axis]
      : `${property.displayName} ${COLOUR_AXIS_LABELS[axis]}`,
    target: withAxis(targetFor(property.name), axis),
    swatch: COLOUR_AXIS_SWATCHES[axis],
    family,
  }))
}

/** Every chip a list of properties offers — the one place the "sole colour" rule is decided. */
function chipsForProperties(
  keyPrefix: string,
  properties: readonly AvailableProperty[],
  targetFor: (propertyName: string) => BindingTarget & { colourAxis?: ColourAxis | null },
): LibraryChip[] {
  const continuous = properties.filter((property) => property.continuous)
  const soleColour = continuous.filter((property) => property.type === "colour").length === 1
  return continuous.flatMap((property) => propertyChips(keyPrefix, property, targetFor, soleColour))
}

/**
 * A chip that is an *action* rather than an attribute — Go, Fire, Clear, Blackout, a bank.
 *
 * `family: null` is the load-bearing field and the reason this is a helper rather than a dozen
 * object literals: a chip filed under a family it does not belong to simply disappears when that
 * filter is off, with nothing to see and no compiler help. Naming the case once means it cannot be
 * mistyped at the thirteenth call site.
 */
function actionChip(key: string, label: string, target: BindingTarget): LibraryChip {
  return { key, label, target, swatch: null, family: null }
}

/**
 * A row's chips in family order — intensity, position, colour, beam — with the actions
 * (`family: null`) last, empty groups dropped. Pure, so the hairline rule can be tested without a
 * render: a hairline is drawn between neighbours, so there are `groups.length − 1` of them.
 */
export function groupChipsByFamily(chips: readonly LibraryChip[]): LibraryChip[][] {
  const groups: LibraryChip[][] = []
  for (const family of ATTRIBUTE_FAMILIES) {
    const group = chips.filter((chip) => chip.family === family)
    if (group.length > 0) groups.push(group)
  }
  const actions = chips.filter((chip) => chip.family === null)
  if (actions.length > 0) groups.push(actions)
  return groups
}

// ─── Drag sources ─────────────────────────────────────────────────────

function DragHandle({
  id,
  data,
  label,
  className,
  children,
}: {
  id: string
  data: SurfaceDragData
  label: string
  className?: string
  children: ReactNode
}) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id, data })
  return (
    <button
      type="button"
      ref={setNodeRef}
      {...attributes}
      {...listeners}
      aria-label={label}
      className={cn("cursor-grab touch-none", isDragging && "opacity-35", className)}
    >
      {children}
    </button>
  )
}

function ChipButton({
  id,
  chip,
  dimmed,
}: {
  id: string
  chip: LibraryChip
  /** A drag is in flight and this chip is not it — the palette recedes so the picture reads. */
  dimmed: boolean
}) {
  return (
    <DragHandle
      id={id}
      data={{ type: "surface-chip", target: chip.target, label: chip.label, swatch: chip.swatch }}
      label={`Bind ${chip.label}`}
      className={cn(
        "inline-flex h-[22px] items-center gap-1 rounded-md border bg-card px-2 text-[11px] leading-3",
        dimmed && "opacity-40",
      )}
    >
      {chip.swatch && (
        <span
          aria-hidden
          className="size-2.5 shrink-0 rounded-[2px]"
          style={{ background: chip.swatch }}
        />
      )}
      {chip.label}
    </DragHandle>
  )
}

/** `TemplateStrip`'s hairline, verbatim: drawn between two chip groups, never at an end. */
function Hairline() {
  return <span aria-hidden data-testid="chip-hairline" className="mx-0.5 h-5 w-px shrink-0 bg-border" />
}

function LibraryRowItem({
  row,
  onSurface,
  dragging,
}: {
  row: LibraryRow
  /** "strip 3" / "on 2 controls" — where this row already is. */
  onSurface: string | null
  dragging: boolean
}) {
  const groups = groupChipsByFamily(row.chips)
  return (
    <div
      data-testid={`library-row:${row.key}`}
      className="flex items-start gap-2.5 border-t px-2.5 py-1.5 text-[13px] first:border-t-0"
    >
      {row.strip ? (
        <DragHandle
          id={`surface-row:${row.key}`}
          data={{
            type: "surface-row",
            target: row.strip.target,
            name: row.name,
            detail: row.detail,
          }}
          label={`Place ${row.name} on a strip`}
          className="mt-0.5 shrink-0 text-muted-foreground"
        >
          <GripVertical className="size-3.5" />
        </DragHandle>
      ) : (
        <span className="mt-0.5 w-3.5 shrink-0" aria-hidden />
      )}
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <div className="flex items-center gap-2">
          <span className="truncate font-medium">{row.name}</span>
          <span className="truncate text-[11px] text-muted-foreground">{row.detail}</span>
          <span className="flex-1" />
          {onSurface && (
            <span className="inline-flex h-4 shrink-0 items-center rounded-full bg-muted px-1.5 text-[10px]">
              {onSurface}
            </span>
          )}
          <span className="inline-flex h-4 shrink-0 items-center rounded-full border px-1.5 text-[10px]">
            {row.badge}
          </span>
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          {row.strip && (
            <DragHandle
              id={`surface-strip-chip:${row.key}`}
              data={{
                type: "surface-row",
                target: row.strip.target,
                name: row.name,
                detail: row.detail,
              }}
              label={`Place ${row.name} on a strip`}
              className={cn(
                "inline-flex h-[22px] items-center rounded-md border px-2 text-[11px] font-semibold leading-3",
                "border-primary/60 text-primary",
                dragging && "opacity-40",
              )}
            >
              Strip
            </DragHandle>
          )}
          {groups.map((group, i) => (
            <span key={group[0].key} className="contents">
              {i > 0 && <Hairline />}
              {group.map((chip) => (
                <ChipButton
                  key={chip.key}
                  id={`surface-chip:${row.key}:${chip.key}`}
                  chip={chip}
                  dimmed={dragging}
                />
              ))}
            </span>
          ))}
        </div>
      </div>
    </div>
  )
}

// ─── Per-target rows ──────────────────────────────────────────────────

/**
 * A group's or fixture's chips need that target's properties, and `useTargetProperties` is a hook —
 * so the row is a component. One query per group row, which is what `/groups` already does with a
 * card per group; a fixture row costs nothing extra, the whole patch being one request.
 *
 * **It takes the target as two primitives and mints the object here**, and it is memoized. Both
 * halves are needed and neither works alone: a `{ type, key }` literal built in the caller's `.map`
 * is a fresh identity every render, which defeats `useTargetProperties`' own memo *and* this
 * component's — so a keystroke in the search box would re-run the property map and sort for every
 * visible row on a rig with hundreds of fixtures.
 *
 * The search is finished here too: the caller can match a name, but only this component knows the
 * row's chips, and `hue` should find every head that has one.
 */
const TargetRowItem = memo(function TargetRowItem({
  targetType,
  targetKey,
  name,
  detail,
  badge,
  kind,
  family,
  needle,
  onSurface,
  dragging,
}: {
  targetType: CueTarget["type"]
  targetKey: string
  name: string
  detail: string
  badge: string
  kind: KindFilter
  family: FamilyFilter
  needle: string
  onSurface: string | null
  dragging: boolean
}) {
  const target = useMemo<CueTarget>(
    () => ({ type: targetType, key: targetKey }),
    [targetType, targetKey],
  )
  const { properties } = useTargetProperties(target)

  const row = useMemo<LibraryRow>(() => {
    const chips = chipsForProperties(`${target.type}:${target.key}`, properties, (propertyName) =>
      target.type === "group"
        ? { type: "groupProperty", groupName: target.key, propertyName }
        : { type: "fixtureProperty", fixtureKey: target.key, propertyName },
    )
    // The strip's own select role, offered on its own so a select button can be put anywhere.
    chips.push(actionChip("select", "select", { type: "selectTarget", target, mode: "toggle" }))
    return {
      key: `${target.type}:${target.key}`,
      name,
      detail,
      badge,
      kind,
      strip: { target },
      chips,
    }
  }, [properties, target, name, detail, badge, kind])

  const shown = useMemo(() => filterChips(row, family), [row, family])
  if (shown == null || !rowMatches(shown, needle)) return null
  return <LibraryRowItem row={shown} onSurface={onSurface} dragging={dragging} />
})

/**
 * A family filter hides *chips*, and a row with nothing left with it.
 *
 * The busk palette's family row partitions its records; here the records are targets and a target
 * has every family its heads have, so the only thing a family can usefully select is which
 * attributes to bind. A row's `Strip` handle is a whole-strip gesture covering every family, so it
 * survives the filter — and keeps its row on screen.
 */
function filterChips(row: LibraryRow, family: FamilyFilter): LibraryRow | null {
  if (family === "any") return row
  const chips = row.chips.filter((chip) => chip.family === null || chip.family === family)
  if (chips.length === 0 && row.strip == null) return null
  return { ...row, chips }
}

/** The search matches a row's name **or any of its chip labels**, so `sat` finds every colour head. */
function rowMatches(row: LibraryRow, needle: string): boolean {
  if (needle.length === 0) return true
  if (row.name.toLowerCase().includes(needle)) return true
  // The `Strip` handle is a row affordance rather than a chip, but the palette predicts against a
  // vocabulary that offers it, so a search for `strip` has to be answerable here too — otherwise
  // every target row is kept as a candidate and then answers null, and the empty state never shows.
  if (row.strip != null && STRIP_HANDLE_LABEL.includes(needle)) return true
  return row.chips.some((chip) => chip.label.toLowerCase().includes(needle))
}

// ─── The palette ──────────────────────────────────────────────────────

export interface SurfaceLibraryProps {
  projectId: number
  /** The selected device's banks, for the Desk row's *Bank A / B* chips. */
  banks: readonly BankDefinition[]
  deviceTypeKey: string
  /**
   * `type:key` → where that record already sits on the bank being drawn: `strip 3`, `on 2 controls`.
   * Built by `describePlacements` in `routes/Surfaces.tsx`.
   */
  placements: ReadonlyMap<string, string>
}

export function SurfaceLibrary({
  projectId,
  banks,
  deviceTypeKey,
  placements,
}: SurfaceLibraryProps) {
  const { data: groups } = useGroupListQuery()
  const { data: fixtures } = useVisibleFixtureListQuery()
  const { data: stacks } = useProjectCueStackListQuery(projectId)
  const { data: looks } = useLookListQuery({ projectId })
  const { data: templates } = useTemplateListQuery({ projectId })
  const { data: pages } = useBuskPagesQuery(projectId)
  const windows = useDeskWindows()
  const rigProperties = useRigProperties()
  const [search, setSearch] = useState("")
  const [kind, setKind] = useState<KindFilter>("all")
  const [family, setFamily] = useState<FamilyFilter>("any")
  const dragging = useSurfaceDragging()

  /** The rows that are not one target's — Selection, Encoder bank, stacks, cues, Desk. */
  const rows = useMemo<LibraryRow[]>(() => {
    const out: LibraryRow[] = []

    out.push({
      key: "selection",
      name: "Selection",
      detail: "whatever is selected",
      badge: "Selection",
      kind: "desk",
      strip: null,
      chips: [
        ...chipsForProperties("sel", rigProperties, (propertyName) => ({
          type: "selectionProperty",
          propertyName,
        })),
        actionChip("clear", "Clear", { type: "clearSelection" }),
        actionChip("locate", "Locate", { type: "locateSelection" }),
      ],
    })

    out.push({
      key: "encoder-bank",
      name: "Encoder bank",
      detail: "strip encoders",
      badge: "Desk",
      kind: "desk",
      strip: null,
      chips: chipsForProperties("bank", rigProperties, (propertyName) => ({
        type: "encoderBankSet",
        propertyName,
      })),
    })

    for (const stack of stacks ?? []) {
      if (stack.type !== "STACK") continue
      out.push({
        key: `stack:${stack.id}`,
        name: stack.name,
        detail: `${stack.cues.length} ${stack.cues.length === 1 ? "cue" : "cues"}`,
        badge: "Stack",
        kind: "cue",
        strip: null,
        chips: [
          actionChip("go", "Go", { type: "cueStackGo", stackId: stack.id }),
          actionChip("back", "Back", { type: "cueStackBack", stackId: stack.id }),
          actionChip("pause", "Pause", { type: "cueStackPause", stackId: stack.id }),
        ],
      })
      for (const cue of stack.cues) {
        // A MARKER cannot be fired, so a binding on one would be dead on arrival.
        if (cue.cueType === "MARKER") continue
        out.push({
          key: `cue:${cue.id}`,
          name: cue.name,
          detail: `${cue.cueNumber} · ${stack.name}`,
          badge: "Cue",
          kind: "cue",
          strip: null,
          chips: [
            actionChip("fire", "Fire", { type: "fireCue", cueId: cue.id }),
          ],
        })
      }
    }

    // Records on buttons (D6). Each is a **plain row with chips** rather than a `TargetRowItem`:
    // that component exists to mount `useTargetProperties` per group or fixture and is memoized on
    // primitive target props for exactly that reason, and none of these has a per-target property
    // lookup to do.
    for (const template of templates ?? []) {
      out.push({
        key: `template:${template.uuid}`,
        name: template.name,
        detail: `${template.family?.toLowerCase() ?? "value"} template`,
        badge: "Template",
        kind: "look",
        strip: null,
        chips: [
          // A template is exactly one family (never `null` — the write boundary validates that),
          // unlike a Look's Apply chip below, which stays family-agnostic because a Look spans
          // families by nature. `actionChip` would give this `family: null` and the family filter
          // would never hide it, so it's built by hand instead. The swatch is the busk pad's — the
          // artboard drew one on this chip, and a colour template's chip should look like its pad.
          {
            key: "press",
            label: "Press",
            target: { type: "pressTemplate", templateUuid: template.uuid },
            swatch: templateSwatch(template),
            family: template.family,
          },
        ],
      })
    }

    for (const look of looks ?? []) {
      out.push({
        key: `look:${look.uuid}`,
        name: look.name,
        detail: look.hasDeferredEffects ? "needs a selection" : "bound",
        badge: "Look",
        kind: "look",
        strip: null,
        // A Look with a deferred effect presses onto targets it does not have, and the write
        // boundary refuses it by name — so it is offered with no chip at all rather than with one
        // that 400s. The detail line above is what says why.
        chips: look.hasDeferredEffects
          ? []
          : [actionChip("apply", "Apply", { type: "applyLook", lookUuid: look.uuid })],
      })
    }

    for (const page of pages ?? []) {
      // A pad this client minted and has not saved has no uuid to bind to. It cannot occur in a
      // fetched page, but the type allows it and an empty uuid would save.
      const pads = allPads(page).filter((pad) => pad.uuid != null)
      out.push({
        key: `busk-page:${page.uuid}`,
        name: `Busk · ${page.name}`,
        detail: `page · ${pads.length} ${pads.length === 1 ? "pad" : "pads"}`,
        badge: "Busk",
        kind: "desk",
        strip: null,
        chips: [
          actionChip("page", "Page", { type: "buskPageSet", pageUuid: page.uuid }),
          ...pads.map((pad) =>
            actionChip(`pad:${pad.uuid}`, padFaceOf(pad).name, {
              type: "pressPad",
              padUuid: pad.uuid!,
            }),
          ),
          // The hand's place target, one per bank (multi-screen plan §3.5). Banks and not pads:
          // a `pickUpPad` chip would sit beside the `pressPad` chip for the same pad under the
          // same name — the pad's — and two chips reading `Warm Wash` that do different things is
          // worse than one route. Picking a pad up is offered in the binding picker, where the
          // kind is named before the record is chosen.
          ...allBanks(page)
            .filter((bank) => bank.uuid != null)
            .map((bank) =>
              actionChip(`bank:${bank.uuid}`, `Place in ${bank.name}`, {
                type: "handPlaceInBank",
                bankUuid: bank.uuid!,
              }),
            ),
        ],
      })
    }

    // One row per registry **name**: the desk addresses every window of a name, so two rows for
    // one name would be two chips for one press.
    for (const name of [...new Set(windows.map((w) => w.name))]) {
      out.push({
        key: `window:${name}`,
        name: `Window · ${name}`,
        detail: "busk focus and sheet",
        badge: "Desk",
        kind: "desk",
        strip: null,
        chips: [
          ...BUSK_FOCUSES.map((focus) =>
            actionChip(`focus:${focus}`, `Focus · ${FOCUS_LABELS[focus]}`, {
              type: "buskFocusSet",
              windowName: name,
              focus,
            }),
          ),
          actionChip("sheet", "Sheet", { type: "buskSheetToggle", windowName: name }),
        ],
      })
    }

    out.push({
      key: "desk",
      name: "Desk",
      detail: "whole rig",
      badge: "Desk",
      kind: "desk",
      strip: null,
      chips: [
        actionChip("blackout", "Blackout", { type: "blackout" }),
        actionChip("gm", "Grand master", { type: "grandMasterToggle" }),
        ...banks.map((bank) =>
          actionChip(`bank:${bank.id}`, `Bank ${bank.name}`, {
            type: "setBank",
            deviceTypeKey,
            bank: bank.id,
          }),
        ),
        // `null` is master 1 by the same convention as everywhere else, and master 1 always
        // exists — so these two are useful before the speed-master bank has even loaded.
        actionChip("tap", "Tap M1", { type: "speedMasterTap", masterUuid: null }),
        actionChip("bpm", "BPM", {
          type: "speedMasterBpm",
          masterUuid: null,
          minBpm: 60,
          maxBpm: 180,
        }),
        // Here, not on each page's row: they are page-agnostic, and a chip repeated once per page
        // reads as page-specific.
        actionChip("page-next", "Next page", { type: "buskPageNext" }),
        actionChip("page-prev", "Prev page", { type: "buskPagePrev" }),
        // The hand's own release. On the Desk row for the two page chips' reason: it names no
        // record and belongs to no page.
        actionChip("hand-drop", "Let go", { type: "handDrop" }),
        // The sub-selection (busk-further plan D12, D14): the rig band's two step buttons and the
        // Cells menu's three most-pressed filters, once, because they rewrite the one desk selection.
        actionChip("sel-next", SUBSELECT_MODE_LABELS.NEXT, { type: "selectionNext" }),
        actionChip("sel-prev", SUBSELECT_MODE_LABELS.PREV, { type: "selectionPrev" }),
        actionChip("sel-odd", SUBSELECT_MODE_LABELS.ODD, { type: "selectionCells", mode: "ODD" }),
        actionChip("sel-even", SUBSELECT_MODE_LABELS.EVEN, { type: "selectionCells", mode: "EVEN" }),
        actionChip("sel-masters", "Masters", { type: "selectionCells", mode: "MASTERS" }),
      ],
    })

    return out
  }, [rigProperties, stacks, banks, deviceTypeKey, looks, templates, pages, windows])

  const needle = search.trim().toLowerCase()
  const showKind = (rowKind: KindFilter) => kind === "all" || kind === rowKind

  const otherRows = rows
    .filter((row) => showKind(row.kind))
    .map((row) => filterChips(row, family))
    .filter((row): row is LibraryRow => row != null && rowMatches(row, needle))

  // A target row's chips are known only inside `TargetRowItem`, which finishes the search itself.
  // What the palette can say up front is whether the needle could match *any* chip a target row
  // offers — the rig's vocabulary plus the two chips every such row carries — so a search for
  // `hue` keeps the target rows in play and a search for `zzz` still reaches the empty state.
  const targetChipVocabulary = useMemo(() => {
    // Filtered by the family the same way `filterChips` filters a row, or the prediction disagrees
    // with the per-row decision: `Position` + `sat` would keep every row as a candidate and then
    // drop them all, leaving a blank list with the empty state suppressed.
    const labels = chipsForProperties("", rigProperties, (propertyName) => ({
      type: "selectionProperty",
      propertyName,
    }))
      .filter((chip) => family === "any" || chip.family === family)
      .map((chip) => chip.label)
    return [...labels, STRIP_HANDLE_LABEL, "select"].map((label) => label.toLowerCase())
  }, [rigProperties, family])
  const targetChipsMatch = needle.length > 0 && targetChipVocabulary.some((l) => l.includes(needle))
  const matchesName = (name: string) => needle.length === 0 || name.toLowerCase().includes(needle)
  const targetCandidate = (name: string) => matchesName(name) || targetChipsMatch

  const shownGroups = showKind("group") ? (groups ?? []).filter((g) => targetCandidate(g.name)) : []
  const shownFixtures = showKind("fixture")
    ? (fixtures ?? []).filter((f) => targetCandidate(f.name))
    : []
  const isEmpty = shownGroups.length === 0 && shownFixtures.length === 0 && otherRows.length === 0

  const sectionRows = (sectionKind: KindFilter): ReactNode[] => {
    switch (sectionKind) {
      case "group":
        return shownGroups.map((group) => (
          <TargetRowItem
            key={`group:${group.name}`}
            targetType="group"
            targetKey={group.name}
            name={group.name}
            detail={`${group.memberCount} ${group.memberCount === 1 ? "fixture" : "fixtures"}`}
            badge="Group"
            kind="group"
            family={family}
            needle={needle}
            onSurface={placements.get(`group:${group.name}`) ?? null}
            dragging={dragging}
          />
        ))
      case "fixture":
        return shownFixtures.map((fixture) => (
          <TargetRowItem
            key={`fixture:${fixture.key}`}
            targetType="fixture"
            targetKey={fixture.key}
            name={fixture.name}
            detail={fixture.model ?? fixture.typeKey}
            badge="Fixture"
            kind="fixture"
            family={family}
            needle={needle}
            onSurface={placements.get(`fixture:${fixture.key}`) ?? null}
            dragging={dragging}
          />
        ))
      default:
        return otherRows
          .filter((row) => row.kind === sectionKind)
          .map((row) => (
            <LibraryRowItem
              key={row.key}
              row={row}
              onSurface={placements.get(row.key) ?? null}
              dragging={dragging}
            />
          ))
    }
  }

  return (
    <div className="flex h-full w-full flex-col overflow-hidden">
      <div className="flex shrink-0 flex-col gap-2 border-b px-3 pt-3 pb-2">
        <div className="flex items-center gap-2">
          <EditorLabel>Library</EditorLabel>
          <span className="flex-1" />
          <span className="text-[11px] text-muted-foreground">row → a strip · chip → one control</span>
        </div>
        <div className="relative">
          <Search className="pointer-events-none absolute top-1/2 left-2 size-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search"
            aria-label="Search the binding library"
            className="h-7 pl-7 text-[13px]"
          />
        </div>
        <div className="flex items-center gap-0.5 rounded-[10px] border bg-card p-0.5">
          {(Object.keys(KIND_LABELS) as KindFilter[]).map((value) => (
            <SegButton
              key={value}
              active={kind === value}
              onClick={() => setKind(value)}
              className="flex-1"
            >
              {KIND_LABELS[value]}
            </SegButton>
          ))}
        </div>
        <div className="flex w-fit items-center gap-0.5 rounded-[10px] border bg-card p-0.5">
          <SegButton active={family === "any"} onClick={() => setFamily("any")}>
            Any family
          </SegButton>
          {ATTRIBUTE_FAMILIES.map((value) => (
            <SegButton key={value} active={family === value} onClick={() => setFamily(value)}>
              {FAMILY_LABELS[value].singular}
            </SegButton>
          ))}
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {isEmpty ? (
          <p className="p-4 text-center text-[12px] text-muted-foreground">
            Nothing here matches. Clear the search or the filters.
          </p>
        ) : (
          SECTION_KINDS.filter(showKind).map((sectionKind) => {
            const children = sectionRows(sectionKind)
            if (children.length === 0) return null
            return (
              // A target row can still answer null to the search after its chips are known, so a
              // section whose rows all did hides itself with its heading rather than standing empty.
              <section
                key={sectionKind}
                data-testid={`library-section:${sectionKind}`}
                className="[&:not(:has([data-testid^=library-row]))]:hidden"
              >
                {kind === "all" && (
                  <EditorLabel className="sticky top-0 z-10 border-b bg-background px-2.5 pt-2 pb-1">
                    {KIND_LABELS[sectionKind]}
                  </EditorLabel>
                )}
                {children}
              </section>
            )
          })
        )}
      </div>

      <div className="shrink-0 border-t px-3 py-2.5 text-[11px] leading-relaxed text-muted-foreground">
        Drop a row on a strip and its fader, select, encoder and flash follow. Any control can still
        be bound on its own.
      </div>
    </div>
  )
}

function SegButton({
  active,
  onClick,
  children,
  className,
}: {
  active: boolean
  onClick: () => void
  children: ReactNode
  className?: string
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        "rounded-lg px-2 py-1 text-xs font-semibold transition-colors",
        active ? "bg-muted text-foreground" : "text-muted-foreground hover:text-foreground",
        className,
      )}
    >
      {children}
    </button>
  )
}

/**
 * Is a surface drag in flight?
 *
 * Once for the whole palette, not per chip: it is a monitor subscription, and one per chip would
 * be a hundred re-renders at drag start. It answers only for *this* surface's drags, so a busk pad
 * lifted elsewhere leaves the palette alone.
 */
function useSurfaceDragging(): boolean {
  const [dragging, setDragging] = useState(false)
  useDndMonitor({
    onDragStart(event) {
      setDragging(surfaceDragData(event.active) != null)
    },
    onDragEnd() {
      setDragging(false)
    },
    onDragCancel() {
      setDragging(false)
    },
  })
  return dragging
}

/**
 * Registered at module scope so the app shell's single `<DragOverlay>` can draw a surface ghost
 * without its import graph ever reaching this file. A surface drag cannot happen without this
 * module being loaded.
 *
 * The two ghosts are the busk page's two, in its geometry: the bank ghost for a row (it covers a
 * whole column) and the pad ghost for a chip (it covers one control).
 */
registerDragOverlay((active) => {
  const data = surfaceDragData(active)
  if (data == null) return null
  if (data.type === "surface-row") {
    return (
      <div
        className="w-[130px] rounded-[10px] border border-primary bg-card p-2.5 opacity-90 shadow-lg"
        style={{ transform: "rotate(-2deg)" }}
      >
        <div className="truncate text-[11px] font-semibold">{data.name}</div>
        <div className="mt-1 text-[10px] text-muted-foreground">{data.detail} · strip</div>
      </div>
    )
  }
  return (
    <div
      className="inline-flex h-[22px] items-center gap-1 rounded-md border border-primary bg-card px-2 text-[11px] leading-3 opacity-90 shadow-lg"
      style={{ transform: "rotate(-2deg)" }}
    >
      {data.swatch && (
        <span
          aria-hidden
          className="size-2.5 shrink-0 rounded-[2px]"
          style={{ background: data.swatch }}
        />
      )}
      {data.label}
    </div>
  )
})
