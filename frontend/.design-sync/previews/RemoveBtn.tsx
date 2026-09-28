import { LookNameBadge, LookValueChip, RemoveBtn } from 'lighting-desk-ui'

const noop = () => {}

/** On the right of layer rows — muted until hovered, then destructive. */
export const OnLayerRows = () => (
  <div className="space-y-1.5">
    {[
      ['Warm Wash', 'Front wash · Colour', false],
      ['Amber Key', 'Specials · Colour', true],
      ['Band walk-on', 'Movers · Position', false],
    ].map(([name, target, isTemplate]) => (
      <div key={String(name)} className="flex items-center gap-2 rounded-md border bg-card px-2 py-1 text-xs">
        <LookNameBadge name={String(name)} isTemplate={Boolean(isTemplate)} />
        <span className="min-w-0 flex-1 truncate text-muted-foreground">{String(target)}</span>
        <RemoveBtn onClick={noop} />
      </div>
    ))}
  </div>
)

/** On value rows in a Look detail sheet. */
export const OnValueRows = () => (
  <div className="space-y-1.5">
    {[
      ['Front wash', 'Colour', '#FF9D4A;w120'],
      ['Front wash', 'Intensity', '255'],
      ['Movers', 'Position', '128,140'],
    ].map(([target, prop, value]) => (
      <div key={`${target}-${prop}`} className="flex items-center gap-2 rounded-md border bg-card px-2 py-1 text-xs">
        <span className="w-24 truncate font-medium">{target}</span>
        <span className="w-16 text-muted-foreground">{prop}</span>
        <LookValueChip value={value} />
        <span className="flex-1" />
        <RemoveBtn onClick={noop} />
      </div>
    ))}
  </div>
)

/** Alone, for scale against text. */
export const Alone = () => (
  <div className="flex items-center gap-2 text-xs">
    <span>Cyc Deep Blue</span>
    <RemoveBtn onClick={noop} />
  </div>
)
