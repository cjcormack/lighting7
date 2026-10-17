import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Plus, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { useFieldAutosave } from '@/hooks/useFieldAutosave'
import { useSpeedMasterListQuery } from '@/store/speedMasters'
import type { SpeedMaster } from '@/api/speedMastersApi'
import type { CueSpeedMaster } from '@/api/cuesApi'

const MIN_BPM = 20
const MAX_BPM = 300

/** `M2 · Chase`, or what a row names when its master has gone. */
function masterLabel(master: SpeedMaster | undefined): string {
  return master ? `M${master.masterIndex} · ${master.name}` : 'Deleted master'
}

/** Why a GO skips this row, or null when it sets the master. */
function skipReason(master: SpeedMaster | undefined, byUuid: Map<string, SpeedMaster>): string | null {
  if (master == null) return 'The master was deleted — skipped on GO'
  if (master.followNum == null || master.followDen == null) return null
  const leader = master.followTargetUuid ? byUuid.get(master.followTargetUuid) : undefined
  const leaderLabel = leader ? `M${leader.masterIndex}` : 'M1'
  return `Follows ${leaderLabel} — skipped on GO`
}

/**
 * The Cue tab's **Speed masters** (venue-visit plan S16, D20): the masters this cue sets on every GO
 * into it and every press of it, each a master and a BPM. Every gesture saves the **whole list**
 * through the cue's PATCH; the rows are a draft held here, so a refetch landing while a save is in
 * flight cannot put an older list back under the operator, and the last save's answer is what they
 * settle on. A refusal is toasted by the error middleware and the rows go back to what the desk
 * holds. Locked, the tab reads.
 */
export function CueSpeedMasters({
  projectId,
  speedMasters,
  locked,
  idBase,
  onSave,
}: {
  projectId: number
  speedMasters: readonly CueSpeedMaster[]
  locked: boolean
  idBase: string
  onSave: (next: CueSpeedMaster[]) => Promise<CueSpeedMaster[]>
}) {
  const { data: masters } = useSpeedMasterListQuery({ projectId })
  const byUuid = useMemo(() => new Map((masters ?? []).map((m) => [m.uuid, m])), [masters])
  const sorted = useMemo(() => [...(masters ?? [])].sort((a, b) => a.masterIndex - b.masterIndex), [masters])

  const [rows, setRows] = useState<CueSpeedMaster[]>(() => [...speedMasters])
  const pending = useRef(0)
  useEffect(() => {
    if (pending.current > 0) return
    setRows([...speedMasters])
  }, [speedMasters])

  const save = useCallback(
    (next: CueSpeedMaster[]) => {
      setRows(next)
      pending.current++
      onSave(next).then(
        (saved) => {
          pending.current--
          if (pending.current === 0) setRows([...saved])
        },
        () => {
          pending.current--
          setRows([...speedMasters])
        },
      )
    },
    [onSave, speedMasters],
  )

  if (locked) {
    return (
      <div className="space-y-1.5" data-cue-speed-masters="read">
        <EditorLabel>Speed masters</EditorLabel>
        {speedMasters.length === 0 ? (
          <p className="text-sm italic text-muted-foreground">None.</p>
        ) : (
          <ul className="space-y-0.5 text-sm">
            {speedMasters.map((row) => (
              <li key={row.speedMasterUuid} className="flex items-baseline gap-2">
                <span className="min-w-0 truncate">{masterLabel(byUuid.get(row.speedMasterUuid))}</span>
                <span className="shrink-0 font-mono text-xs text-muted-foreground">{formatBpm(row.bpm)} bpm</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    )
  }

  const used = new Set(rows.map((r) => r.speedMasterUuid))
  // A follower is skipped on GO, so one is offered only when nothing else is free.
  const free = sorted.filter((m) => !used.has(m.uuid))
  const firstFree = free.find((m) => m.followNum == null) ?? free[0]
  const add = () => {
    if (firstFree == null) return
    save([...rows, { speedMasterUuid: firstFree.uuid, bpm: clampBpm(Math.round(firstFree.bpm)) }])
  }
  const update = (index: number, patch: Partial<CueSpeedMaster>) =>
    save(rows.map((r, i) => (i === index ? { ...r, ...patch } : r)))
  const remove = (index: number) => save(rows.filter((_, i) => i !== index))

  return (
    <div className="space-y-1.5" data-cue-speed-masters="edit">
      <div className="flex items-center justify-between gap-2">
        <EditorLabel>Speed masters</EditorLabel>
        <Button
          variant="outline"
          size="sm"
          className="h-7 gap-1 text-xs"
          disabled={firstFree == null}
          title={firstFree == null ? 'This cue already sets every master.' : undefined}
          onClick={add}
        >
          <Plus className="size-3" />
          Master
        </Button>
      </div>
      {rows.length === 0 ? (
        <p className="text-[11px] text-muted-foreground">
          None. A cue can set masters&rsquo; tempo on every GO into it; a tap afterwards wins until the next cue.
        </p>
      ) : (
        <div className="space-y-1.5">
          {rows.map((row, index) => {
            const master = byUuid.get(row.speedMasterUuid)
            const reason = skipReason(master, byUuid)
            return (
              <div key={row.speedMasterUuid} className="space-y-0.5" data-cue-speed-master-row={row.speedMasterUuid}>
                <div className="grid grid-cols-[minmax(0,16rem)_5.5rem_1.75rem] items-center gap-1.5">
                  <Select
                    value={row.speedMasterUuid}
                    onValueChange={(uuid) => {
                      if (uuid !== row.speedMasterUuid) update(index, { speedMasterUuid: uuid })
                    }}
                  >
                    <SelectTrigger id={`${idBase}-master-${index}`} size="sm" className="w-full text-xs" aria-label="Speed master">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {master == null && (
                        <SelectItem value={row.speedMasterUuid} disabled>
                          Deleted master
                        </SelectItem>
                      )}
                      {sorted.map((m) => (
                        <SelectItem key={m.uuid} value={m.uuid} disabled={m.uuid !== row.speedMasterUuid && used.has(m.uuid)}>
                          {masterLabel(m)}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <BpmField
                    id={`${idBase}-bpm-${index}`}
                    bpm={row.bpm}
                    label={`${masterLabel(master)} BPM`}
                    onCommit={(bpm) => update(index, { bpm })}
                  />
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="size-7"
                    aria-label={`Remove ${masterLabel(master)}`}
                    onClick={() => remove(index)}
                  >
                    <X className="size-3.5" />
                  </Button>
                </div>
                {reason != null && <p className="text-[11px] text-amber-600 dark:text-amber-400">{reason}</p>}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

function clampBpm(bpm: number): number {
  return Math.min(MAX_BPM, Math.max(MIN_BPM, bpm))
}

function formatBpm(bpm: number): string {
  return Number.isInteger(bpm) ? String(bpm) : bpm.toFixed(1)
}

/**
 * A row's tempo. Its text is its own until it commits — on blur, Enter or a pause in typing — so
 * `1` on the way to `128` is never sent; a number outside 20–300 is not committed and the field
 * goes back to the stored tempo on blur.
 */
function BpmField({
  id,
  bpm,
  label,
  onCommit,
}: {
  id: string
  bpm: number
  label: string
  onCommit: (bpm: number) => void
}) {
  const [text, setText] = useState(formatBpm(bpm))
  const focused = useRef(false)
  useEffect(() => {
    if (!focused.current) setText(formatBpm(bpm))
  }, [bpm])

  const commit = useCallback(() => {
    const next = Number(text.trim())
    if (text.trim() === '' || !Number.isFinite(next) || next < MIN_BPM || next > MAX_BPM) return
    const rounded = Math.round(next * 10) / 10
    if (rounded !== bpm) onCommit(rounded)
  }, [text, bpm, onCommit])
  useFieldAutosave(text, commit)

  return (
    <div className="relative">
      <Input
        id={id}
        type="number"
        min={MIN_BPM}
        max={MAX_BPM}
        step="1"
        value={text}
        aria-label={label}
        title="20–300 BPM"
        className="h-8 pr-9 font-mono text-xs [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
        onFocus={() => {
          focused.current = true
        }}
        onChange={(e) => setText(e.target.value)}
        onBlur={() => {
          focused.current = false
          commit()
          setText(formatBpm(bpm))
        }}
        onKeyDown={(e) => {
          if (e.key !== 'Enter') return
          e.preventDefault()
          commit()
        }}
      />
      <span className="pointer-events-none absolute inset-y-0 right-2 flex items-center text-[10px] text-muted-foreground">
        bpm
      </span>
    </div>
  )
}
