import { useEffect, useRef, useState } from 'react'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'

/**
 * A `MODEL` object's parts as JSON (lighting7 `models/sceneModel.kt` is the vocabulary): the form's
 * one field for them, since a model is made through `set_scene` — another Claude session writing the
 * parts — and this is where the operator reads or corrects one. The text is the field's own while it
 * does not parse, and [onInvalid] says so, so a save never sends parts the operator can see are not
 * the ones in the box. The desk checks the parts themselves; its problems are drawn under the box.
 */
export function ModelPartsField({
  id,
  parts,
  errors,
  onChange,
  onInvalid,
}: {
  id: string
  parts: unknown
  errors: readonly string[]
  onChange: (parts: unknown[]) => void
  onInvalid: (invalid: boolean) => void
}) {
  const [text, setText] = useState(() => format(parts))
  const [parseError, setParseError] = useState<string | null>(null)
  // The parts this field last wrote, so a write from elsewhere (the shape's starter part) resets the box.
  const sent = useRef(JSON.stringify(parts ?? null))
  useEffect(() => {
    const next = JSON.stringify(parts ?? null)
    if (next === sent.current) return
    sent.current = next
    setText(format(parts))
    setParseError(null)
    onInvalid(false)
  }, [parts, onInvalid])

  // The flag is the form's: an unmounted box (the shape turned away from MODEL) holds nothing to refuse.
  useEffect(() => () => onInvalid(false), [onInvalid])

  const count = Array.isArray(parts) ? parts.length : 0
  const handle = (value: string) => {
    setText(value)
    let parsed: unknown
    try {
      parsed = JSON.parse(value)
    } catch (e) {
      setParseError(`Not JSON: ${(e as Error).message}`)
      onInvalid(true)
      return
    }
    if (!Array.isArray(parsed)) {
      setParseError('Parts must be a JSON array: [{"shape": "BOX", …}, …]')
      onInvalid(true)
      return
    }
    setParseError(null)
    onInvalid(false)
    sent.current = JSON.stringify(parsed)
    onChange(parsed)
  }

  const shown = parseError != null ? [parseError] : errors
  return (
    <div className="min-w-0 space-y-1">
      <Label htmlFor={id} className="text-xs font-normal text-muted-foreground">
        Parts ({count === 1 ? '1 part' : `${count} parts`}, JSON)
      </Label>
      <Textarea
        id={id}
        value={text}
        onChange={(e) => handle(e.target.value)}
        spellCheck={false}
        rows={10}
        className="font-mono text-[11px] leading-snug"
        aria-invalid={shown.length > 0 || undefined}
      />
      {shown.length > 0 && (
        <div className="space-y-0.5" role="alert">
          {shown.map((m) => (
            <p key={m} className="text-[11px] leading-snug text-destructive">
              {m}
            </p>
          ))}
        </div>
      )}
      <p className="text-[11px] leading-snug text-muted-foreground">
        Boxes, cylinders and cones, spheres, extruded outlines and lathed profiles, in the model&apos;s own units — fitted
        to the element&apos;s width, depth and height. A model is usually made through set_scene; get_scene reads it back.
      </p>
    </div>
  )
}

function format(parts: unknown): string {
  return JSON.stringify(Array.isArray(parts) ? parts : [], null, 2)
}
