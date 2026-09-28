import { InlineEditField } from 'lighting-desk-ui'

// Column tracks are inline: the compiled stylesheet only carries utilities the app uses, and an
// arbitrary `grid-cols-[…]` is not one of them.
const cols = { display: 'grid', gridTemplateColumns: '4rem 1fr 4rem' } as const

const Row = ({ children }: { children: React.ReactNode }) => (
  <div style={cols} className="items-center gap-2 rounded-md border px-2 py-1.5 text-sm">
    {children}
  </div>
)

export const CueRow = () => (
  <div className="space-y-1">
    <div style={cols} className="gap-2 px-2 text-[10px] uppercase tracking-wide text-muted-foreground">
      <span>Cue #</span>
      <span>Name</span>
      <span>Fade</span>
    </div>
    <Row>
      <InlineEditField
        value="12"
        ariaLabel="cue number"
        formatDisplay={(v) => `Q${v}`}
        onCommit={() => {}}
        className="font-mono tabular-nums"
      />
      <InlineEditField value="Warm Wash" ariaLabel="cue name" onCommit={() => {}} className="truncate" />
      <InlineEditField value="3.0s" ariaLabel="fade time" onCommit={() => {}} className="tabular-nums" />
    </Row>
    <Row>
      <InlineEditField
        value="12.5"
        ariaLabel="cue number"
        formatDisplay={(v) => `Q${v}`}
        onCommit={() => {}}
        className="font-mono tabular-nums"
      />
      <InlineEditField
        value="Band walk-on — followspot pickup stage left"
        ariaLabel="cue name"
        onCommit={() => {}}
        className="truncate"
      />
      <InlineEditField value="0.5s" ariaLabel="fade time" onCommit={() => {}} className="tabular-nums" />
    </Row>
  </div>
)

export const Notes = () => (
  <div className="rounded-md border p-2 text-sm">
    <p className="mb-1 text-[10px] uppercase tracking-wide text-muted-foreground">Notes</p>
    <InlineEditField
      multiline
      rows={3}
      value={'Wait for the MD’s downbeat.\nHouse to half on the applause, not before.'}
      ariaLabel="cue notes"
      onCommit={() => {}}
      className="w-full text-sm"
    />
  </div>
)

export const ReadOnly = () => (
  <div className="space-y-1">
    <Row>
      <InlineEditField
        disabled
        value="7"
        ariaLabel="cue number"
        formatDisplay={(v) => `Q${v}`}
        onCommit={() => {}}
        className="font-mono tabular-nums"
      />
      <InlineEditField disabled value="Blackout" ariaLabel="cue name" onCommit={() => {}} className="truncate" />
      <InlineEditField disabled value="0.0s" ariaLabel="fade time" onCommit={() => {}} className="tabular-nums" />
    </Row>
    <p className="text-xs text-muted-foreground">Locked show: plain text, no hover affordance.</p>
  </div>
)
