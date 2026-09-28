import { LookValueChip } from 'lighting-desk-ui'

const Row = ({ label, children }: { label: string; children: React.ReactNode }) => (
  <div className="flex items-center gap-2">
    <span className="w-28 shrink-0 text-xs text-muted-foreground">{label}</span>
    <div className="flex flex-wrap items-center gap-1">{children}</div>
  </div>
)

/** Colour literals get a swatch — including ones carrying white / amber / UV tags. */
export const Colours = () => (
  <div className="space-y-2">
    <Row label="RGB">
      <LookValueChip value="#FF9D4A" />
      <LookValueChip value="#1E40AF" />
      <LookValueChip value="#22C55E" />
      <LookValueChip value="#FFFFFF" />
      <LookValueChip value="#0A0A0A" />
    </Row>
    <Row label="With emitters">
      <LookValueChip value="#FF9D4A;w120" />
      <LookValueChip value="#FF5A00;a200" />
      <LookValueChip value="#3B0764;uv255" />
      <LookValueChip value="#000000;w255" />
    </Row>
    <Row label="Named">
      <LookValueChip value="red" />
      <LookValueChip value="amber" />
      <LookValueChip value="cyan" />
    </Row>
  </div>
)

/** Levels stay raw 0..255 so 127 and 128 remain distinguishable. */
export const Levels = () => (
  <Row label="Levels">
    <LookValueChip value="0" />
    <LookValueChip value="64" />
    <LookValueChip value="127" />
    <LookValueChip value="128" />
    <LookValueChip value="255" />
  </Row>
)

/** Positions render as the raw pan,tilt pair — there is no fixture in hand to draw a crosshair. */
export const Positions = () => (
  <Row label="Pan,tilt">
    <LookValueChip value="128,128" />
    <LookValueChip value="40,215" />
    <LookValueChip value="0,0" />
    <LookValueChip value="255,96" />
  </Row>
)

/** A mixed Look, and a value outside the grammar which falls back to its raw text. */
export const Mixed = () => (
  <Row label="Warm Wash">
    <LookValueChip value="#FF9D4A;w120" />
    <LookValueChip value="255" />
    <LookValueChip value="128,140" />
    <LookValueChip value="#1E40AF" />
    <LookValueChip value="tmpl:9f2e" />
  </Row>
)
