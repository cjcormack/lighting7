import { useState } from 'react'
import { BeamAngleField } from 'lighting-desk-ui'

// Numeric input plus preset chips, with a little beam-cone illustration on the left.
// Controlled so the chips move the cone.
function Field({ initial }: { initial: number | null }) {
  const [value, setValue] = useState<number | null>(initial)
  return (
    <div className="w-[400px]">
      <BeamAngleField id="beam" value={value as number} onChange={setValue} />
    </div>
  )
}

export const PresetMedium = () => <Field initial={36} />

export const CustomNarrow = () => <Field initial={19} />

export const Flood = () => <Field initial={70} />

export const Unset = () => <Field initial={null} />
