import { useEffect, useState } from 'react'

/**
 * Click-to-type editing for a live BPM readout.
 *
 * The value it edits is being pushed at from the server the whole time — a tap on another
 * surface, or another operator's tile — so while the field is dirty the pushed value is
 * ignored rather than yanking the field out from under the typing. Enter or blur commits,
 * Escape reverts.
 *
 * @param identity  Whatever identifies the thing being edited (a master's uuid). When it
 *                  changes the draft is abandoned: committing a half-typed tempo to a
 *                  *different* master is worse than losing the keystrokes.
 * @param onCommit  Called with the parsed BPM. Never called for an unparseable draft.
 */
export function useBpmDraft(identity: string | null, onCommit: (bpm: number) => void) {
  const [draft, setDraft] = useState<string | null>(null)

  useEffect(() => {
    setDraft(null)
  }, [identity])

  const commit = () => {
    if (draft == null) return
    const parsed = Number(draft)
    if (Number.isFinite(parsed) && parsed > 0) {
      onCommit(parsed)
    }
    setDraft(null)
  }

  return {
    /** True while the operator is typing — render an input, and ignore server pushes. */
    editing: draft != null,
    draft: draft ?? '',
    /** Open the editor seeded with the current value, rounded the way it is displayed. */
    start: (currentBpm: number) => setDraft(String(Math.round(currentBpm * 10) / 10)),
    change: setDraft,
    commit,
    cancel: () => setDraft(null),
    onKeyDown: (e: { key: string }) => {
      if (e.key === 'Enter') commit()
      if (e.key === 'Escape') setDraft(null)
    },
  }
}

/** Display rounding for a BPM readout — one decimal place, no trailing zero. */
export function formatBpm(bpm: number): number {
  return Math.round(bpm * 10) / 10
}
