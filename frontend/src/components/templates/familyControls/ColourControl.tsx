import { HexColorPicker } from 'react-colorful'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Slider } from '@/components/ui/slider'
import {
  EMITTER_PROPERTIES,
  EMITTER_TINTS,
  WHITE_POLICIES,
  WHITE_POLICY_LABELS,
  templatePropertyFor,
  type TemplateIntent,
  type WhitePolicy,
} from '@/lib/templateIntent'
import { colourPolicyLocked, effectiveColourPolicy, type TemplateValues } from './templateRows'

/**
 * The colour family: a hex with a white/amber policy, plus the three emitters set outright.
 *
 * **Absent is not zero**, which is why each emitter has a switch of its own rather than a slider
 * resting at 0. No row means the template says nothing about that emitter and leaves whatever is
 * under it alone; a row at 0 means drive it to 0. That difference is the whole reason a UV-only
 * template can sit *over* an amber wash instead of replacing it.
 *
 * **An explicit white or amber replaces the policy** rather than joining it — Extract and Additive
 * drive the same emitter byte, so the desk refuses the pair at the write boundary. Said here first,
 * with the buttons disabled and a line explaining it, so a 400 is a backstop rather than the first
 * an operator hears of the rule. UV never conflicts: no policy has ever driven it.
 */
export function ColourControl({
  values,
  onChange,
}: {
  values: TemplateValues
  onChange: (propertyName: string, intent: TemplateIntent | null) => void
}) {
  const value = values.rgbColour
  const current = value?.kind === 'colour' ? value : null
  const hex = current?.hex ?? '#FF9D4A'
  // Seeded from the parsed value, not from a UI default. `parseTemplateIntent` reads a stored colour
  // with no policy token as `rgbonly` — matching every other reader of that string — so defaulting
  // the buttons to `extract` here showed Extract selected for a row that was not, and wrote Extract
  // on the next edit the operator made for some other reason. `effectiveColourPolicy` then collapses
  // it to `rgbonly` while an emitter is set, so the buttons say what will actually be saved.
  const policy = effectiveColourPolicy(values)
  const policyLocked = colourPolicyLocked(values)

  const setColour = (next: Partial<{ hex: string; policy: WhitePolicy }>) =>
    onChange('rgbColour', { kind: 'colour', hex, policy, ...next })

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-2">
        <Label>Colour</Label>
        {current != null && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            aria-label="Clear colour"
            onClick={() => onChange('rgbColour', null)}
          >
            Clear
          </Button>
        )}
      </div>
      <div className="flex items-start gap-3">
        <div className="[&_.react-colorful]:!w-40 [&_.react-colorful]:!h-32">
          <HexColorPicker color={hex} onChange={(next) => setColour({ hex: next })} />
        </div>
        <div className="space-y-2 min-w-0 flex-1">
          <Input
            // Typed as text, so the Value cell's keyboard finds it: Enter in it applies, and it is
            // the field a popover focuses on open (`useEditorKeyboard`'s selector names the type).
            type="text"
            aria-label="Hex colour"
            value={hex.toUpperCase()}
            onChange={(e) => {
              const next = e.target.value.trim()
              if (/^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(next)) {
                setColour({ hex: next })
              }
            }}
            className="font-mono"
          />
          <div
            className="h-8 rounded border border-border/60"
            style={{ background: hex }}
            aria-hidden
          />
        </div>
      </div>

      <div className="space-y-1.5">
        <Label>White / amber handling — on heads that have those channels</Label>
        <div className="flex flex-wrap gap-1.5">
          {WHITE_POLICIES.map((p) => (
            <Button
              key={p}
              type="button"
              size="sm"
              disabled={policyLocked && p !== 'rgbonly'}
              variant={policy === p ? 'default' : 'outline'}
              onClick={() => setColour({ policy: p })}
            >
              {WHITE_POLICY_LABELS[p].label}
            </Button>
          ))}
        </div>
        <p className="text-[11px] text-muted-foreground">
          {policyLocked
            ? 'This template sets an emitter directly, so there is nothing left for the policy to derive — it would drive the same channel.'
            : WHITE_POLICY_LABELS[policy].hint}
        </p>
      </div>

      <div className="space-y-2.5 rounded-md border p-2.5">
        <div className="space-y-0.5">
          <Label>Emitters set directly</Label>
          <p className="text-[11px] text-muted-foreground">
            Switch one on and this template can only be applied to heads that have it — the whole
            template, not just that value.
          </p>
        </div>
        {/* Set / Clear beside the value, and the slider only once set — `PercentControl`'s shape,
            deliberately, rather than `ExtendedChannelSlider`'s. That component always shows a
            slider, and a slider resting at 0 is exactly the reading this control must not give:
            here "off" means the template does not mention the emitter, not that it drives it to 0. */}
        {EMITTER_PROPERTIES.map((name) => {
          const intent = values[name]
          const level = intent?.kind === 'level' ? intent.value : null
          const label = templatePropertyFor(name)?.label ?? name
          return (
            <div key={name} className="space-y-1">
              <div className="flex items-center justify-between gap-2">
                <span className="flex items-center gap-1.5 text-xs">
                  <span
                    className="inline-block size-2 rounded-full border border-border"
                    style={{ backgroundColor: EMITTER_TINTS[name] }}
                    aria-hidden
                  />
                  {label}
                </span>
                <div className="flex items-center gap-2">
                  <span className="font-mono text-xs tabular-nums text-muted-foreground">
                    {level ?? '—'}
                  </span>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    // Named, not bare: three "Set" buttons in a row are indistinguishable to a
                    // screen reader, and one of them sits beside the colour's own Clear.
                    aria-label={`${level == null ? 'Set' : 'Clear'} ${label}`}
                    onClick={() =>
                      onChange(name, level == null ? { kind: 'level', value: 255 } : null)
                    }
                  >
                    {level == null ? 'Set' : 'Clear'}
                  </Button>
                </div>
              </div>
              {level != null && (
                <Slider
                  aria-label={label}
                  min={0}
                  max={255}
                  step={1}
                  value={[level]}
                  onValueChange={([next]) => onChange(name, { kind: 'level', value: next })}
                />
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
