import { TimingBadge } from 'lighting-desk-ui'

export const Delay = () => (
  <div className="flex flex-wrap items-center gap-2">
    <TimingBadge delayMs={500} />
    <TimingBadge delayMs={2000} />
    <TimingBadge delayMs={90000} />
  </div>
)

export const Interval = () => (
  <div className="flex flex-wrap items-center gap-2">
    <TimingBadge intervalMs={250} />
    <TimingBadge intervalMs={5000} />
    <TimingBadge intervalMs={120000} />
  </div>
)

export const IntervalWithRandomWindow = () => (
  <div className="flex flex-wrap items-center gap-2">
    <TimingBadge intervalMs={5000} randomWindowMs={1500} />
    <TimingBadge delayMs={2000} intervalMs={8000} randomWindowMs={3000} />
  </div>
)

export const InLayerRow = () => (
  <div className="divide-y rounded-md border text-sm">
    {[
      { name: 'Warm Wash', timing: {} },
      { name: 'Lightning', timing: { delayMs: 2000 } },
      { name: 'Candle Flicker', timing: { intervalMs: 5000, randomWindowMs: 1500 } },
    ].map((layer) => (
      <div key={layer.name} className="flex items-center gap-2 px-2 py-1.5">
        <span className="flex-1 truncate">{layer.name}</span>
        <TimingBadge {...layer.timing} />
        <span className="text-xs text-muted-foreground">100%</span>
      </div>
    ))}
  </div>
)
