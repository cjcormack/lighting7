import type { AttributeFamily } from '@/lib/attributeFamily'
import type { TemplateIntent } from '@/lib/templateIntent'
import { BeamControls } from './BeamControls'
import { ColourControl } from './ColourControl'
import { PercentControl } from './PercentControl'
import { PositionControl } from './PositionControl'
import type { TemplateValues } from './templateRows'

/** The family-native control set. One per family, and nothing descriptor-driven. */
export function FamilyControls({
  family,
  values,
  onChange,
}: {
  family: AttributeFamily
  values: TemplateValues
  onChange: (propertyName: string, intent: TemplateIntent | null) => void
}) {
  switch (family) {
    case 'COLOUR':
      return <ColourControl values={values} onChange={onChange} />
    case 'INTENSITY':
      return (
        <div className="space-y-4">
          <PercentControl
            label="Level"
            value={values.dimmer}
            onChange={(i) => onChange('dimmer', i)}
          />
          <PercentControl
            label="Strobe"
            hint="A percentage of each head's own strobe channel, not a rate: no fixture in this rig declares a Hz range, so a template cannot promise one."
            value={values.strobe}
            onChange={(i) => onChange('strobe', i)}
          />
        </div>
      )
    case 'POSITION':
      return <PositionControl value={values.position} onChange={(i) => onChange('position', i)} />
    case 'BEAM':
      return <BeamControls values={values} onChange={onChange} />
  }
}
