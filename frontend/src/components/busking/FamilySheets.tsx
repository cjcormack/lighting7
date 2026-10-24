import { useCallback, useMemo, useState, type ReactNode } from 'react'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { FxBand, PaletteBand, PaletteFooter } from '@/components/editor/PaletteBands'
import { targetEmitters } from '@/components/fixtures-list/rowModel'
import type { CellCommit } from '@/components/fixtures-list/rowModel'
import { PickPositionControls, pickGroups } from '@/components/fixtureSheet/PickPropertyRow'
import { LevelControl } from '@/components/fixtureSheet/PropertyRow'
import type { PositionResolution } from '@/components/fixtureSheet/sheetRows'
import { writePickPosition } from '@/components/fixtureSheet/sheetWrites'
import { useChannelValues } from '@/components/fixtureSheet/useChannelValues'
import { NewPaletteFromSelectionSheet } from '@/components/programmer/NewPaletteFromSelectionSheet'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import type { AttributeFamily } from '@/lib/attributeFamily'
import { getProgrammerFadeMs } from '@/lib/programmerFade'
import { palettePropertyFor } from '@/lib/paletteIntent'
import { cn } from '@/lib/utils'
import type { ChannelRef, Fixture, SliderPropertyDescriptor } from '@/store/fixtures'
import { useIsDeskConnected } from '@/store/status'
import { lookLayerTarget, type BuskingTarget } from './buskingTypes'
import { writeTargetsOf } from './ColourSheet'
import { beamPropertiesOf, familyUnits, headsWithout, positionOf, sliderOf, writeUnitLevels, type FamilyUnit } from './familyHeads'

/**
 * The busk side sheet's **Level**, **Position** and **Beam** tabs (apply-model plan §4.5, D10): the
 * per-property popup's body over the busk selection pair — the family's Palettes band, the editor,
 * the Effects band, and the footer's *Save as palette…* — docked in a tab.
 *
 * A tab is not one cell, so each names the property it acts on: **Level** the dimmer, **Position**
 * pan and tilt as one, **Beam** the one Beam level the operator picks from those the selection
 * carries (zoom, focus, iris, frost), so the band, the slider and the effects all mean that property
 * as a Beam cell's popup does.
 *
 * **The editor writes Local, and only that**, as the Colour tab does: a drag or a typed value is a
 * programmer value on each selected head (a group as one group entry where every member carries the
 * property — [familyUnits]), the fixture sheet's pick controls and its writes, never a third copy; a
 * selected head without the property is left out and the tab says so. A drag writes at once, a typed
 * value at the programmer fade. **A band chip is a press** through
 * `usePress` with the busk pair, its scope `'programmer'` — Local, whatever the grid is pointed at.
 */

interface FamilyTabProps {
  projectId: number
  selectedTargets: Map<string, BuskingTarget>
  /** The busk pair's mask, which a band press carries. Null is every attribute. */
  families: AttributeFamily[] | null
}

const EMPTY_FIXTURES: readonly Fixture[] = []

/** The selection, as the tabs read it: its targets, the press's targets and the heads' emitters. */
function useTabSelection(selectedTargets: Map<string, BuskingTarget>) {
  const { fixtures: maybeFixtures } = useFixtureLookup()
  const fixtures = maybeFixtures ?? EMPTY_FIXTURES
  const selected = useMemo(() => [...selectedTargets.values()], [selectedTargets])
  const pressTargets = useMemo(() => selected.map(lookLayerTarget), [selected])
  const emitters = useMemo(() => targetEmitters(writeTargetsOf(selected, fixtures)), [selected, fixtures])
  return { fixtures, selected, pressTargets, emitters }
}

/** The tab's frame: one scroller — the Palettes band, the editor, the Effects band — over the footer. */
function FamilyTab({
  projectId,
  family,
  property,
  pressTargets,
  emitters,
  families,
  above,
  children,
}: {
  projectId: number
  family: AttributeFamily
  property: string
  pressTargets: readonly { type: 'group' | 'fixture'; key: string }[]
  emitters: readonly string[]
  families: AttributeFamily[] | null
  /** Drawn above the Palettes band — the Beam tab's property row. */
  above?: ReactNode
  children: ReactNode
}) {
  const [saving, setSaving] = useState(false)
  const familyList = useMemo(() => [family], [family])
  return (
    <div data-family-sheet={family} className="flex min-h-0 flex-1 flex-col border-l">
      <div className="min-h-0 flex-1 space-y-3 overflow-y-auto px-3 py-3">
        {above}
        <PaletteBand
          projectId={projectId}
          family={family}
          property={property}
          targets={pressTargets}
          families={families}
          emitters={emitters}
          scope="programmer"
        />
        {children}
        <FxBand
          projectId={projectId}
          family={family}
          property={property}
          targets={pressTargets}
          families={families}
          scope="programmer"
        />
      </div>
      <PaletteFooter
        projectId={projectId}
        canSave={pressTargets.length > 0}
        onSave={() => setSaving(true)}
        className="shrink-0 px-3 py-2"
      />
      <NewPaletteFromSelectionSheet
        open={saving}
        onOpenChange={setSaving}
        projectId={projectId}
        families={familyList}
        targets={pressTargets}
      />
    </div>
  )
}

/** What the editor says when the selection gives it nothing to write. */
function NoHeads({ selected, what }: { selected: number; what: string }) {
  return (
    <p data-family-sheet-empty className="text-xs text-muted-foreground">
      {selected === 0 ? `Select heads on the rig to set their ${what}.` : `No selected head has a ${what}.`}
    </p>
  )
}

/** The selected heads the editor leaves out, having no such property — said, never silent. */
function LeftOut({ count, what }: { count: number; what: string }) {
  if (count === 0) return null
  return (
    <p data-family-sheet-left-out className="text-[10.5px] text-muted-foreground">
      {count === 1 ? `1 selected head has no ${what} and is left out.` : `${count} selected heads have no ${what} and are left out.`}
    </p>
  )
}

/** A level over the units: the slider at the highest head, the track drawing the range below it. */
function UnitLevel({ label, units }: { label: string; units: readonly FamilyUnit<SliderPropertyDescriptor>[] }) {
  const connected = useIsDeskConnected()
  const refs = useMemo<ChannelRef[]>(() => units.flatMap((u) => u.heads.map((h) => h.resolution.channel)), [units])
  const values = useChannelValues(refs)
  const lead = units[0].heads[0].resolution
  const high = values.length > 0 ? Math.max(...values) : 0
  const low = values.length > 0 ? Math.min(...values) : 0
  const write = useCallback((level: number, fadeMs?: number) => writeUnitLevels(units, level, fadeMs), [units])
  return (
    <div className="space-y-1">
      <EditorLabel>{label}</EditorLabel>
      <div data-family-level className="flex min-w-0 items-center gap-2">
        <LevelControl
          label={label}
          value={high}
          lowest={low}
          min={lead.min}
          max={lead.max}
          readOnly={!connected}
          onDrag={(v) => write(v)}
          onTyped={(v) => write(v, getProgrammerFadeMs())}
        />
      </div>
    </div>
  )
}

/** The **Level** tab: the dimmer of every selected head. */
export function LevelSheet({ projectId, selectedTargets, families }: FamilyTabProps) {
  const { fixtures, selected, pressTargets, emitters } = useTabSelection(selectedTargets)
  const units = useMemo(() => familyUnits(selected, fixtures, sliderOf('dimmer')), [selected, fixtures])
  const without = useMemo(() => headsWithout(selected, fixtures, sliderOf('dimmer')), [selected, fixtures])
  return (
    <FamilyTab
      projectId={projectId}
      family="INTENSITY"
      property="dimmer"
      pressTargets={pressTargets}
      emitters={emitters}
      families={families}
    >
      {units.length === 0 ? (
        <NoHeads selected={selected.length} what="dimmer" />
      ) : (
        <>
          <UnitLevel label="Level" units={units} />
          <LeftOut count={without} what="dimmer" />
        </>
      )}
    </FamilyTab>
  )
}

/** The **Beam** tab: one Beam level, chosen from those the selection carries. */
export function BeamSheet({ projectId, selectedTargets, families }: FamilyTabProps) {
  const { fixtures, selected, pressTargets, emitters } = useTabSelection(selectedTargets)
  const properties = useMemo(() => beamPropertiesOf(selected, fixtures), [selected, fixtures])
  const [chosen, setChosen] = useState<string | null>(null)
  // The operator's choice while the selection still carries it, else the first it does — zoom
  // where there is one — and zoom with nothing selected, so the bands still offer something.
  const property = chosen != null && properties.includes(chosen) ? chosen : (properties[0] ?? 'zoom')
  const units = useMemo(() => familyUnits(selected, fixtures, sliderOf(property)), [selected, fixtures, property])
  const without = useMemo(() => headsWithout(selected, fixtures, sliderOf(property)), [selected, fixtures, property])
  const label = palettePropertyFor(property)?.label ?? property
  const row =
    properties.length > 1 ? (
      <div role="radiogroup" aria-label="Beam attribute" className="flex flex-wrap items-center gap-1">
        {properties.map((name) => (
          <button
            key={name}
            type="button"
            role="radio"
            aria-checked={name === property}
            onClick={() => setChosen(name)}
            className={cn(
              'h-7 rounded-md border px-2.5 text-xs font-medium',
              name === property ? 'border-primary bg-primary/10 text-foreground' : 'text-muted-foreground hover:text-foreground',
            )}
          >
            {palettePropertyFor(name)?.label ?? name}
          </button>
        ))}
      </div>
    ) : null
  return (
    <FamilyTab
      projectId={projectId}
      family="BEAM"
      property={property}
      pressTargets={pressTargets}
      emitters={emitters}
      families={families}
      above={row}
    >
      {units.length === 0 ? (
        <NoHeads selected={selected.length} what={properties.length === 0 ? 'beam level' : label.toLowerCase()} />
      ) : (
        <>
          <UnitLevel key={property} label={label} units={units} />
          <LeftOut count={without} what={label.toLowerCase()} />
        </>
      )}
    </FamilyTab>
  )
}

/** The **Position** tab: pan and tilt of every selected head, the pad and the two axes. */
export function PositionSheet({ projectId, selectedTargets, families }: FamilyTabProps) {
  const { fixtures, selected, pressTargets, emitters } = useTabSelection(selectedTargets)
  const units = useMemo(() => familyUnits(selected, fixtures, positionOf), [selected, fixtures])
  const without = useMemo(() => headsWithout(selected, fixtures, positionOf), [selected, fixtures])
  return (
    <FamilyTab
      projectId={projectId}
      family="POSITION"
      property="position"
      pressTargets={pressTargets}
      emitters={emitters}
      families={families}
    >
      {units.length === 0 ? (
        <NoHeads selected={selected.length} what="position" />
      ) : (
        <>
          <UnitPosition units={units} />
          <LeftOut count={without} what="position" />
        </>
      )}
    </FamilyTab>
  )
}

type PositionCommit = Extract<CellCommit, { kind: 'position' }>

/** The fixture sheet's pick controls over the units, written into Local per unit. */
function UnitPosition({ units }: { units: readonly FamilyUnit<PositionResolution>[] }) {
  const connected = useIsDeskConnected()
  const heads = useMemo(() => units.flatMap((u) => u.heads), [units])
  const groups = useMemo(() => [...new Set(units.flatMap((u) => pickGroups(u.write) ?? []))], [units])
  const onCommit = (commit: PositionCommit | ((r: PositionResolution) => PositionCommit), typed: boolean) => {
    const fadeMs = typed ? getProgrammerFadeMs() : undefined
    for (const unit of units) writePickPosition(unit.write, unit.heads, commit, fadeMs)
  }
  return <PickPositionControls heads={heads} groups={groups} open readOnly={!connected} onCommit={onCommit} />
}
