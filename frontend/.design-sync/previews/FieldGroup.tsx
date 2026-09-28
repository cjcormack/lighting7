import { FieldGroup, NumberField, Input } from 'lighting-desk-ui'

const noop = () => {}

export const Position = () => (
  <div className="w-full max-w-sm">
    <FieldGroup label="Position (m)">
      <NumberField id="pos-x" label="X" value={2.5} onChange={noop} />
      <NumberField id="pos-y" label="Y" value={-1.2} onChange={noop} />
      <NumberField id="pos-z" label="Z" value={4} onChange={noop} min={0} />
    </FieldGroup>
  </div>
)

export const Rotation = () => (
  <div className="w-full max-w-sm">
    <FieldGroup label="Rotation (°)">
      <NumberField id="rot-x" label="Pan" value={-45} onChange={noop} />
      <NumberField id="rot-y" label="Tilt" value={30} onChange={noop} />
      <NumberField id="rot-z" label="Roll" value={0} onChange={noop} />
    </FieldGroup>
  </div>
)

export const Stacked = () => (
  <div className="w-full max-w-sm space-y-4">
    <FieldGroup label="DMX patch">
      <Input defaultValue="1" aria-label="Universe" />
      <Input defaultValue="101" aria-label="Address" />
      <Input defaultValue="16" aria-label="Footprint" disabled />
    </FieldGroup>
    <FieldGroup label="Beam (°)">
      <NumberField id="beam-min" label="Min" value={8} onChange={noop} min={0} />
      <NumberField id="beam-max" label="Max" value={42} onChange={noop} min={0} />
      <NumberField id="beam-default" label="Default" value={25} onChange={noop} min={0} />
    </FieldGroup>
  </div>
)
