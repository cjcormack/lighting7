import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { MAX_FIXTURE_LENGTH_M, MIN_FIXTURE_LENGTH_M } from '@/api/patchApi'
import { parseNullableNumber } from '@/lib/utils'

/** Whether a length is one the desk will store: none (the default), or within its bounds. */
export function fixtureLengthValid(value: number | null | undefined): boolean {
  return (
    value == null ||
    (Number.isFinite(value) && value >= MIN_FIXTURE_LENGTH_M && value <= MAX_FIXTURE_LENGTH_M)
  )
}

/** A length as the field shows it: metres, trimmed of trailing zeros. */
export function formatLengthM(value: number): string {
  return `${Number(value.toFixed(2))} m`
}

interface FixtureLengthFieldProps {
  id: string
  value: number | null
  onChange: (next: number | null) => void
  /** What is drawn while the field is empty — the type's default, or the fixture's own length for
   *  a segment — named in the placeholder so an empty field says what it means. */
  fallbackM: number | null
  /** Describes the fallback in the placeholder: "default" for the fixture, "fixture's" for a segment. */
  fallbackLabel: string
}

/**
 * The installed length of a fixture whose length is set per install — a lightstrip cut to its run
 * (`FixtureTypeInfo.acceptsLength`). Empty is no length of its own, and draws `fallbackM`.
 *
 * Controlled like the form's other number fields (`NumberField`). A value out of the desk's bounds
 * is kept rather than clamped — clamping would rewrite a number mid-keystroke — and reported under
 * the field; the form refuses to save it, as the desk would.
 */
export function FixtureLengthField({ id, value, onChange, fallbackM, fallbackLabel }: FixtureLengthFieldProps) {
  const invalid = !fixtureLengthValid(value)

  return (
    <div className="space-y-1.5">
      <Label htmlFor={id}>Length</Label>
      <div className="flex items-center gap-1.5">
        <Input
          id={id}
          type="number"
          inputMode="decimal"
          step={0.1}
          min={MIN_FIXTURE_LENGTH_M}
          max={MAX_FIXTURE_LENGTH_M}
          value={value ?? ''}
          placeholder={fallbackM == null ? '—' : `${formatLengthM(fallbackM)} (${fallbackLabel})`}
          className="w-40 font-mono"
          aria-invalid={invalid || undefined}
          onChange={(e) => onChange(parseNullableNumber(e.target.value))}
          onFocus={(e) => e.currentTarget.select()}
        />
        <span className="text-xs font-mono text-muted-foreground/60">m</span>
      </div>
      {invalid && (
        <p className="text-xs text-destructive">
          Between {MIN_FIXTURE_LENGTH_M} and {MAX_FIXTURE_LENGTH_M} m, or empty for the {fallbackLabel} length
        </p>
      )}
    </div>
  )
}
