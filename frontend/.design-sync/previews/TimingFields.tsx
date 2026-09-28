import { useState } from 'react'
import { TimingFields } from 'lighting-desk-ui'

type TimingValues = {
  delayMs?: number | null
  intervalMs?: number | null
  randomWindowMs?: number | null
}

const Demo = ({ initial }: { initial: TimingValues }) => {
  const [values, setValues] = useState<TimingValues>(initial)
  return (
    <div className="w-72">
      <TimingFields values={values} onChange={setValues} />
    </div>
  )
}

export const Empty = () => <Demo initial={{}} />

export const DelayOnly = () => <Demo initial={{ delayMs: 2000 }} />

export const Recurring = () => <Demo initial={{ delayMs: 500, intervalMs: 5000, randomWindowMs: 1500 }} />
