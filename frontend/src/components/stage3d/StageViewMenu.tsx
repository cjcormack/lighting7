import { useState } from 'react'
import { Check, Cpu, Eye, Gauge, Link2, Monitor, Moon, RotateCcw, Sun, type LucideIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { cn } from '@/lib/utils'
import {
  VIS_SOURCES,
  VIS_SOURCE_HINTS,
  VIS_SOURCE_LABELS,
  isVisSource,
  type VisSource,
} from '@/hooks/useVisSource'
import type { StageViewFlags, StageViewToggle } from './useStageView'
import {
  STAGE_LABEL_MODES,
  STAGE_LABEL_MODE_LABELS,
  isStageLabelMode,
  type StageLabelMode,
} from './stageLabels'
import {
  BOX_SHADOW_CAPS,
  BOX_SHADOWS,
  GOBO_SURFACES,
  isBoxShadows,
  isGoboSurfaces,
  isHazeExtent,
  type BoxShadows,
  type GoboSurfaces,
  type HazeExtent,
  type SceneLayer,
  type SceneLayers,
} from './scene/sceneView'
import { LIGHT_BUDGETS } from './scene/lightTable'
import { isWorkLights, type WorkLights } from './scene/workLights'
import { HAZE_TIERS } from './scene/hazeGovernor'
import { isSlowReading } from './scene/frameRate'
import { useStageStats, type StageStatsStore } from './scene/stageStats'

/**
 * The Stage view's **View** popover (`stage-view-menu-design/Menu.dc.html`): **View** is what this
 * window sees, **Performance** what it costs. Each group says whose it is — *this window* or *this
 * machine* — and every value is on screen without hovering.
 */

export type StageViewMenuTab = 'view' | 'performance'

const GOBO_SURFACES_LABELS: Record<GoboSurfaces, string> = {
  all: 'Every gobo light',
  selected: 'Selected heads',
}

const BOX_SHADOWS_LABELS: Record<BoxShadows, string> = {
  all: `${BOX_SHADOW_CAPS.all} a light`,
  some: `${BOX_SHADOW_CAPS.some} a light`,
  off: 'Off',
}

/**
 * Work lights (stage-view menu plan D2, D6), with the chosen value's hint: on's is the board's copy
 * (`Menu.dc.html`), off says what the room is without them.
 */
const WORK_LIGHT_ITEMS: ReadonlyArray<{ value: WorkLights; label: string; icon: LucideIcon; hint: string }> = [
  { value: 'off', label: 'Off', icon: Moon, hint: 'The room as lit: what no beam reaches stays dark.' },
  { value: 'on', label: 'On', icon: Sun, hint: 'Lifts the dark, so unlit surfaces show their shape. Pools keep their exposure.' },
]

/** The rig's four toggles, then (on a second row) the scene's three. */
const RIG_TOGGLES: ReadonlyArray<{ key: StageViewToggle; label: string; hint: string }> = [
  { key: 'fixtures', label: 'Fixtures', hint: 'The fixtures, their beams and their pools' },
  // Not "Beam cones": off unmounts the emitters, which pack the light table every surface reads.
  { key: 'beamCones', label: 'Light', hint: 'Beams and every pool' },
  { key: 'riggings', label: 'Rigging', hint: 'Bars, trusses and booms' },
  { key: 'regions', label: 'Regions', hint: 'The playing areas' },
]

const SCENE_LAYER_ITEMS: ReadonlyArray<{ layer: SceneLayer; label: string; hint: string }> = [
  { layer: 'venue', label: 'Venue', hint: 'The room: walls, proscenium, the house' },
  { layer: 'set', label: 'Set', hint: "This show's scenery" },
  { layer: 'seating', label: 'Seating', hint: 'The seats, whichever layer they are in' },
]

const HAZE_ITEMS: ReadonlyArray<{ extent: HazeExtent; label: string; hint: string }> = [
  { extent: 'off', label: 'Off', hint: 'Only the light that lands.' },
  { extent: 'stage', label: 'Stage', hint: 'Upstage of the proscenium, or the stage edge.' },
  { extent: 'everywhere', label: 'Everywhere', hint: 'The house too.' },
]

interface StageViewMenuProps {
  flags: StageViewFlags
  setFlag: (key: StageViewToggle, value: boolean) => void
  setLabelMode: (mode: StageLabelMode) => void
  /**
   * *Test recovery*: drops the WebGL context the way Safari does under memory pressure, so the
   * paused state and *Restore* can be exercised on purpose.
   */
  onTestRecovery?: () => void
  /** Which layer of the lighting cascade the stage draws. */
  visSource: VisSource
  setVisSource: (next: VisSource) => void
  /**
   * A live line for a source whose hint alone can't say what it is showing — Next GO falls back to
   * plain output when nothing is on deck, and the operator has to be told.
   */
  sourceStatus?: Partial<Record<VisSource, string | null>>
  /** Venue, Set, Seating and how far the Haze reaches, per window. Absent where they mean nothing. */
  layers?: SceneLayers
  setLayer?: (layer: SceneLayer, on: boolean) => void
  setHaze?: (extent: HazeExtent) => void
  /** How many lights the surfaces take, per machine; absent where there are no lit surfaces. */
  lightBudget?: number
  setLightBudget?: (budget: number) => void
  /** Whose gobos land on surfaces, per machine; absent with the budget. */
  goboSurfaces?: GoboSurfaces
  setGoboSurfaces?: (mode: GoboSurfaces) => void
  /** How many boxes may shadow each light, per machine; absent with the budget. */
  boxShadows?: BoxShadows
  setBoxShadows?: (mode: BoxShadows) => void
  /** Whether this window lifts the dark (`scene/workLights.ts`), per window and announced. */
  workLights?: WorkLights
  setWorkLights?: (next: WorkLights) => void
  /** What this window's canvas is doing (`scene/stageStats.ts`), for Performance's live block. */
  stats?: StageStatsStore | null
  /** Whether this window draws the frame-rate readout over its canvas. */
  frameRateReadout?: boolean
  setFrameRateReadout?: (on: boolean) => void
  /** Controlled open state and tab, so the readout can open the popover on Performance. */
  open?: boolean
  onOpenChange?: (open: boolean) => void
  tab?: StageViewMenuTab
  onTabChange?: (tab: StageViewMenuTab) => void
}

export function StageViewMenu(props: StageViewMenuProps) {
  const [ownOpen, setOwnOpen] = useState(false)
  const [ownTab, setOwnTab] = useState<StageViewMenuTab>('view')
  const open = props.open ?? ownOpen
  const tab = props.tab ?? ownTab
  const setOpen = (next: boolean) => {
    setOwnOpen(next)
    props.onOpenChange?.(next)
  }
  const setTab = (next: string) => {
    if (next !== 'view' && next !== 'performance') return
    setOwnTab(next)
    props.onTabChange?.(next)
  }
  const sourceLabel = props.visSource === 'output' ? null : VIS_SOURCE_LABELS[props.visSource]

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          size="sm"
          variant="outline"
          aria-label={sourceLabel == null ? 'View options' : `View options · ${sourceLabel}`}
        >
          <Eye className="size-3.5 mr-1" />
          View
          {/* The one setting that makes the picture not the output is named on the button. */}
          {sourceLabel != null && (
            <span className="ml-1.5 inline-flex h-[18px] items-center rounded-full border border-primary/45 bg-primary/15 px-1.5 text-[10px] font-semibold text-primary">
              {sourceLabel}
            </span>
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent
        align="end"
        className="flex w-80 max-h-[var(--radix-popover-content-available-height)] flex-col overflow-hidden p-0"
      >
        <Tabs value={tab} onValueChange={setTab} className="min-h-0 gap-0">
          <TabsList className="mx-2 mt-2 grid h-8 w-auto grid-cols-2">
            <TabsTrigger value="view" className="text-xs">
              <Eye className="size-3" />
              View
            </TabsTrigger>
            <TabsTrigger value="performance" className="text-xs">
              <Gauge className="size-3" />
              Performance
            </TabsTrigger>
          </TabsList>
          <TabsContent value="view" className="min-h-0 overflow-y-auto">
            <ViewTab {...props} />
          </TabsContent>
          <TabsContent value="performance" className="min-h-0 overflow-y-auto">
            <PerformanceTab
              {...props}
              onTestRecovery={
                props.onTestRecovery == null
                  ? undefined
                  : () => {
                      setOpen(false)
                      props.onTestRecovery?.()
                    }
              }
            />
          </TabsContent>
        </Tabs>
      </PopoverContent>
    </Popover>
  )
}

function ViewTab({
  flags,
  setFlag,
  setLabelMode,
  visSource,
  setVisSource,
  sourceStatus,
  layers,
  setLayer,
  setHaze,
  workLights,
  setWorkLights,
}: StageViewMenuProps) {
  const status = sourceStatus?.[visSource]
  return (
    <div className="flex flex-col gap-2.5 px-2.5 pb-2.5 pt-1.5">
      <Group label="Source" scope="window" announced>
        <ToggleGroup
          type="single"
          value={visSource}
          onValueChange={(v) => {
            if (isVisSource(v)) setVisSource(v)
          }}
          aria-label="Source"
          className="grid h-auto w-full grid-cols-2 gap-1 bg-transparent p-0"
        >
          {VIS_SOURCES.map((source) => (
            <ToggleGroupItem key={source} value={source} className={OPTION_CLASS} title={VIS_SOURCE_LABELS[source]}>
              <span aria-hidden className={RADIO_CLASS} />
              <span className="truncate">{VIS_SOURCE_LABELS[source]}</span>
            </ToggleGroupItem>
          ))}
        </ToggleGroup>
        {/* Not decoration: "Output + Programmer" is identical to "Output" whenever Blind is off,
            so without the hint that option reads as broken. */}
        <Hint>{VIS_SOURCE_HINTS[visSource]}</Hint>
        {status && <p className="text-[11px] leading-snug text-foreground/80">{status}</p>}
      </Group>

      <Group label="Show" scope="window">
        <div className="flex flex-wrap gap-1">
          {RIG_TOGGLES.map(({ key, label, hint }) => (
            <ShowToggle key={key} label={label} hint={hint} on={flags[key]} onChange={(v) => setFlag(key, v)} />
          ))}
          {layers != null && setLayer != null && (
            <>
              <span aria-hidden className="h-0 basis-full" />
              {SCENE_LAYER_ITEMS.map(({ layer, label, hint }) => (
                <ShowToggle key={layer} label={label} hint={hint} on={layers[layer]} onChange={(v) => setLayer(layer, v)} />
              ))}
            </>
          )}
        </div>
      </Group>

      {layers != null && setHaze != null && (
        <Group label="Haze" scope="window">
          <Segments
            label="Haze"
            value={layers.haze}
            onChange={(v) => {
              if (isHazeExtent(v)) setHaze(v)
            }}
            items={HAZE_ITEMS.map(({ extent, label }) => ({ value: extent, label }))}
          />
          <Hint>{HAZE_ITEMS.find((h) => h.extent === layers.haze)?.hint}</Hint>
        </Group>
      )}

      <Group label="Labels" scope="window">
        <Segments
          label="Labels"
          value={flags.labels}
          onChange={(v) => {
            if (isStageLabelMode(v)) setLabelMode(v)
          }}
          items={STAGE_LABEL_MODES.map((mode) => ({ value: mode, label: STAGE_LABEL_MODE_LABELS[mode] }))}
        />
      </Group>

      {workLights != null && setWorkLights != null && (
        <Group label="Work lights" scope="window" announced>
          <Segments
            label="Work lights"
            value={workLights}
            onChange={(v) => {
              if (isWorkLights(v)) setWorkLights(v)
            }}
            items={WORK_LIGHT_ITEMS}
          />
          <Hint>{WORK_LIGHT_ITEMS.find((item) => item.value === workLights)?.hint}</Hint>
        </Group>
      )}
    </div>
  )
}

function PerformanceTab({
  layers,
  lightBudget,
  setLightBudget,
  goboSurfaces,
  setGoboSurfaces,
  boxShadows,
  setBoxShadows,
  onTestRecovery,
  stats,
  frameRateReadout,
  setFrameRateReadout,
}: StageViewMenuProps) {
  return (
    <div className="flex flex-col gap-2.5 px-2.5 pb-2.5 pt-1.5">
      {(stats != null || setFrameRateReadout != null) && (
        <Group label="This window's canvas" scope="window">
          <div className="flex flex-col gap-1.5 rounded-lg border bg-background px-2.5 py-2">
            {stats != null && <LiveBlock stats={stats} hazeOff={layers?.haze === 'off'} />}
            {setFrameRateReadout != null && (
              <div className={cn('flex items-center gap-2 text-[11px]', stats != null && 'border-t pt-1.5')}>
                <span className="flex-1">Frame rate on the canvas</span>
                <Switch
                  label="Frame rate on the canvas"
                  on={frameRateReadout === true}
                  onChange={setFrameRateReadout}
                />
              </div>
            )}
          </div>
        </Group>
      )}

      {lightBudget != null && setLightBudget != null && (
        <Group label="Light budget" scope="machine">
          {/* The surface shader's cost is pixels × lights. */}
          <Segments
            label="Light budget"
            value={String(lightBudget)}
            onChange={(v) => {
              if (v !== '') setLightBudget(Number(v))
            }}
            items={LIGHT_BUDGETS.map((budget) => ({ value: String(budget), label: String(budget) }))}
          />
          <Hint>The brightest this many light the surfaces. The rest still draw their beams.</Hint>
        </Group>
      )}

      {goboSurfaces != null && setGoboSurfaces != null && (
        <Group label="Gobos on surfaces" scope="machine">
          {/* The light budget's fallback: a gobo on every surface costs an atlas read per lit pixel
              and light; the rest keep their plain pool, and every gobo still shows in the air. */}
          <Segments
            label="Gobos on surfaces"
            value={goboSurfaces}
            onChange={(v) => {
              if (isGoboSurfaces(v)) setGoboSurfaces(v)
            }}
            items={GOBO_SURFACES.map((mode) => ({ value: mode, label: GOBO_SURFACES_LABELS[mode] }))}
          />
        </Group>
      )}

      {boxShadows != null && setBoxShadows != null && (
        <Group label="Box shadows" scope="machine">
          {/* A light that reaches more boxes than the cap keeps where its beam lands instead. */}
          <Segments
            label="Box shadows"
            value={boxShadows}
            onChange={(v) => {
              if (isBoxShadows(v)) setBoxShadows(v)
            }}
            items={BOX_SHADOWS.map((mode) => ({ value: mode, label: BOX_SHADOWS_LABELS[mode] }))}
          />
        </Group>
      )}

      {onTestRecovery && (
        <div className="flex items-center gap-2 border-t pt-2">
          <Button size="sm" variant="outline" className="h-7 shrink-0 text-xs" onClick={onTestRecovery}>
            <RotateCcw className="size-3" />
            Test recovery
          </Button>
          <Hint>Drops the 3D context the way Safari does under memory pressure.</Hint>
        </div>
      )}
    </div>
  )
}

/** fps and ms a frame, the lights packed of lit, the haze tier: what this canvas is doing now. */
function LiveBlock({ stats, hazeOff }: { stats: StageStatsStore; hazeOff: boolean }) {
  const { frameRate, lights, hazeTier } = useStageStats(stats)
  const slow = isSlowReading(frameRate)
  return (
    <>
      <div
        data-testid="stage-live-rate"
        className={cn('font-mono text-xl font-semibold tabular-nums tracking-tight', slow && 'text-amber-500 dark:text-amber-400')}
      >
        {frameRate == null ? (
          'idle'
        ) : (
          <>
            {frameRate.fps} <small className="text-xs font-medium text-muted-foreground">fps</small>
            {frameRate.ms != null && (
              <>
                {' · '}
                {frameRate.ms.toFixed(1)} <small className="text-xs font-medium text-muted-foreground">ms a frame</small>
              </>
            )}
          </>
        )}
      </div>
      <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
        <span data-testid="stage-live-lights">
          {lights == null ? (
            'No lights packed'
          ) : (
            <>
              Lights <b className="font-semibold text-foreground tabular-nums">{lights.packed}</b> on surfaces of{' '}
              <b className="font-semibold text-foreground tabular-nums">{lights.lit}</b> lit
            </>
          )}
        </span>
        <span className="flex-1" />
        <span data-testid="stage-live-haze" title="How far the haze governor has stepped the march down">
          Haze <b className="font-semibold text-foreground">{hazeOff ? 'off' : hazeTierLabel(hazeTier)}</b>
        </span>
      </div>
    </>
  )
}

function hazeTierLabel(tier: number): string {
  const quality = HAZE_TIERS[tier] ?? HAZE_TIERS[0]
  return quality.tier === 0 ? 'full' : `${Math.round(quality.stepScale * 100)}%`
}

type Scope = 'window' | 'machine'

function Group({
  label,
  scope,
  announced = false,
  children,
}: {
  label: string
  scope: Scope
  /** Rides `windows.viewOptions`, so another window's Screens row can set it. */
  announced?: boolean
  children: React.ReactNode
}) {
  return (
    <section aria-label={label} className="flex flex-col gap-1.5">
      <div className="flex h-4 items-center gap-1.5">
        <EditorLabel className="flex-1">{label}</EditorLabel>
        <ScopeChip scope={scope} />
        {announced && (
          <span
            className={cn(SCOPE_CLASS, 'border-primary/50 text-primary')}
            title="Announced: another window's Screens row can set it"
          >
            <Link2 className="size-2.5" />
            Screens
          </span>
        )}
      </div>
      {children}
    </section>
  )
}

const SCOPE_CLASS =
  'inline-flex h-4 items-center gap-1 whitespace-nowrap rounded-full border px-1.5 text-[9px] font-semibold'

function ScopeChip({ scope }: { scope: Scope }) {
  return scope === 'window' ? (
    <span className={cn(SCOPE_CLASS, 'text-muted-foreground')}>
      <Monitor className="size-2.5" />
      this window
    </span>
  ) : (
    <span className={cn(SCOPE_CLASS, 'border-amber-500/50 text-amber-600 dark:text-amber-400')}>
      <Cpu className="size-2.5" />
      this machine
    </span>
  )
}

function Hint({ children }: { children: React.ReactNode }) {
  return <p className="text-[11px] leading-snug text-muted-foreground">{children}</p>
}

const OPTION_CLASS =
  'group h-[30px] justify-start gap-1 rounded-[7px] border bg-background px-1.5 text-[11px] font-normal text-foreground data-[state=on]:border-primary/60 data-[state=on]:bg-primary/15 data-[state=on]:shadow-none'

const RADIO_CLASS =
  'size-3 shrink-0 rounded-full border-[1.5px] border-muted-foreground group-data-[state=on]:border-primary group-data-[state=on]:bg-[radial-gradient(circle,var(--primary)_0_3px,transparent_3.5px)]'

/** One segmented row: every value on screen, the chosen one raised. */
function Segments({
  label,
  value,
  onChange,
  items,
}: {
  label: string
  value: string
  onChange: (value: string) => void
  items: ReadonlyArray<{ value: string; label: string; icon?: LucideIcon }>
}) {
  return (
    <ToggleGroup
      type="single"
      value={value}
      onValueChange={onChange}
      aria-label={label}
      className="flex h-auto w-full gap-0.5 border bg-background p-0.5"
    >
      {items.map((item) => (
        <ToggleGroupItem
          key={item.value}
          value={item.value}
          className="h-6 min-w-0 flex-1 gap-1 px-1.5 text-[11.5px] data-[state=on]:bg-muted data-[state=on]:shadow-none"
        >
          {item.icon != null && <item.icon aria-hidden className="size-3" />}
          {item.label}
        </ToggleGroupItem>
      ))}
    </ToggleGroup>
  )
}

function ShowToggle({
  label,
  hint,
  on,
  onChange,
}: {
  label: string
  hint: string
  on: boolean
  onChange: (on: boolean) => void
}) {
  return (
    <button
      type="button"
      aria-pressed={on}
      title={hint}
      onClick={() => onChange(!on)}
      className={cn(
        'inline-flex h-[26px] items-center gap-1 rounded-[7px] border bg-background pl-1.5 pr-[7px] text-[11px] text-muted-foreground transition-colors',
        on && 'border-primary/60 bg-primary/15 text-foreground',
      )}
    >
      <Check className={cn('size-3 text-primary', !on && 'opacity-0')} />
      {label}
    </button>
  )
}

function Switch({ label, on, onChange }: { label: string; on: boolean; onChange: (on: boolean) => void }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={label}
      onClick={() => onChange(!on)}
      className={cn('relative h-4 w-7 shrink-0 rounded-full transition-colors', on ? 'bg-primary' : 'bg-muted')}
    >
      <span
        className={cn(
          'absolute top-0.5 size-3 rounded-full transition-all',
          on ? 'left-3.5 bg-primary-foreground' : 'left-0.5 bg-muted-foreground',
        )}
      />
    </button>
  )
}
