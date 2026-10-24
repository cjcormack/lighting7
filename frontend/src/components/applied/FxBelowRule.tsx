import type { ReactNode } from 'react'
import { ArrowDownUp } from 'lucide-react'

/** *Values above beat FX below* — the boundary between the stack and the effects, whatever order the rows are in. */
export function FxBelowRule({ children }: { children?: ReactNode }) {
  return (
    <div
      data-effects-rule
      className="flex h-7 shrink-0 items-center gap-1.5 border-b border-amber-800/70 bg-amber-950/40 px-3"
      title="FX run under the values: a value set above an FX beats it, whatever the layer order. Per-layer stomp is the way round it."
    >
      <ArrowDownUp className="size-3 shrink-0 text-amber-300" />
      <span className="text-[9px] font-bold uppercase tracking-[0.06em] text-amber-300">Values above beat FX below</span>
      {children}
    </div>
  )
}
