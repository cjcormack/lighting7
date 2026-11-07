import type { AttributeFamily } from '@/lib/attributeFamily'
import type { PaletteIntent } from '@/lib/paletteIntent'
import { BeamControls } from './BeamControls'
import { ColourControl } from './ColourControl'
import { PercentControl } from './PercentControl'
import { PositionControl } from './PositionControl'
import type { PaletteValues } from './paletteRows'

/** The family-native control set. One per family, and nothing descriptor-driven. */
export function FamilyControls({
  family,
  values,
  onChange,
  projectId,
}: {
  family: AttributeFamily
  values: PaletteValues
  onChange: (attribute: string, intent: PaletteIntent | null) => void
  /**
   * The palette's project. The controls' Recent bands are the live project's lists, so they are
   * drawn, and record, only while it is the live one (D18).
   */
  projectId?: number
}) {
  switch (family) {
    case 'COLOUR':
      return <ColourControl values={values} onChange={onChange} projectId={projectId} />
    case 'INTENSITY':
      return (
        <div className="space-y-4">
          <PercentControl
            label="Level"
            attribute="dimmer"
            projectId={projectId}
            value={values.dimmer}
            onChange={(i) => onChange('dimmer', i)}
          />
          <PercentControl
            label="Strobe"
            attribute="strobe"
            projectId={projectId}
            hint="A percentage of each head's own strobe channel, not a rate: the heads' strobe rates are mostly estimates, so a palette cannot promise one yet."
            value={values.strobe}
            onChange={(i) => onChange('strobe', i)}
          />
        </div>
      )
    case 'POSITION':
      return <PositionControl value={values.position} onChange={(i) => onChange('position', i)} projectId={projectId} />
    case 'BEAM':
      return <BeamControls values={values} onChange={onChange} projectId={projectId} />
  }
}
