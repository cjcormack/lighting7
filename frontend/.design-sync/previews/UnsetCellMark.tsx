import { UnsetCellMark } from 'lighting-desk-ui'

// Inline tracks: the compiled stylesheet carries only utilities the app uses.
const cols = { display: 'grid', gridTemplateColumns: '6rem repeat(4, 1fr)' } as const

const Header = () => (
  <div style={cols} className="border-b text-[10px] uppercase tracking-wide text-muted-foreground">
    <div className="px-1.5 py-1">Fixture</div>
    <div className="px-1.5 py-1">Int</div>
    <div className="px-1.5 py-1">Colour</div>
    <div className="px-1.5 py-1">Position</div>
    <div className="px-1.5 py-1">Beam</div>
  </div>
)

const Cell = ({ children }: { children: React.ReactNode }) => (
  <div className="h-7 border-l text-xs">{children}</div>
)

const Set = ({ children }: { children: React.ReactNode }) => (
  <span className="flex h-full items-center px-1.5">{children}</span>
)

const Row = ({ name, last, children }: { name: string; last?: boolean; children: React.ReactNode }) => (
  <div style={cols} className={last ? undefined : 'border-b'}>
    <div className="flex h-7 items-center px-1.5 text-xs font-medium">{name}</div>
    {children}
  </div>
)

export const MixedRow = () => (
  <div className="rounded-md border text-sm">
    <Header />
    <Row name="Spot 3">
      <Cell>
        <Set>72%</Set>
      </Cell>
      <Cell>
        <Set>
          <span className="mr-1.5 size-3 rounded-sm border border-black/20" style={{ backgroundColor: '#ff9d4a' }} />
          #FF9D4A
        </Set>
      </Cell>
      <Cell>
        <UnsetCellMark />
      </Cell>
      <Cell>
        <UnsetCellMark />
      </Cell>
    </Row>
    <Row name="Wash L" last>
      <Cell>
        <UnsetCellMark />
      </Cell>
      <Cell>
        <Set>
          <span className="mr-1.5 size-3 rounded-sm border border-black/20" style={{ backgroundColor: '#2b5fd9' }} />
          #2B5FD9
        </Set>
      </Cell>
      <Cell>
        <Set>P 12° · T −8°</Set>
      </Cell>
      <Cell>
        <UnsetCellMark />
      </Cell>
    </Row>
  </div>
)

export const AllUnset = () => (
  <div className="space-y-1">
    <div className="rounded-md border text-sm">
      <Header />
      <Row name="Cyc 1" last>
        <Cell><UnsetCellMark /></Cell>
        <Cell><UnsetCellMark /></Cell>
        <Cell><UnsetCellMark /></Cell>
        <Cell><UnsetCellMark /></Cell>
      </Row>
    </div>
    <p className="text-xs text-muted-foreground">Local scope with nothing busked: Record would take nothing from this row.</p>
  </div>
)
