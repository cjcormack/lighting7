import { useMemo } from 'react'
import { formatBpm } from '@/hooks/useBpmDraft'
import { heldTempoIn, tempoMasterLabel } from '@/lib/heldTempo'
import { useProgrammerTempo } from '@/store/programmer'
import { useSpeedMasterLiveQuery } from '@/store/speedMasters'

export interface RecordTempoRowProps {
  projectId: number
  checked: boolean
  onCheckedChange: (checked: boolean) => void
}

/**
 * Record's *Tempo* row (fx-speed plan D10), the Scenery row's twin: absent when no tempo is held,
 * ticked on every open while one is, its hint naming each held master and its tempo. Unticked, the
 * request says `tempo: false` and the cue's *Tempo on GO* is left as it is. The mask and *Selected
 * fixtures only* never narrow it: a tempo names no fixture. A cue's only — a Look records none.
 */
export function RecordTempoRow({ projectId, checked, onCheckedChange }: RecordTempoRowProps) {
  const held = useHeldTempoLabels(projectId)
  if (held.length === 0) return null

  return (
    <div className="space-y-1" data-testid="record-tempo-row">
      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={checked}
          onChange={(e) => onCheckedChange(e.target.checked)}
          className="size-4"
        />
        Tempo
        <span className="text-muted-foreground tabular-nums">({held.length})</span>
      </label>
      <p className="text-xs text-muted-foreground">
        {held.join(' · ')} — the cue&rsquo;s Tempo on GO for these masters.
      </p>
    </div>
  )
}

/** *M2 · Chase 132* for each master the programmer holds in [projectId], in the order first held. */
export function useHeldTempoLabels(projectId: number): string[] {
  const tempo = useProgrammerTempo()
  const { data: bank } = useSpeedMasterLiveQuery()
  return useMemo(
    () =>
      heldTempoIn(tempo, projectId).map((m) => `${tempoMasterLabel(bank, m.speedMasterUuid)} ${formatBpm(m.bpm)}`),
    [bank, projectId, tempo],
  )
}
