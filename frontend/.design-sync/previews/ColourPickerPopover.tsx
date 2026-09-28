import { useEffect, useRef, useState } from 'react'
import { ColourPickerPopover } from 'lighting-desk-ui'

// The component owns its Popover open state and exposes no open prop, so the
// wrapper clicks its own swatch trigger once after mount to render it open.
// An RGBWA wash head: RGB set to an amber, the white and amber emitters idle.
export const OpenPicker = () => {
  const [rgb, setRgb] = useState({ r: 255, g: 157, b: 74, w: 0, a: 64 })
  const swatchRef = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    swatchRef.current?.click()
  }, [])
  const combined = `rgb(${rgb.r}, ${rgb.g}, ${rgb.b})`
  return (
    <div className="flex h-[500px] flex-col items-start gap-2 p-2">
      <span className="text-xs text-muted-foreground">Front wash 1 · Colour</span>
      <ColourPickerPopover
        r={rgb.r}
        g={rgb.g}
        b={rgb.b}
        w={rgb.w}
        a={rgb.a}
        combinedCss={combined}
        hasWhiteChannel
        hasAmberChannel
        hasUvChannel={false}
        onColourChange={(r, g, b, w, a) => setRgb({ r, g, b, w: w ?? 0, a: a ?? 0 })}
      >
        <button
          ref={swatchRef}
          type="button"
          aria-label="Edit colour"
          className="size-9 rounded-md border border-border shadow-sm"
          style={{ backgroundColor: combined }}
        />
      </ColourPickerPopover>
    </div>
  )
}
