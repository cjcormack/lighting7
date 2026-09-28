import { useCallback, useState } from 'react'

/**
 * The live numeric field's draft text — the "an emptied box must not commit" rule, written once.
 *
 * Every numeric field on this desk writes as it is typed, because the value it holds is a live
 * channel and the operator judges it against the rig. That makes one keystroke dangerous:
 * `Number('')` is 0, so a field that committed on its way to being retyped would black the
 * channel out between the last digit deleted and the first one typed. The guard is to keep the
 * operator's text locally while it is being edited, commit only what parses, and drop the text on
 * blur so the field falls back to whatever the desk actually holds.
 *
 * That rule was written twice — inline in `SliderCell`'s popover field and again in
 * `ChannelNumberInput` — and the two had already drifted on **where the value is clamped**. So
 * clamping is deliberately *not* here: this hook parses, and the caller clamps to its own range,
 * because a channel byte's range is 0–255 while a slider cell's comes from the resolution's
 * descriptor. One place for the trap, each caller's own answer for its bounds.
 *
 * [display] is what the field shows when there is no draft — the caller formats it, since a
 * cell shows `value.max` and a colour channel shows a rounded byte.
 */
export function useNumberFieldDraft(
  display: string,
  onNumber: (parsed: number) => void,
): {
  /** Bind to the input's `value`. */
  value: string
  /** Bind to `onChange`. */
  onChange: (raw: string) => void
  /** Bind to `onBlur`. */
  onBlur: () => void
  /** Drop the draft without a blur — for a field whose editor reopens on the same mounted node. */
  reset: () => void
} {
  const [text, setText] = useState<string | null>(null)

  const onChange = useCallback(
    (raw: string) => {
      setText(raw)
      if (raw.trim() === '') return
      const parsed = Number(raw)
      if (!Number.isFinite(parsed)) return
      onNumber(parsed)
    },
    [onNumber],
  )

  const reset = useCallback(() => setText(null), [])

  return { value: text ?? display, onChange, onBlur: reset, reset }
}
