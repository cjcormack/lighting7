import {
  Select,
  SelectTrigger,
  SelectValue,
  SelectContent,
  SelectGroup,
  SelectLabel,
  SelectItem,
  SelectSeparator,
  Label,
} from 'lighting-desk-ui'

export const Open = () => (
  <div className="grid w-64 gap-1.5 pb-72">
    <Label htmlFor="speed-master">Speed master</Label>
    <Select defaultOpen defaultValue="m2">
      <SelectTrigger id="speed-master" className="w-64">
        <SelectValue placeholder="Pick a master" />
      </SelectTrigger>
      <SelectContent position="popper">
        <SelectGroup>
          <SelectLabel>Global</SelectLabel>
          <SelectItem value="m1">Master 1 — global tempo</SelectItem>
        </SelectGroup>
        <SelectSeparator />
        <SelectGroup>
          <SelectLabel>Named masters</SelectLabel>
          <SelectItem value="m2">Master 2 — Chorus (124 BPM)</SelectItem>
          <SelectItem value="m3">Master 3 — Ballad (72 BPM)</SelectItem>
          <SelectItem value="m4" disabled>
            Master 4 — follows M2 at 1/2
          </SelectItem>
        </SelectGroup>
      </SelectContent>
    </Select>
  </div>
)
