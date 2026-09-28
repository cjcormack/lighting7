import { LookNameBadge } from 'lighting-desk-ui'

/** A Look named as the operator named it — never a P<n> short code. */
export const Looks = () => (
  <div className="flex flex-wrap items-center gap-2">
    <LookNameBadge name="Warm Wash" />
    <LookNameBadge name="Band walk-on" />
    <LookNameBadge name="Cyc Deep Blue" />
  </div>
)

/** A template: same shape and rank, palette glyph instead of layers. */
export const Templates = () => (
  <div className="flex flex-wrap items-center gap-2">
    <LookNameBadge name="Amber Key" isTemplate />
    <LookNameBadge name="Full" isTemplate />
    <LookNameBadge name="Centre Stage" isTemplate />
  </div>
)

/** The thing this names is gone — destructive colouring carries "broken", with or without a name. */
export const Missing = () => (
  <div className="flex flex-wrap items-center gap-2">
    <LookNameBadge name="Old Cyc Wash" missing />
    <LookNameBadge missing />
    <LookNameBadge name="Retired Amber" missing isTemplate />
    <LookNameBadge missing isTemplate />
  </div>
)

/** Name not yet known (list still loading) — plain, not red; and a long name truncates. */
export const UnnamedAndTruncated = () => (
  <div className="flex flex-wrap items-center gap-2">
    <LookNameBadge />
    <LookNameBadge isTemplate />
    <div style={{ width: 160 }}>
      <LookNameBadge name="Act 3 — Finale curtain call full-stage wash" />
    </div>
  </div>
)
