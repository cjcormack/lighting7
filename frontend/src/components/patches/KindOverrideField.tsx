import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { FIXTURE_KINDS, FIXTURE_KIND_LABEL, isFixtureKind } from '@/store/fixtures'

interface KindOverrideFieldProps {
  id: string
  value: string | null
  onChange: (next: string | null) => void
  /**
   * The lantern the kind is derived from, when one is named (stage-view plan session 7): the
   * picker then shows that kind, disabled, and says so — the desk refuses another kind beside a
   * lantern. Clearing the lantern gives the choice back.
   */
  lockedBy?: string | null
}

// Sentinel for the "no override — inherit from fixture type" choice. Empty
// string isn't legal as a SelectItem value, so a named token is clearer.
const DEFAULT_VALUE = '__default__'

const SELECTABLE_KINDS = FIXTURE_KINDS.filter((k) => k !== 'GENERIC')

export function KindOverrideField({ id, value, onChange, lockedBy }: KindOverrideFieldProps) {
  const selectValue: string = isFixtureKind(value) ? value : DEFAULT_VALUE
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id}>3D shape</Label>
      <Select
        disabled={lockedBy != null}
        value={selectValue}
        onValueChange={(next) => onChange(next === DEFAULT_VALUE ? null : next)}
      >
        <SelectTrigger id={id}>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={DEFAULT_VALUE}>Default (generic)</SelectItem>
          {SELECTABLE_KINDS.map((kind) => (
            <SelectItem key={kind} value={kind}>
              {FIXTURE_KIND_LABEL[kind]}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <p className="text-xs text-muted-foreground">
        {lockedBy != null
          ? `Set by the lantern (${lockedBy}); clear the lantern to choose another.`
          : 'Controls how this patch is drawn in the Stage view.'}
      </p>
    </div>
  )
}
