import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { parseNullableNumber } from '@/lib/utils'

export function FieldGroup({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1.5">
      <Label>{label}</Label>
      <div className="grid grid-cols-3 gap-2">{children}</div>
    </div>
  )
}

export function NumberField({
  id,
  label,
  value,
  onChange,
  min,
}: {
  id: string
  label: string
  value: number | null
  onChange: (v: number | null) => void
  min?: number
}) {
  return (
    <div className="space-y-1">
      <Label htmlFor={id} className="text-xs text-muted-foreground font-normal">
        {label}
      </Label>
      <Input
        id={id}
        type="number"
        min={min}
        value={value ?? ''}
        onChange={(e) => onChange(parseNullableNumber(e.target.value))}
        onFocus={(e) => e.target.select()}
      />
    </div>
  )
}
