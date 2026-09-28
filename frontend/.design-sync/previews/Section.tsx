import { AddBtn, LookNameBadge, RemoveBtn, Section } from 'lighting-desk-ui'
import { Layers, Sparkles, Zap } from 'lucide-react'

const noop = () => {}

const LayerRow = ({ name, target, isTemplate }: { name: string; target: string; isTemplate?: boolean }) => (
  <div className="flex items-center gap-2 rounded-md border bg-card px-2 py-1 text-xs">
    <LookNameBadge name={name} isTemplate={isTemplate} />
    <span className="min-w-0 flex-1 truncate text-muted-foreground">{target}</span>
    <RemoveBtn onClick={noop} />
  </div>
)

/** Title, count badge, and an add action in the header — the shape LookStack draws. */
export const WithCountAndAction = () => (
  <Section title="Layers" icon={<Layers className="size-3" />} count={3} action={<AddBtn label="Layer" onClick={noop} />}>
    <LayerRow name="Warm Wash" target="Front wash · Colour" />
    <LayerRow name="Amber Key" target="Specials · Colour" isTemplate />
    <LayerRow name="Band walk-on" target="Movers · Position" />
  </Section>
)

/** A count of zero hides the badge; the empty state is the children. */
export const EmptyWithAction = () => (
  <Section title="Effects" icon={<Sparkles className="size-3" />} count={0} action={<AddBtn label="Effect" onClick={noop} />}>
    <p className="text-xs text-muted-foreground">No effects on this cue.</p>
  </Section>
)

/** Bare title, no icon, no count, no action. */
export const TitleOnly = () => (
  <Section title="Notes">
    <p className="text-xs">House to half, hold for the first downbeat.</p>
  </Section>
)

/** Two sections stacked the way a cue body composes them. */
export const Stacked = () => (
  <div className="space-y-4">
    <Section title="Layers" icon={<Layers className="size-3" />} count={2} action={<AddBtn label="Layer" onClick={noop} />}>
      <LayerRow name="Warm Wash" target="Front wash · Colour" />
      <LayerRow name="Cyc Deep Blue" target="Cyc · Colour" />
    </Section>
    <Section title="Script hooks" icon={<Zap className="size-3" />} count={1} action={<AddBtn label="Hook" onClick={noop} />}>
      <div className="flex items-center gap-2 rounded-md border bg-card px-2 py-1 text-xs">
        <span className="font-medium">ACTIVATION</span>
        <span className="min-w-0 flex-1 truncate text-muted-foreground">Strobe burst on downbeat</span>
        <RemoveBtn onClick={noop} />
      </div>
    </Section>
  </div>
)
