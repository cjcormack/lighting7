import { NumberField } from 'lighting-desk-ui'

const noop = () => {}

export const Default = () => (
  <div className="w-full max-w-xs">
    <NumberField id="fade" label="Fade time (s)" value={3} onChange={noop} min={0} />
  </div>
)

export const Row = () => (
  <div className="grid w-full max-w-sm grid-cols-3 gap-2">
    <NumberField id="delay" label="Delay (ms)" value={250} onChange={noop} min={0} />
    <NumberField id="interval" label="Interval (ms)" value={4000} onChange={noop} min={0} />
    <NumberField id="random" label="Random (ms)" value={800} onChange={noop} min={0} />
  </div>
)

export const Values = () => (
  <div className="grid w-full max-w-sm grid-cols-3 gap-2">
    <NumberField id="neg" label="Pan offset (°)" value={-12.5} onChange={noop} />
    <NumberField id="zero" label="Tilt offset (°)" value={0} onChange={noop} />
    <NumberField id="empty" label="Roll (°)" value={null!} onChange={noop} />
  </div>
)
