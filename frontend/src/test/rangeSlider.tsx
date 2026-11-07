/**
 * A stand-in for `@/components/ui/slider` in tests: Radix's slider reads the pointer against a track
 * jsdom lays out at zero, so a drag is driven through a range input with the same two callbacks —
 * `change` is a move (`onValueChange`), `pointerUp` the release (`onValueCommit`).
 *
 * `vi.mock('@/components/ui/slider', async () => ({ Slider: (await import('@/test/rangeSlider')).RangeSlider }))`
 */
export function RangeSlider({
  min = 0,
  max = 100,
  step = 1,
  value,
  disabled,
  onValueChange,
  onValueCommit,
  onPointerDownCapture,
  'aria-label': label,
}: {
  min?: number
  max?: number
  step?: number
  value?: number[]
  disabled?: boolean
  onValueChange?: (v: number[]) => void
  onValueCommit?: (v: number[]) => void
  onPointerDownCapture?: (event: { button: number }) => void
  'aria-label'?: string
}) {
  return (
    <input
      type="range"
      aria-label={label}
      min={min}
      max={max}
      step={step}
      value={value?.[0] ?? min}
      disabled={disabled}
      onChange={(e) => onValueChange?.([Number(e.target.value)])}
      onPointerUp={(e) => onValueCommit?.([Number((e.target as HTMLInputElement).value)])}
      onPointerDownCapture={onPointerDownCapture && ((e) => onPointerDownCapture({ button: e.button }))}
    />
  )
}
