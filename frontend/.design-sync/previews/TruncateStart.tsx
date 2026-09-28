import { TruncateStart } from 'lighting-desk-ui'

// Widths are inline (the component takes `style` for computed widths, as cueNumberCellWidth does);
// the compiled stylesheet carries only the width utilities the app uses.
const Track = ({ width, text }: { width: number; text: string }) => (
  <div className="flex items-center gap-3">
    <span className="w-14 shrink-0 text-xs text-muted-foreground">{width}px</span>
    <TruncateStart
      text={text}
      title={text}
      style={{ width }}
      className="rounded border bg-muted/40 px-1 font-mono text-sm tabular-nums"
    />
  </div>
)

export const CueNumbers = () => (
  <div className="space-y-2">
    <Track width={96} text="PRE-SHOW-3.2.10" />
    <Track width={96} text="S1-3.2.10" />
    <Track width={96} text="S2-3.2.10" />
    <Track width={96} text="12" />
    <p className="text-xs text-muted-foreground">Clipped at the start so the distinguishing tail survives.</p>
  </div>
)

export const Widths = () => (
  <div className="space-y-2">
    <Track width={48} text="ACT2-SC4-17.3.1" />
    <Track width={80} text="ACT2-SC4-17.3.1" />
    <Track width={112} text="ACT2-SC4-17.3.1" />
    <Track width={160} text="ACT2-SC4-17.3.1" />
  </div>
)

export const ShrinkWrap = () => (
  <div className="space-y-2">
    <div className="flex flex-wrap items-center gap-2 text-sm">
      {['1', '2.5', '3', 'T2-1', 'S-1'].map((n) => (
        <TruncateStart
          key={n}
          text={n}
          className="rounded border px-1 font-mono tabular-nums"
        />
      ))}
    </div>
    <p className="text-xs text-muted-foreground">Unbounded: each box shrink-wraps its own label.</p>
  </div>
)
