import { useRef, useState } from 'react'
import { Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  MAX_EXTRA_PLACEMENTS,
  MAX_PLACEMENT_LABEL_LENGTH,
  type PatchPlacementInput,
} from '@/api/patchApi'
import { mirroredPlacement, type PlacementGeometry } from '@/lib/extraPlacements'
import { PatchPlacementFields } from './PatchPlacementFields'
import { FixtureLengthField } from './FixtureLengthField'
import { cn } from '@/lib/utils'

interface Props {
  projectId: number
  /** The fixture's own placement, as currently edited — what a new lantern is mirrored from. */
  primary: PlacementGeometry
  value: PatchPlacementInput[]
  onChange: (next: PatchPlacementInput[]) => void
  /**
   * Set for a fixture whose length is set per install (a lightstrip): the placements are then the
   * other *sides* of one run — a ring round the stage edge — each with a length of its own, and
   * `fixtureM` is the length one without its own takes (the fixture's, else the type default).
   */
  segmentLength?: { fixtureM: number | null }
}

/**
 * "Also hung at": a paired dimmer's other lanterns.
 *
 * One circuit can feed two lanterns — an SL and an SR unit on one bar. That is one fixture to the
 * desk (one address, one row in every cue and group), so it is patched once and its other lanterns
 * are listed here, each with the same placement fields as the fixture itself plus a short label
 * for the plot. Every stage view draws each one, lit from the fixture's channels. They are moved
 * here rather than by dragging on the plot, where a drag moves the fixture's own placement.
 */
export function ExtraPlacementsFields({ projectId, primary, value, onChange, segmentLength }: Props) {
  const segments = segmentLength != null
  const atCap = value.length >= MAX_EXTRA_PLACEMENTS
  const replace = (index: number, next: PatchPlacementInput) =>
    onChange(value.map((p, i) => (i === index ? next : p)))

  // React keys, one per row and kept beside the list rather than derived from it: an unsaved
  // lantern has no uuid, and an index key would hand a removed row's fields to the one after it.
  // Rebuilt from the list if it changes length by some other route (the form resetting).
  const nextKey = useRef(0)
  const mint = () => `new-${nextKey.current++}`
  const [rowKeys, setRowKeys] = useState<string[]>(() => value.map((p) => p.uuid ?? mint()))
  const keys = rowKeys.length === value.length ? rowKeys : value.map((p, i) => p.uuid ?? `new-at-${i}`)
  const add = () => {
    setRowKeys([...keys, mint()])
    onChange([...value, mirroredPlacement(primary)])
  }
  const remove = (index: number) => {
    setRowKeys(keys.filter((_, i) => i !== index))
    onChange(value.filter((_, i) => i !== index))
  }

  return (
    <div className="space-y-2.5 rounded-md border border-border p-3">
      <div className="flex items-center gap-2">
        <p className="flex-1 text-xs font-medium text-muted-foreground">
          {segments ? 'Other sides of this run' : 'Also hung at'}
        </p>
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="h-7"
          onClick={add}
          disabled={atCap}
          title={
            atCap
              ? `At most ${MAX_EXTRA_PLACEMENTS} ${segments ? 'sides' : 'lanterns'} per fixture`
              : segments
                ? 'Add another side of this run, mirrored across the centre line to start'
                : 'Add another lantern on this circuit, mirrored across the centre line to start'
          }
        >
          <Plus className="size-3.5" />
          {segments ? 'Side' : 'Lantern'}
        </Button>
      </div>
      {value.length === 0 ? (
        <p className="text-xs text-muted-foreground">
          {segments
            ? 'For a run laid along several sides — a ring round the stage edge: each other side, with its own position, direction and length, lit with this fixture.'
            : 'For a paired dimmer: another lantern on this same circuit, drawn on the stage and lit with this fixture.'}
        </p>
      ) : (
        value.map((placement, index) => {
          const idPrefix = `extra-placement-${index}`
          return (
            <div
              key={keys[index]}
              className={cn('space-y-3', index > 0 && 'border-t border-border pt-3')}
            >
              <div className="flex items-end gap-2">
                <div className="flex-1 space-y-1.5">
                  <Label htmlFor={`${idPrefix}-label`}>Label</Label>
                  <Input
                    id={`${idPrefix}-label`}
                    value={placement.label ?? ''}
                    maxLength={MAX_PLACEMENT_LABEL_LENGTH}
                    placeholder={segments ? 'e.g. US' : 'e.g. SR'}
                    onChange={(e) => replace(index, { ...placement, label: e.target.value })}
                  />
                </div>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  aria-label={`Remove ${placement.label?.trim() || `${segments ? 'side' : 'lantern'} ${index + 1}`}`}
                  onClick={() => remove(index)}
                >
                  <Trash2 className="size-4" />
                </Button>
              </div>
              <PatchPlacementFields
                projectId={projectId}
                idPrefix={idPrefix}
                value={placement}
                onChange={(geometry) => replace(index, { ...placement, ...geometry })}
              />
              {segmentLength && (
                <FixtureLengthField
                  id={`${idPrefix}-length`}
                  value={placement.lengthM ?? null}
                  onChange={(lengthM) => replace(index, { ...placement, lengthM })}
                  fallbackM={segmentLength.fixtureM}
                  fallbackLabel="fixture's"
                />
              )}
            </div>
          )
        })
      )}
    </div>
  )
}
