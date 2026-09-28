import { OutOfOrderBanner } from 'lighting-desk-ui'

const noop = () => {}

export const Default = () => <OutOfOrderBanner onFixOrder={noop} onDismiss={noop} />

/** Where it sits: across the top of a stack whose numbers no longer match its playback order. */
export const AboveCueList = () => (
  <div className="overflow-hidden rounded-md border bg-card">
    <OutOfOrderBanner onFixOrder={noop} onDismiss={noop} />
    {[
      ['12', 'Band walk-on', '3s'],
      ['12.5', 'Vocal special', '1s'],
      ['11', 'Blackout', '0s'],
      ['S1-3', 'Warm Wash', '5s'],
    ].map(([num, name, fade]) => (
      <div
        key={num}
        className="flex items-center gap-3 border-b px-4 py-2 text-sm last:border-b-0"
      >
        <span className="w-12 font-mono text-xs text-muted-foreground">{num}</span>
        <span className="flex-1">{name}</span>
        <span className="text-xs text-muted-foreground">{fade}</span>
      </div>
    ))}
  </div>
)
