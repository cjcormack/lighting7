import { Eye } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
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
import type { SceneLayer, SceneLayers } from './scene/sceneView'
import { LIGHT_BUDGETS } from './scene/lightTable'

/** The scene's per-window layers, in the order `Stage.dc.html`'s View menu lists them under Show. */
const SCENE_LAYER_ITEMS: ReadonlyArray<{ layer: SceneLayer; label: string; hint: string }> = [
  { layer: 'venue', label: 'Venue', hint: 'The room: walls, proscenium, the house' },
  { layer: 'set', label: 'Set', hint: "This show's scenery" },
  { layer: 'seating', label: 'Seating', hint: 'The seats, whichever layer they are in' },
  { layer: 'haze', label: 'Haze', hint: 'Beams in the air; off leaves only the light that lands' },
]

interface StageViewMenuProps {
  flags: StageViewFlags
  setFlag: (key: StageViewToggle, value: boolean) => void
  setLabelMode: (mode: StageLabelMode) => void
  /** Flags with no meaning in the current view — e.g. beam cones in a 2D plot. */
  hide?: ReadonlyArray<StageViewToggle>
  /**
   * The 3D view's *Test recovery*: drops the WebGL context the way Safari does under memory
   * pressure, so the paused state and *Restore* can be exercised on purpose. Absent in 2D.
   */
  onTestRecovery?: () => void
  /** Which layer of the lighting cascade the stage draws. */
  visSource: VisSource
  setVisSource: (next: VisSource) => void
  /**
   * A live second line for a source whose hint alone can't say what it is showing — Next GO falls
   * back to plain output when nothing is on deck, and the operator has to be told.
   */
  sourceStatus?: Partial<Record<VisSource, string | null>>
  /**
   * The scene's layers — Venue, Set, Seating, Haze (stage-view plan session 3) — per window. Absent
   * where they mean nothing (the 2D plot draws no scene).
   */
  layers?: SceneLayers
  setLayer?: (layer: SceneLayer, on: boolean) => void
  /** How many lights the surfaces take, per browser; absent where there are no lit surfaces. */
  lightBudget?: number
  setLightBudget?: (budget: number) => void
}

export function StageViewMenu({
  flags,
  setFlag,
  setLabelMode,
  hide,
  onTestRecovery,
  visSource,
  setVisSource,
  sourceStatus,
  layers,
  setLayer,
  lightBudget,
  setLightBudget,
}: StageViewMenuProps) {
  const hidden = (key: StageViewToggle) => hide?.includes(key) ?? false
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button size="sm" variant="outline" aria-label="View options">
          <Eye className="size-3.5 mr-1" />
          View
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-64">
        <DropdownMenuLabel>Source</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuRadioGroup
          value={visSource}
          onValueChange={(v) => {
            if (isVisSource(v)) setVisSource(v)
          }}
        >
          {VIS_SOURCES.map((source) => (
            <DropdownMenuRadioItem key={source} value={source} className="items-start">
              <span className="flex flex-col gap-0.5">
                <span>{VIS_SOURCE_LABELS[source]}</span>
                {/* Not decoration: "Output + Programmer" is identical to "Output" whenever
                    Blind is off, so without the hint that option reads as broken. */}
                <span className="text-xs text-muted-foreground">{VIS_SOURCE_HINTS[source]}</span>
                {sourceStatus?.[source] && (
                  <span className="text-xs text-foreground/70">{sourceStatus[source]}</span>
                )}
              </span>
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
        <DropdownMenuSeparator />
        <DropdownMenuLabel>Show</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuCheckboxItem
          checked={flags.fixtures}
          onCheckedChange={(v) => setFlag('fixtures', !!v)}
        >
          Fixtures
        </DropdownMenuCheckboxItem>
        {!hidden('beamCones') && (
          <DropdownMenuCheckboxItem
            checked={flags.beamCones}
            onCheckedChange={(v) => setFlag('beamCones', !!v)}
          >
            Beam cones
          </DropdownMenuCheckboxItem>
        )}
        <DropdownMenuCheckboxItem
          checked={flags.riggings}
          onCheckedChange={(v) => setFlag('riggings', !!v)}
        >
          Rigging
        </DropdownMenuCheckboxItem>
        <DropdownMenuCheckboxItem
          checked={flags.regions}
          onCheckedChange={(v) => setFlag('regions', !!v)}
        >
          Regions
        </DropdownMenuCheckboxItem>
        {layers != null &&
          setLayer != null &&
          SCENE_LAYER_ITEMS.map(({ layer, label, hint }) => (
            <DropdownMenuCheckboxItem
              key={layer}
              checked={layers[layer]}
              onCheckedChange={(v) => setLayer(layer, !!v)}
              title={hint}
            >
              {label}
            </DropdownMenuCheckboxItem>
          ))}
        <DropdownMenuSeparator />
        <DropdownMenuLabel>Labels</DropdownMenuLabel>
        <DropdownMenuRadioGroup
          value={flags.labels}
          onValueChange={(v) => {
            if (isStageLabelMode(v)) setLabelMode(v)
          }}
        >
          {STAGE_LABEL_MODES.map((mode) => (
            <DropdownMenuRadioItem key={mode} value={mode}>
              {STAGE_LABEL_MODE_LABELS[mode]}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
        {lightBudget != null && setLightBudget != null && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuLabel>
              <span className="flex flex-col gap-0.5">
                <span>Light budget</span>
                {/* The surface shader's cost is pixels × lights; the brightest this many light the
                    room, and the rest still draw their beams. This machine's, not this window's. */}
                <span className="text-xs font-normal text-muted-foreground">
                  The brightest lights the surfaces take, on this machine
                </span>
              </span>
            </DropdownMenuLabel>
            <DropdownMenuRadioGroup
              value={String(lightBudget)}
              onValueChange={(v) => setLightBudget(Number(v))}
            >
              {LIGHT_BUDGETS.map((budget) => (
                <DropdownMenuRadioItem key={budget} value={String(budget)}>
                  {budget} lights
                </DropdownMenuRadioItem>
              ))}
            </DropdownMenuRadioGroup>
          </>
        )}
        {onTestRecovery && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuItem onSelect={onTestRecovery}>
              <span className="flex flex-col gap-0.5">
                <span>Test recovery</span>
                <span className="text-xs text-muted-foreground">
                  Drops the 3D context the way Safari does under memory pressure
                </span>
              </span>
            </DropdownMenuItem>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
