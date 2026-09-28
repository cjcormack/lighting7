import { Gauge, Grid3x3, LayoutGrid, Theater, type LucideIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { usePersistentToggle } from '@/hooks/usePersistentState'

/**
 * The four collapsible panels Layout hangs under the header, as data.
 *
 * They were four all-but-identical toggle components and three one-line hooks wrapping
 * `usePersistentToggle`, plus a fifth hand-written copy of the same list inside the command
 * palette — which is how the Stage panel ended up with two different icons depending on where you
 * reached it from. One array now feeds both surfaces, so a further panel is a row here.
 *
 * `label` is the palette's noun (title case, it heads a list); `noun` is the toolbar tooltip's,
 * which reads "Show …" / "Hide …". They differ per panel and always have.
 */
type OverviewPanelId = 'stage' | 'fixtures' | 'speedMasters' | 'cueSlots'

interface OverviewPanelDescriptor {
  id: OverviewPanelId
  label: string
  noun: string
  icon: LucideIcon
  storageKey: string
}

/**
 * Declaration order is display order, left to right in the toolbar and top to bottom in the
 * palette — and Layout stacks the panels themselves in the same order, so the two agree.
 */
const DESCRIPTORS: readonly OverviewPanelDescriptor[] = [
  {
    id: 'stage',
    label: 'Stage Overview',
    noun: 'stage',
    // lucide `Theater`, which is what the command palette always used. The toolbar had a
    // hand-rolled stage-and-spotlight SVG; two glyphs for one panel is worse than either.
    icon: Theater,
    storageKey: 'stage-overview-visible',
  },
  {
    id: 'fixtures',
    label: 'Fixture Overview',
    noun: 'fixture overview',
    icon: LayoutGrid,
    storageKey: 'fixture-overview-visible',
  },
  {
    id: 'speedMasters',
    // "… Overview", like Stage and Fixture, because the palette's Navigation section already has
    // a **Speed Masters** — the `/speed-masters` page. Two identically-labelled rows, one that
    // navigates and one that toggles a panel, is the ambiguity the other two panels' names were
    // already avoiding; Cue Slots needs no suffix because nothing else is called that.
    label: 'Speed Master Overview',
    noun: 'speed masters',
    // lucide `Gauge`. Not `Settings2` or `SlidersHorizontal`, both of which already mean "manage
    // the bank" a row or two away inside `SpeedMasters` itself; two identical glyphs for a summon
    // and a destination read as one thing.
    icon: Gauge,
    storageKey: 'speed-master-overview-visible',
  },
  {
    id: 'cueSlots',
    label: 'Cue Slots',
    noun: 'FX cue slots',
    icon: Grid3x3,
    storageKey: 'cue-slot-overview-visible',
  },
]

const BY_ID = Object.fromEntries(DESCRIPTORS.map((d) => [d.id, d])) as Record<
  OverviewPanelId,
  OverviewPanelDescriptor
>

/** One panel's visibility. */
interface PanelVisibility {
  isVisible: boolean
  toggle: () => void
}

/** One panel's live state, ready for the toolbar, the palette and the panel itself. */
export interface OverviewPanel extends OverviewPanelDescriptor {
  isVisible: boolean
  toggle: () => void
}

/**
 * Visibility for all four panels.
 *
 * There was a different fourth once, Effects Overview: a beat dot, master 1's bpm, a TAP, a
 * running-effect count and a Kill All. The ShowBar carries the tempo half on the live views that
 * have a bar — Show and the Prompt Book, the whole speed-master bank with a beat dot and TAP per
 * tile; Busk has the same bank in its side sheet's Speed tab and the tempo chip on its Show tab's
 * strip — so the panel was a second, narrower answer to "what tempo is the desk at", and one
 * that only ever spoke for master 1. It went, and the count and Kill All went with it rather than
 * moving: the programmer's FX band and the busk view's presence rings say what is running, and
 * each lists the effects individually so they can be removed by name. If a "stop everything"
 * gesture is wanted again it belongs beside blackout in the bar, not in a panel the operator has
 * to open first.
 *
 * **Speed Masters is not that panel coming back, and the difference is the whole of why it is
 * allowed** (`PD-SPEED-OVERLAY`). Effects Overview drew a tempo readout *of its own*, narrower
 * than the bar's; `SpeedMasterOverviewPanel` mounts `components/SpeedMasters.tsx` — the bar's own
 * component, every master, unchanged — in a wider box. So it cannot be a second answer to the
 * question the bar answers: it is the same answer, reachable from a view that has no bar. The
 * count and Kill All did not come back with it. The programmer still draws no bar, and this is
 * not chrome it gained: the panel hangs under the app header on every route, behind a toggle.
 *
 * The hooks are written out rather than mapped over `DESCRIPTORS`, because they are hooks and
 * their order and count have to be statically obvious. They are then paired with their descriptors
 * **by id**, through a `Record` the compiler makes exhaustive — never by array index, which would
 * mean reordering `DESCRIPTORS` silently put one panel's icon and label on another's storage key
 * and visibility. That is the same class of mismatch this module was written to end.
 */
export function useOverviewPanels(): {
  panels: readonly OverviewPanel[]
  byId: Record<OverviewPanelId, OverviewPanel>
} {
  const stage = usePersistentToggle(BY_ID.stage.storageKey)
  const fixtures = usePersistentToggle(BY_ID.fixtures.storageKey)
  const speedMasters = usePersistentToggle(BY_ID.speedMasters.storageKey)
  const cueSlots = usePersistentToggle(BY_ID.cueSlots.storageKey)

  const visibility: Record<OverviewPanelId, PanelVisibility> = {
    stage: { isVisible: stage.isVisible, toggle: stage.toggle },
    fixtures: { isVisible: fixtures.isVisible, toggle: fixtures.toggle },
    speedMasters: { isVisible: speedMasters.isVisible, toggle: speedMasters.toggle },
    cueSlots: { isVisible: cueSlots.isVisible, toggle: cueSlots.toggle },
  }

  const panels = DESCRIPTORS.map((d) => ({ ...d, ...visibility[d.id] }))

  return {
    panels,
    byId: Object.fromEntries(panels.map((p) => [p.id, p])) as Record<
      OverviewPanelId,
      OverviewPanel
    >,
  }
}

/** The header button for one overview panel. */
export function OverviewToggle({ panel }: { panel: OverviewPanel }) {
  const { icon: Icon, noun, isVisible, toggle } = panel
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          onClick={toggle}
          className={
            isVisible
              ? 'text-primary-foreground bg-primary-foreground/20 hover:bg-primary-foreground/30'
              : 'text-primary-foreground hover:bg-primary-foreground/10'
          }
        >
          <Icon className="size-5" />
        </Button>
      </TooltipTrigger>
      <TooltipContent>{`${isVisible ? 'Hide' : 'Show'} ${noun}`}</TooltipContent>
    </Tooltip>
  )
}
