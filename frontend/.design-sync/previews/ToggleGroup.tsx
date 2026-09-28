import { ToggleGroup, ToggleGroupItem, Label } from 'lighting-desk-ui'
import { Sun, Palette, Move, Aperture } from 'lucide-react'

export const Single = () => (
  <div className="grid gap-2">
    <Label>Beat division</Label>
    <ToggleGroup type="single" defaultValue="1">
      <ToggleGroupItem value="1/4">1/4</ToggleGroupItem>
      <ToggleGroupItem value="1/2">1/2</ToggleGroupItem>
      <ToggleGroupItem value="1">1</ToggleGroupItem>
      <ToggleGroupItem value="2">2</ToggleGroupItem>
      <ToggleGroupItem value="4">4</ToggleGroupItem>
    </ToggleGroup>
  </div>
)

export const Multiple = () => (
  <div className="grid gap-2">
    <Label>Record mask</Label>
    <ToggleGroup type="multiple" defaultValue={['intensity', 'colour']}>
      <ToggleGroupItem value="intensity">
        <Sun className="mr-1.5 size-3.5" />
        Intensity
      </ToggleGroupItem>
      <ToggleGroupItem value="colour">
        <Palette className="mr-1.5 size-3.5" />
        Colour
      </ToggleGroupItem>
      <ToggleGroupItem value="position">
        <Move className="mr-1.5 size-3.5" />
        Position
      </ToggleGroupItem>
      <ToggleGroupItem value="beam">
        <Aperture className="mr-1.5 size-3.5" />
        Beam
      </ToggleGroupItem>
    </ToggleGroup>
  </div>
)

export const Sizes = () => (
  <div className="grid gap-3">
    <ToggleGroup type="single" size="sm" defaultValue="mine">
      <ToggleGroupItem value="mine">Keep mine</ToggleGroupItem>
      <ToggleGroupItem value="theirs">Take theirs</ToggleGroupItem>
    </ToggleGroup>
    <ToggleGroup type="single" defaultValue="mine">
      <ToggleGroupItem value="mine">Keep mine</ToggleGroupItem>
      <ToggleGroupItem value="theirs">Take theirs</ToggleGroupItem>
    </ToggleGroup>
    <ToggleGroup type="single" size="lg" defaultValue="mine">
      <ToggleGroupItem value="mine">Keep mine</ToggleGroupItem>
      <ToggleGroupItem value="theirs">Take theirs</ToggleGroupItem>
    </ToggleGroup>
  </div>
)

export const Disabled = () => (
  <div className="grid gap-2">
    <Label>Usage (follower — set by its leader)</Label>
    <ToggleGroup type="single" defaultValue="colour" disabled>
      <ToggleGroupItem value="dimmer">Dimmer</ToggleGroupItem>
      <ToggleGroupItem value="colour">Colour</ToggleGroupItem>
      <ToggleGroupItem value="position">Position</ToggleGroupItem>
    </ToggleGroup>
  </div>
)
