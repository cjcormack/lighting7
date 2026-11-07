import { X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { formatBpm, useBpmDraft } from '@/hooks/useBpmDraft'
import { heldTempoIn, listedTempoIn, tempoMasterLabel } from '@/lib/heldTempo'
import { cn } from '@/lib/utils'
import { programmerClearTempo, programmerSetTempo, useProgrammerTempo } from '@/store/programmer'
import { useSpeedMasterLiveQuery } from '@/store/speedMasters'
import { useIsDeskConnected } from '@/store/status'
import type { ProgrammerTempoEntry } from '@/api/programmerWsApi'

/** How many masters the programmer holds in [projectId] — what Clear will release and Record write. */
export function useHeldTempoCount(projectId: number): number {
  return heldTempoIn(useProgrammerTempo(), projectId).length
}

/**
 * **The programmer's Tempo rows** (fx-speed plan D8, D11): one row per master it lists, its tempo
 * typed in place (`programmer.setTempo`) and ✕ releasing the hold (`programmer.clearTempo`). In Blind
 * a hold not yet on stage reads *staged*, and a hold released in Blind that still runs reads
 * *releasing*, with nothing left to release. Draws nothing while nothing is listed.
 */
export function ProgrammerTempoList({ projectId, className }: { projectId: number; className?: string }) {
  const entries = listedTempoIn(useProgrammerTempo(), projectId)
  const { data: bank } = useSpeedMasterLiveQuery()
  const connected = useIsDeskConnected()
  if (entries.length === 0) return null
  return (
    <section
      aria-label="Held tempo"
      data-programmer-tempo
      className={cn('rounded-lg border bg-card px-2 py-1', className)}
    >
      <h3 className="px-1 py-0.5 text-[10px] font-bold uppercase tracking-[0.08em] text-muted-foreground">Tempo</h3>
      <ul className="flex flex-col">
        {entries.map((entry) => (
          <TempoRow
            key={entry.speedMasterUuid}
            entry={entry}
            label={tempoMasterLabel(bank, entry.speedMasterUuid)}
            connected={connected}
          />
        ))}
      </ul>
    </section>
  )
}

function TempoRow({ entry, label, connected }: { entry: ProgrammerTempoEntry; label: string; connected: boolean }) {
  const { speedMasterUuid } = entry
  const { editing, draft, start, change, commit, onKeyDown } = useBpmDraft(speedMasterUuid, (bpm) =>
    programmerSetTempo(speedMasterUuid, bpm),
  )
  const releasing = entry.released === true
  const staged = entry.staged === true && !releasing
  return (
    <li
      data-tempo-master={speedMasterUuid}
      data-staged={staged || undefined}
      data-releasing={releasing || undefined}
      className="flex min-h-9 items-center gap-2 px-1 pointer-coarse:min-h-11"
    >
      <span className="min-w-0 flex-1 truncate text-sm font-medium">{label}</span>
      {staged && (
        <span
          className="shrink-0 text-[10px] font-semibold uppercase tracking-wide text-amber-700 dark:text-amber-300"
          title="Blind: this tempo lands when Blind goes off"
        >
          staged
        </span>
      )}
      {releasing && (
        <span
          className="shrink-0 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground"
          title="Blind: released, still running until Blind goes off"
        >
          releasing
        </span>
      )}
      {editing ? (
        <input
          autoFocus
          inputMode="decimal"
          value={draft}
          onChange={(e) => change(e.target.value)}
          onBlur={commit}
          onKeyDown={onKeyDown}
          aria-label={`${label} tempo`}
          className="w-[6ch] border-b border-primary bg-transparent text-right font-mono text-sm font-bold tabular-nums outline-none"
        />
      ) : (
        <button
          type="button"
          disabled={!connected}
          onClick={() => start(entry.bpm)}
          aria-label={`${label} tempo ${formatBpm(entry.bpm)}`}
          title={releasing ? 'Type a tempo to hold it again' : 'Type a tempo'}
          className={cn(
            'min-w-[6ch] rounded px-1 text-right font-mono text-sm font-bold tabular-nums transition-colors hover:text-primary disabled:hover:text-foreground pointer-coarse:min-h-11',
            releasing && 'text-muted-foreground',
          )}
        >
          {formatBpm(entry.bpm)}
        </button>
      )}
      <span className="text-[10px] text-muted-foreground">BPM</span>
      {releasing ? (
        <span className="size-7 shrink-0 pointer-coarse:size-11" aria-hidden />
      ) : (
        <Button
          type="button"
          variant="ghost"
          size="icon"
          disabled={!connected}
          className="size-7 shrink-0 pointer-coarse:size-11"
          aria-label={`Release ${label}`}
          title={`Release ${label}`}
          onClick={() => programmerClearTempo(speedMasterUuid)}
        >
          <X className="size-3.5" />
        </Button>
      )}
    </li>
  )
}
