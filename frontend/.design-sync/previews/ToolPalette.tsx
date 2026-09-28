import { useState } from 'react'
import { ToolPalette } from 'lighting-desk-ui'

type Tool = 'move' | 'note' | 'strikethrough' | 'freetext'

function Palette({ initial, warn, placingLabel }: { initial: Tool; warn: boolean; placingLabel?: string }) {
  const [tool, setTool] = useState<Tool>(initial)
  return (
    <div className="w-full max-w-[440px]">
      <ToolPalette tool={tool} warn={warn} placingLabel={placingLabel} onSelectTool={setTool} />
    </div>
  )
}

// Ordinary chrome on a stopped show, Select active.
export const SelectActive = () => <Palette initial="move" warn={false} />

export const NoteActive = () => <Palette initial="note" warn={false} />

// The cut tool is the destructive one and highlights red when active.
export const CutActive = () => <Palette initial="strikethrough" warn={false} />

// Unlocked mid-show: the bar washes amber and the active tool tints with it.
export const WarnRunning = () => <Palette initial="freetext" warn />

// A cue armed for anchoring shows a targeted prompt instead of the hint.
export const PlacingCue = () => <Palette initial="move" warn placingLabel="Q12" />
