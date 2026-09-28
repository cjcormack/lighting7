import {
  Badge,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableFooter,
  TableHead,
  TableHeader,
  TableRow,
} from 'lighting-desk-ui'

// A table is the desk's list surface: the cue list in a stack, the fixture
// list, a universe's patch. Header, body rows, a caption and a footer, with
// numeric columns right-aligned and status as a badge.
export const CueList = () => (
  <Table>
    <TableCaption>Act 1 — Opening · 6 cues · 2 running effects</TableCaption>
    <TableHeader>
      <TableRow>
        <TableHead className="w-20">Cue #</TableHead>
        <TableHead>Name</TableHead>
        <TableHead className="w-24 text-right">Fade (s)</TableHead>
        <TableHead className="w-28">Status</TableHead>
      </TableRow>
    </TableHeader>
    <TableBody>
      <TableRow>
        <TableCell className="font-medium tabular-nums">1</TableCell>
        <TableCell>Preshow</TableCell>
        <TableCell className="text-right tabular-nums">10</TableCell>
        <TableCell>
          <span className="text-muted-foreground">Done</span>
        </TableCell>
      </TableRow>
      <TableRow>
        <TableCell className="font-medium tabular-nums">2</TableCell>
        <TableCell>House to half</TableCell>
        <TableCell className="text-right tabular-nums">5</TableCell>
        <TableCell>
          <span className="text-muted-foreground">Done</span>
        </TableCell>
      </TableRow>
      <TableRow data-state="selected">
        <TableCell className="font-medium tabular-nums">3</TableCell>
        <TableCell>Band walk-on</TableCell>
        <TableCell className="text-right tabular-nums">3</TableCell>
        <TableCell>
          <Badge>Live</Badge>
        </TableCell>
      </TableRow>
      <TableRow>
        <TableCell className="font-medium tabular-nums">3.5</TableCell>
        <TableCell>Drummer count-in</TableCell>
        <TableCell className="text-right tabular-nums">0</TableCell>
        <TableCell>
          <Badge variant="outline">Next</Badge>
        </TableCell>
      </TableRow>
      <TableRow>
        <TableCell className="font-medium tabular-nums">4</TableCell>
        <TableCell>Chorus colour chase</TableCell>
        <TableCell className="text-right tabular-nums">1.5</TableCell>
        <TableCell>
          <span className="text-muted-foreground">—</span>
        </TableCell>
      </TableRow>
      <TableRow>
        <TableCell className="text-muted-foreground font-medium tabular-nums">5</TableCell>
        <TableCell>Blackout</TableCell>
        <TableCell className="text-right tabular-nums">2</TableCell>
        <TableCell>
          <span className="text-muted-foreground">—</span>
        </TableCell>
      </TableRow>
    </TableBody>
    <TableFooter>
      <TableRow>
        <TableCell colSpan={2}>Total fade time</TableCell>
        <TableCell className="text-right tabular-nums">21.5</TableCell>
        <TableCell />
      </TableRow>
    </TableFooter>
  </Table>
)

// A patch table: fixtures against their DMX universe and start address.
// No footer, no caption — a plain data grid.
export const FixturePatch = () => (
  <Table>
    <TableHeader>
      <TableRow>
        <TableHead>Fixture</TableHead>
        <TableHead>Model</TableHead>
        <TableHead className="w-24 text-right">Universe</TableHead>
        <TableHead className="w-24 text-right">Address</TableHead>
        <TableHead className="w-20 text-right">Ch</TableHead>
      </TableRow>
    </TableHeader>
    <TableBody>
      <TableRow>
        <TableCell className="font-medium">Front wash 1</TableCell>
        <TableCell>Martin MAC Aura XB</TableCell>
        <TableCell className="text-right tabular-nums">1</TableCell>
        <TableCell className="text-right tabular-nums">1</TableCell>
        <TableCell className="text-right tabular-nums">25</TableCell>
      </TableRow>
      <TableRow>
        <TableCell className="font-medium">Front wash 2</TableCell>
        <TableCell>Martin MAC Aura XB</TableCell>
        <TableCell className="text-right tabular-nums">1</TableCell>
        <TableCell className="text-right tabular-nums">26</TableCell>
        <TableCell className="text-right tabular-nums">25</TableCell>
      </TableRow>
      <TableRow>
        <TableCell className="font-medium">Spot SL</TableCell>
        <TableCell>Robe Pointe</TableCell>
        <TableCell className="text-right tabular-nums">2</TableCell>
        <TableCell className="text-right tabular-nums">1</TableCell>
        <TableCell className="text-right tabular-nums">24</TableCell>
      </TableRow>
      <TableRow>
        <TableCell className="font-medium">Cyc 1–4</TableCell>
        <TableCell>ETC Source Four LED S2</TableCell>
        <TableCell className="text-right tabular-nums">2</TableCell>
        <TableCell className="text-right tabular-nums">101</TableCell>
        <TableCell className="text-right tabular-nums">28</TableCell>
      </TableRow>
    </TableBody>
  </Table>
)
