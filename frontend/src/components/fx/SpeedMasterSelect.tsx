import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useSpeedMasterLiveQuery } from '../../store/speedMasters'

/**
 * "Which speed master does this effect run on" picker, shared by every FX authoring
 * surface. Reads the live bank itself so consumers only thread `value`/`onChange`.
 *
 * Deals exclusively in **concrete master uuids**: a stored `null` (the pre-masters
 * default) renders as master 1, and choosing "M1" emits master 1's uuid rather than null —
 * the backend treats an explicit master-1 reference identically to the null default, and a
 * concrete uuid is what the update route needs (its `speedMasterUuid` field means
 * "no change" when omitted, so null can't express "back to default" there).
 *
 * Renders nothing until the live bank has arrived — a picker that can't list its options
 * is worse than no picker, and every project has masters within one WS round-trip.
 */
export function SpeedMasterSelect({
  value,
  onChange,
  label = 'Speed Master',
  description,
}: {
  /** The effect's stored master uuid; null/undefined means master 1. */
  value: string | null | undefined
  onChange: (masterUuid: string) => void
  /** Overridden by the wall-clock rate picker, which is the same control for a different field. */
  label?: string
  /** Optional hint below the control — used to explain what a rate master actually does. */
  description?: string
}) {
  const { data: masters } = useSpeedMasterLiveQuery()
  const listable = (masters ?? []).filter((m) => m.uuid != null)
  if (listable.length === 0) return null

  const master1 = listable.find((m) => m.index === 1)
  const effective = value ?? master1?.uuid ?? listable[0].uuid!

  return (
    <div>
      <Label className="text-xs text-muted-foreground mb-1.5 block">{label}</Label>
      <Select value={effective} onValueChange={onChange}>
        <SelectTrigger className="h-8 text-xs">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {listable.map((m) => (
            <SelectItem key={m.uuid} value={m.uuid!} className="text-xs">
              <span>
                M{m.index} · {m.name}
              </span>
              <span className="text-muted-foreground ml-2 tabular-nums">
                {Math.round(m.bpm * 10) / 10} bpm
                {m.index === 1 && ' · default'}
              </span>
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {description && <p className="mt-1 text-[11px] text-muted-foreground">{description}</p>}
    </div>
  )
}
