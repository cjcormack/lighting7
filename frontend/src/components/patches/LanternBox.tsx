import { useState } from 'react'
import { Crosshair } from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { PatchPlacementInput } from '@/api/patchApi'
import {
  effectiveLantern,
  focusFields,
  focusSummary,
  lanternFieldDeg,
  zoomFor,
  type LanternFocus,
  type LanternIndex,
} from '@/lib/lanterns'
import type { FixtureKind } from '@/store/fixtures'
import { LanternPicker } from '@/components/lanterns/LanternPicker'
import { FocusCard, type FocusUnit } from '@/components/lanterns/FocusCard'
import { BeamAngleField } from './BeamAngleField'
import { GelPickerField } from './GelPickerField'

interface LanternBoxProps {
  idPrefix: string
  lanterns: LanternIndex
  /** The fixture's kind as it stands — what a lantern named none defaults by. */
  kind: FixtureKind
  focus: LanternFocus
  onFocusChange: (next: LanternFocus) => void
  gelCode: string | null
  onGelChange: (next: string | null) => void
  beamAngleDeg: number | null
  onBeamAngleChange: (next: number | null) => void
  /** The fixture's other lanterns — the pair switch's other entries. */
  placements: readonly PatchPlacementInput[]
  onPlacementsChange: (next: PatchPlacementInput[]) => void
}

/**
 * The **Lantern** box (stage-view plan session 7): which lantern from the library a conventional
 * dimmer is hung with, its beam and field, its zoom within the lantern's range, the gel in its
 * frame, an explicit beam-angle override, and its focus — the card the Stage view's Focus tab
 * draws, over every lantern of the pair. It replaces *Beam & Gel* and *3D shape*: the lantern is
 * the shape, and the desk derives the fixture's kind from it.
 *
 * Nothing here writes: the form saves the lot on Save, as it does every other field.
 */
export function LanternBox({
  idPrefix,
  lanterns,
  kind,
  focus,
  onFocusChange,
  gelCode,
  onGelChange,
  beamAngleDeg,
  onBeamAngleChange,
  placements,
  onPlacementsChange,
}: LanternBoxProps) {
  const [focusing, setFocusing] = useState(false)
  const [active, setActive] = useState(0)
  const lantern = effectiveLantern(lanterns, focus.lanternType, kind)
  const kindDefault = effectiveLantern(lanterns, null, kind)
  const zoomed = lantern ? lanternFieldDeg(lantern, focus.zoomDeg) : null

  const units: FocusUnit[] = [
    { key: 'fixture', label: placements.length > 0 ? 'Lantern 1 · this one' : 'This lantern', lantern, focus },
    ...placements.map((p, i) => ({
      key: p.uuid ?? `new-${i}`,
      label: `Lantern ${i + 2}${p.label?.trim() ? ` · ${p.label.trim()}` : ''}`,
      lantern: effectiveLantern(lanterns, p.lanternType ?? focus.lanternType, kind),
      focus: p,
    })),
  ]
  const change = (index: number, next: LanternFocus) => {
    if (index === 0) onFocusChange({ ...focusFields(next) })
    else onPlacementsChange(placements.map((p, i) => (i === index - 1 ? { ...p, ...focusFields(next) } : p)))
  }

  return (
    <div className="space-y-2.5 rounded-md border border-border p-3" data-lantern-box>
      <p className="text-xs font-medium text-muted-foreground">Lantern</p>
      <LanternPicker
        id={`${idPrefix}-lantern`}
        value={focus.lanternType}
        onChange={(lanternType) => {
          const next = effectiveLantern(lanterns, lanternType, kind)
          // A zoom the new lantern cannot take is dropped, as the desk would drop it.
          onFocusChange({ ...focusFields(focus), lanternType, zoomDeg: zoomFor(next, focus.zoomDeg) })
        }}
        lanterns={lanterns}
        inherited={kindDefault}
        inheritWord="Default for the kind"
        label="Type"
      />
      {lantern && (
        <p className="text-xs text-muted-foreground" data-lantern-field>
          {lantern.beamDeg != null ? `${fmt(lantern.beamDeg)}° beam · ` : ''}
          {zoomed != null ? `${fmt(zoomed)}° field` : ''}
          {lantern.oval ? ` × ${fmt(lantern.oval.narrowDeg)}° oval` : ''}
          {' · '}
          {lantern.zoom ? `zoom ${fmt(lantern.zoom.minDeg)}–${fmt(lantern.zoom.maxDeg)}°` : 'zoom · fixed'}
        </p>
      )}
      <GelPickerField id={`${idPrefix}-gel`} value={gelCode} onChange={onGelChange} />
      <BeamAngleField id={`${idPrefix}-beam`} value={beamAngleDeg} onChange={onBeamAngleChange} />
      <p className="text-xs text-muted-foreground">
        A beam angle set here overrides the lantern&apos;s field; leave it blank to draw the lantern.
      </p>
      <div className="flex items-center gap-2">
        <p className="min-w-0 flex-1 truncate text-xs text-muted-foreground" data-focus-summary>
          Focus · {focusSummary(lantern, focus)}
        </p>
        <Button
          type="button"
          variant={focusing ? 'default' : 'outline'}
          size="sm"
          className="h-7"
          aria-expanded={focusing}
          onClick={() => setFocusing((v) => !v)}
        >
          <Crosshair className="size-3.5" />
          Focus
        </Button>
      </div>
      {focusing && (
        <div className="rounded-md border border-border p-3">
          <FocusCard
            units={units}
            active={active}
            onActiveChange={setActive}
            onChange={change}
            note={
              units.length > 1
                ? "Stored on each lantern's placement, never in a look. The lanterns of a pair are focused separately."
                : 'Stored on the patch, never in a look.'
            }
          />
        </div>
      )}
    </div>
  )
}

function fmt(v: number): string {
  return Number.isInteger(v) ? String(v) : v.toFixed(1)
}
