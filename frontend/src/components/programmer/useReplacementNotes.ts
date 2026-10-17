import { useCallback, useMemo, useState } from 'react'
import { useProgrammerAppliedQuery } from '@/store/programmer'
import type { ProgrammerAppliedSource } from '@/api/programmerWsApi'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { appliedSignature, replacementNotes } from '@/lib/replacementNotes'
import { cellKeysOf, headName } from '@/lib/headName'

const NO_APPLIED: readonly ProgrammerAppliedSource[] = []
const NO_NOTES: ReadonlyMap<string, string> = new Map()

/**
 * The rows' *lost Hex 3–4 to Amber* (apply-model plan §4.6): the last applied-state transition
 * read as replacements, held until the next frame that changes anything — so a frame that moves
 * nothing leaves the note up, and the next press or release takes it down. Never stored, and the
 * first frame this window sees says nothing: there is no "before" to compare it with.
 */
export function useReplacementNotes(): ReadonlyMap<string, string> {
  const { data } = useProgrammerAppliedQuery()
  const { fixtures } = useFixtureLookup()
  const applied = data ?? NO_APPLIED
  const signature = useMemo(() => appliedSignature(applied), [applied])
  const [held, setHeld] = useState<{
    signature: string | null
    applied: readonly ProgrammerAppliedSource[]
    notes: ReadonlyMap<string, string>
  }>({ signature: data != null ? signature : null, applied, notes: NO_NOTES })

  const nameOf = useCallback((key: string) => headName(fixtures ?? [], key), [fixtures])
  const cellsOf = useCallback((key: string) => cellKeysOf(fixtures ?? [], key), [fixtures])

  if (data != null && held.signature !== signature) {
    const notes = held.signature == null ? NO_NOTES : replacementNotes(held.applied, applied, nameOf, cellsOf)
    setHeld({ signature, applied, notes })
    return notes
  }
  return held.notes
}
