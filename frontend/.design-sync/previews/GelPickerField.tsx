import { useEffect, useRef, useState } from 'react'
import { GelPickerField } from 'lighting-desk-ui'

// The field owns its Popover state and exposes no open prop, so the wrapper
// clicks the trigger once after mount to render the list open.
export const OpenPicker = () => {
  const [gel, setGel] = useState<string | null>('L201')
  const hostRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    hostRef.current?.querySelector<HTMLButtonElement>('button[id]')?.click()
  }, [])
  return (
    <div ref={hostRef} className="h-[480px] w-80 p-2">
      <GelPickerField id="gel" value={gel ?? ''} onChange={setGel} />
    </div>
  )
}
