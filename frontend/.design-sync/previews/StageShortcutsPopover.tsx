import { useEffect, useRef } from 'react'
import { StageShortcutsPopover } from 'lighting-desk-ui'

// No props: the component owns its trigger and Popover state, so the wrapper
// clicks the keyboard button once after mount to render the list open. The
// popover aligns to the trigger's end, so the trigger sits at the right edge as
// it does in the stage editor's toolbar.
export const Open = () => {
  const hostRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    hostRef.current?.querySelector<HTMLButtonElement>('button')?.click()
  }, [])
  return (
    <div ref={hostRef} className="flex h-[440px] w-[560px] items-start justify-end gap-2 p-2">
      <span className="self-center text-xs text-muted-foreground">Stage plot · Plan</span>
      <StageShortcutsPopover />
    </div>
  )
}
