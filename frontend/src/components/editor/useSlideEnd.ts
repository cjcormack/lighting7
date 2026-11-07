import { useWindowRelease } from './useWindowRelease'

/**
 * A slider's gesture end, for a recorder (programmer-fixes plan D17): spread on a `Slider`. Radix's
 * `onValueCommit` ends a drag and a key's step, but skips a drag that ends where it began, so the
 * press's window release ends it too — else what the drag staged would wait, to be sent out of
 * order by whatever ends next. [onEnd] runs twice for most drags; the second finds nothing staged.
 */
export function useSlideEnd(onEnd: () => void): {
  onValueCommit: () => void
  onPointerDownCapture: (event: { button: number }) => void
} {
  const hold = useWindowRelease(onEnd)
  return { onValueCommit: onEnd, onPointerDownCapture: hold }
}
