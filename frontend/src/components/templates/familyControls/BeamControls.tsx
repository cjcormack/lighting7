import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { TEMPLATE_EXCLUSIONS, type TemplateIntent } from '@/lib/templateIntent'
import { PercentControl } from './PercentControl'
import type { TemplateValues } from './templateRows'

/** The beam family: four continuous roles as percentages, the prism in three states, and the exclusions named. */
export function BeamControls({
  values,
  onChange,
}: {
  values: TemplateValues
  onChange: (propertyName: string, intent: TemplateIntent | null) => void
}) {
  const prism = values.prism?.kind === 'switch' ? values.prism.on : null

  return (
    <div className="space-y-4">
      {(['zoom', 'focus', 'iris', 'frost'] as const).map((role) => (
        <PercentControl
          key={role}
          label={role[0].toUpperCase() + role.slice(1)}
          value={values[role]}
          onChange={(i) => onChange(role, i)}
        />
      ))}

      {/* Three states, not a switch: a template that says nothing about the prism is different from
          one that says "out", and a two-state control cannot express the first. */}
      <div className="flex items-center justify-between gap-2">
        <Label>Prism</Label>
        <div className="flex items-center gap-1.5">
          <Button
            type="button"
            size="sm"
            variant={prism === true ? 'default' : 'outline'}
            onClick={() => onChange('prism', { kind: 'switch', on: true })}
          >
            In
          </Button>
          <Button
            type="button"
            size="sm"
            variant={prism === false ? 'default' : 'outline'}
            onClick={() => onChange('prism', { kind: 'switch', on: false })}
          >
            Out
          </Button>
          {prism != null && (
            <Button type="button" variant="ghost" size="sm" onClick={() => onChange('prism', null)}>
              Clear
            </Button>
          )}
        </div>
      </div>

      {/* Shown disabled with the reason rather than omitted. An operator looking for gobo needs to
          learn *where* it lives — a recorded look, which names a head and can hold anything that
          head has — not conclude the desk cannot do it. */}
      <div className="space-y-1.5 rounded-md border border-dashed p-2.5">
        <p className="text-[11px] font-medium text-muted-foreground">Not available on a template</p>
        {TEMPLATE_EXCLUSIONS.map((exclusion) => (
          <div key={exclusion.label} className="flex items-start gap-2 opacity-60">
            <span className="text-xs font-medium shrink-0 w-28 truncate">{exclusion.label}</span>
            <span className="text-[11px] text-muted-foreground">{exclusion.reason}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
