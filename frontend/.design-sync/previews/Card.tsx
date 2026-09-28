import {
  Badge,
  Button,
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from 'lighting-desk-ui'

export const Default = () => (
  <Card className="w-full max-w-sm">
    <CardHeader>
      <CardTitle>Act 1 — Opening</CardTitle>
      <CardDescription>12 cues · last run Tuesday 19:42</CardDescription>
      <CardAction>
        <Badge variant="secondary">Live</Badge>
      </CardAction>
    </CardHeader>
    <CardContent>
      <p className="text-sm text-muted-foreground">
        House to half, then a slow 8 s fade up on the front wash while the band walks on. Cue 3
        holds until the first downbeat.
      </p>
    </CardContent>
    <CardFooter className="justify-end gap-2">
      <Button variant="outline" size="sm">
        Open
      </Button>
      <Button size="sm">GO</Button>
    </CardFooter>
  </Card>
)

export const TextOnly = () => (
  <Card className="w-full max-w-sm">
    <CardHeader>
      <CardTitle>Speed masters</CardTitle>
      <CardDescription>Named tempo buses that effects follow</CardDescription>
    </CardHeader>
    <CardContent className="space-y-2 text-sm">
      <p>
        Master 1 is the global tempo: every unassigned effect resolves to it and it cannot be
        deleted.
      </p>
      <p className="text-muted-foreground">
        A follower runs at its leader's tempo times a ratio, and its clock is driven by the
        leader's tick, so the two beat together.
      </p>
    </CardContent>
  </Card>
)

export const Grid = () => (
  <div className="grid grid-cols-2 gap-3">
    {[
      ['Front wash', '8 fixtures', 'Warm'],
      ['Movers', '6 fixtures', 'Position'],
      ['Cyc', '4 fixtures', 'Colour'],
      ['Specials', '3 fixtures', 'Beam'],
    ].map(([name, count, family]) => (
      <Card key={name} className="py-4">
        <CardHeader className="px-4">
          <CardTitle className="text-base">{name}</CardTitle>
          <CardDescription>{count}</CardDescription>
          <CardAction>
            <Badge variant="outline">{family}</Badge>
          </CardAction>
        </CardHeader>
      </Card>
    ))}
  </div>
)
