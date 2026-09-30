import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  LANTERN_FAMILY_LABEL,
  lanternFieldLabel,
  lanternsByFamily,
  type Lantern,
  type LanternIndex,
} from '@/lib/lanterns'

/** The "names none" choice: a Select item cannot carry an empty value. */
const INHERIT = '__inherit__'

interface LanternPickerProps {
  id: string
  /** The lantern named, or null for none. */
  value: string | null | undefined
  onChange: (next: string | null) => void
  lanterns: LanternIndex
  /** What naming none draws: the kind's default for a fixture, the fixture's lantern for a placement. */
  inherited: Lantern | null
  /** How the "names none" option reads — *Default* on a fixture, *Same as the fixture* on a placement. */
  inheritWord: string
  label?: string
  disabled?: boolean
}

/**
 * A lantern from the library (`GET /lanterns`), grouped by family, with the choice of naming none —
 * which draws [inherited]. The option names the lantern and its field, so a Source Four 19° and a
 * 26° can be told apart in the list rather than after the pick.
 */
export function LanternPicker({
  id,
  value,
  onChange,
  lanterns,
  inherited,
  inheritWord,
  label = 'Lantern',
  disabled,
}: LanternPickerProps) {
  // An id this desk's library does not hold (an archive from a newer desk) is kept, and shown.
  const unknown = value != null && !lanterns.byId.has(value)
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id}>{label}</Label>
      <Select
        value={value ?? INHERIT}
        onValueChange={(next) => onChange(next === INHERIT ? null : next)}
        disabled={disabled || lanterns.all.length === 0}
      >
        <SelectTrigger id={id} className="w-full">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={INHERIT}>
            {inheritWord}
            {inherited ? ` · ${inherited.name}` : ''}
          </SelectItem>
          {unknown && <SelectItem value={value}>{value} · not in this desk&apos;s library</SelectItem>}
          {lanternsByFamily(lanterns).map(([family, list]) => (
            <SelectGroup key={family}>
              <SelectLabel>{LANTERN_FAMILY_LABEL[family]}</SelectLabel>
              {list.map((l) => (
                <SelectItem key={l.id} value={l.id}>
                  {l.name}
                  <span className="text-muted-foreground"> · {lanternFieldLabel(l)}</span>
                </SelectItem>
              ))}
            </SelectGroup>
          ))}
        </SelectContent>
      </Select>
    </div>
  )
}
