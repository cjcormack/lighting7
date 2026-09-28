import { AddBtn, LookNameBadge, Section } from 'lighting-desk-ui'
import { Layers } from 'lucide-react'

const noop = () => {}

/** The labels it carries across the desk. */
export const Labels = () => (
  <div className="flex flex-wrap items-center gap-2">
    <AddBtn label="Layer" onClick={noop} />
    <AddBtn label="Effect" onClick={noop} />
    <AddBtn label="Hook" onClick={noop} />
    <AddBtn label="Add to targets" onClick={noop} />
  </div>
)

/** As a section header action, right-aligned by the header's spacer. */
export const AsSectionAction = () => (
  <Section title="Layers" icon={<Layers className="size-3" />} count={2} action={<AddBtn label="Layer" onClick={noop} />}>
    <div className="flex items-center gap-2 rounded-md border bg-card px-2 py-1 text-xs">
      <LookNameBadge name="Warm Wash" />
      <span className="text-muted-foreground">Front wash · Colour</span>
    </div>
    <div className="flex items-center gap-2 rounded-md border bg-card px-2 py-1 text-xs">
      <LookNameBadge name="Amber Key" isTemplate />
      <span className="text-muted-foreground">Specials · Colour</span>
    </div>
  </Section>
)

/** Trailing a short list as the "one more" affordance. */
export const BelowList = () => (
  <div className="space-y-1.5">
    <div className="rounded-md border bg-card px-2 py-1 text-xs">Front wash</div>
    <div className="rounded-md border bg-card px-2 py-1 text-xs">Movers</div>
    <AddBtn label="Group" onClick={noop} />
  </div>
)
