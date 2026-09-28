import { useLayoutEffect, useRef, useState } from 'react'
import { FloatingSelectionToolbar } from 'lighting-desk-ui'

// The bar portals to body at a fixed client-space point, so the preview
// measures a highlighted "selection" in some script text and anchors the bar to
// its top-centre — which is what the page does with the live text selection.
export const AboveSelection = () => {
  const selRef = useRef<HTMLSpanElement>(null)
  const [anchor, setAnchor] = useState<{ x: number; y: number } | null>(null)
  useLayoutEffect(() => {
    const el = selRef.current
    if (!el) return
    const measure = () => {
      const r = el.getBoundingClientRect()
      setAnchor({ x: r.left + r.width / 2, y: r.top })
    }
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(document.documentElement)
    return () => ro.disconnect()
  }, [])
  return (
    // Width, top padding, serif face and the selection tint are inline: the shipped
    // stylesheet only carries utilities the app uses, and none of these are among them.
    <div
      className="rounded-md border bg-card p-4 text-sm text-card-foreground"
      style={{ width: 420, paddingTop: 64, fontFamily: 'Georgia, serif', lineHeight: '28px' }}
    >
      <p className="mb-1 text-center text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        Act 1, Scene 2
      </p>
      <p>
        <span className="font-semibold">HAMLET.</span> O, that this too too solid flesh would melt,{' '}
        <span ref={selRef} style={{ background: 'rgba(14, 165, 233, 0.3)' }}>
          Thaw and resolve itself into a dew!
        </span>{' '}
        Or that the Everlasting had not fix'd his canon 'gainst self-slaughter!
      </p>
      {anchor && (
        <FloatingSelectionToolbar anchor={anchor} onAnchor={() => {}} onCut={() => {}} onNote={() => {}} />
      )}
    </div>
  )
}
