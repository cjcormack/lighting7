import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Plus, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { useFieldAutosave } from '@/hooks/useFieldAutosave'
import { formatError } from '@/lib/formatError'
import { triggersOf, useFixtureTypeListQuery, type TriggerPropertyDescriptor } from '@/store/fixtures'
import { useVisiblePatchListQuery } from '@/store/patches'
import type { CueEvent, CueEventWriteItem } from '@/api/cuesApi'

/** A patch that carries one-shot triggers — what an event row can name. */
export interface CannonOption {
  patchId: number
  key: string
  name: string
  triggers: readonly TriggerPropertyDescriptor[]
}

/** The project's patched fixtures with one-shot triggers, by name. */
export function useCannons(projectId: number): CannonOption[] {
  const { data: patches } = useVisiblePatchListQuery(projectId)
  const { data: types } = useFixtureTypeListQuery()
  return useMemo(() => {
    const byType = new Map((types ?? []).map((t) => [t.typeKey, triggersOf(t.properties)]))
    return (patches ?? [])
      .map((p) => ({ patchId: p.id, key: p.key, name: p.displayName, triggers: byType.get(p.fixtureTypeKey) ?? [] }))
      .filter((c) => c.triggers.length > 0)
      .sort((a, b) => a.name.localeCompare(b.name))
  }, [patches, types])
}

/**
 * One row as the editor holds it. Keyed by the tube (patch · trigger) — a tube fires once per cue,
 * so the key is unique, and it survives the desk's list coming back after a save, so a row does not
 * remount (and drop its focus) under the operator.
 */
interface DraftRow {
  key: string
  patchId: number
  trigger: string
  offsetMs: number
}

function keyOf(patchId: number, trigger: string): string {
  return `${patchId}:${trigger}`
}

function rowsOf(events: readonly CueEvent[]): DraftRow[] {
  return [...events]
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map((e) => ({ key: keyOf(e.patchId, e.trigger), patchId: e.patchId, trigger: e.trigger, offsetMs: e.offsetMs }))
}

function itemsOf(rows: readonly DraftRow[]): CueEventWriteItem[] {
  return rows.map((r) => ({ patchId: r.patchId, trigger: r.trigger, offsetMs: r.offsetMs }))
}

/**
 * A cue's events (stage-view plan session 9; the Cue board's Events): one row per tube — fixture ·
 * tube · offset from GO · remove — and an add button. Built on the session-8 scenery editor's shape:
 * every gesture saves the **whole list** at once through [onSave], which is what `PUT cues/{id}/events`
 * takes; a refusal is drawn under the rows and the rows go back to what the desk holds; and the rows
 * are a draft held here, so a refetch landing while a save is in flight — or while an offset is being
 * typed — cannot put an older list back under the operator's hands.
 */
export function CueEventsEditor({
  projectId,
  events,
  onSave,
  idPrefix,
  disabled = false,
}: {
  projectId: number
  events: readonly CueEvent[]
  onSave: (items: CueEventWriteItem[]) => Promise<unknown>
  idPrefix: string
  disabled?: boolean
}) {
  const cannons = useCannons(projectId)
  const byPatch = useMemo(() => new Map(cannons.map((c) => [c.patchId, c])), [cannons])

  const [rows, setRows] = useState<DraftRow[]>(() => rowsOf(events))
  const [error, setError] = useState<string | null>(null)
  const pending = useRef(0)
  const editingTime = useRef(false)

  useEffect(() => {
    if (pending.current > 0 || editingTime.current) return
    setRows(rowsOf(events))
  }, [events])

  const save = useCallback(
    (next: DraftRow[]) => {
      setRows(next)
      setError(null)
      pending.current++
      onSave(itemsOf(next))
        .catch((e: unknown) => {
          setError(formatError(e))
          setRows(rowsOf(events))
        })
        .finally(() => {
          pending.current--
        })
    },
    [onSave, events],
  )

  const used = new Set(rows.map((r) => r.key))
  const firstFree = (() => {
    for (const c of cannons) {
      for (const t of c.triggers) if (!used.has(keyOf(c.patchId, t.name))) return { c, t }
    }
    return null
  })()

  const add = () => {
    if (firstFree == null) return
    const last = rows.at(-1)?.offsetMs ?? 0
    save([...rows, { key: keyOf(firstFree.c.patchId, firstFree.t.name), patchId: firstFree.c.patchId, trigger: firstFree.t.name, offsetMs: rows.length > 0 ? last : 0 }])
  }

  const update = (key: string, patch: Partial<DraftRow>) =>
    save(rows.map((r) => (r.key === key ? { ...r, ...patch, key: keyOf(patch.patchId ?? r.patchId, patch.trigger ?? r.trigger) } : r)))

  const remove = (key: string) => save(rows.filter((r) => r.key !== key))

  return (
    <div className="space-y-2">
      {rows.length > 0 && (
        <div className="space-y-1.5">
          {rows.map((row) => {
            const cannon = byPatch.get(row.patchId)
            return (
              <div key={row.key} className="grid grid-cols-[1.4fr_4.5rem_5.5rem_1.75rem] items-center gap-1.5">
                <Select
                  value={String(row.patchId)}
                  disabled={disabled}
                  onValueChange={(v) => {
                    const next = byPatch.get(Number(v))
                    if (next == null) return
                    // Keep the tube's label where the new cannon has one free, else its first free tube.
                    const label = cannon?.triggers.find((t) => t.name === row.trigger)?.label
                    const same = next.triggers.find((t) => t.label === label && !used.has(keyOf(next.patchId, t.name)))
                    const free = same ?? next.triggers.find((t) => !used.has(keyOf(next.patchId, t.name)))
                    if (free == null) return
                    update(row.key, { patchId: next.patchId, trigger: free.name })
                  }}
                >
                  <SelectTrigger id={`${idPrefix}-${row.key}-fixture`} className="h-8 w-full text-xs" aria-label="Fixture">
                    <SelectValue placeholder="Fixture" />
                  </SelectTrigger>
                  <SelectContent>
                    {cannon == null && (
                      <SelectItem value={String(row.patchId)} disabled>
                        Missing fixture
                      </SelectItem>
                    )}
                    {cannons.map((c) => (
                      <SelectItem key={c.patchId} value={String(c.patchId)}>
                        {c.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <Select
                  value={row.trigger}
                  disabled={disabled || cannon == null}
                  onValueChange={(v) => {
                    if (v !== row.trigger) update(row.key, { trigger: v })
                  }}
                >
                  <SelectTrigger id={`${idPrefix}-${row.key}-tube`} className="h-8 w-full text-xs" aria-label="Tube">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {(cannon?.triggers ?? []).map((t) => (
                      <SelectItem
                        key={t.name}
                        value={t.name}
                        disabled={t.name !== row.trigger && used.has(keyOf(row.patchId, t.name))}
                      >
                        {t.label}
                      </SelectItem>
                    ))}
                    {cannon == null && <SelectItem value={row.trigger}>{row.trigger}</SelectItem>}
                  </SelectContent>
                </Select>
                <OffsetField
                  id={`${idPrefix}-${row.key}-offset`}
                  valueMs={row.offsetMs}
                  disabled={disabled}
                  onCommit={(ms) => update(row.key, { offsetMs: ms })}
                  onEditing={(editing) => {
                    editingTime.current = editing
                  }}
                />
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="size-7"
                  aria-label={`Remove ${cannon?.name ?? 'event'}`}
                  disabled={disabled}
                  onClick={() => remove(row.key)}
                >
                  <X className="size-3.5" />
                </Button>
              </div>
            )
          })}
        </div>
      )}
      <Button
        type="button"
        variant="outline"
        size="sm"
        className="h-7 gap-1 text-xs"
        disabled={disabled || firstFree == null}
        onClick={add}
        title={cannons.length === 0 ? 'No patched fixture has a one-shot trigger.' : firstFree == null ? 'Every tube already fires in this cue.' : undefined}
      >
        <Plus className="size-3" />
        Add event
      </Button>
      {cannons.length === 0 && (
        <p className="text-[11px] text-muted-foreground">No patched fixture has a one-shot trigger — patch a confetti cannon first.</p>
      )}
      {error != null && <p className="text-xs text-destructive">{error}</p>}
    </div>
  )
}

/**
 * An event's offset from GO in milliseconds. Its text is its own until it commits — on blur, Enter or
 * a pause in typing — so a keystroke never waits on a round trip and a refetch never overwrites
 * what is being typed. Blank is 0.
 */
function OffsetField({
  id,
  valueMs,
  disabled,
  onCommit,
  onEditing,
}: {
  id: string
  valueMs: number
  disabled: boolean
  onCommit: (ms: number) => void
  onEditing: (editing: boolean) => void
}) {
  const [text, setText] = useState(String(valueMs))
  const focused = useRef(false)
  useEffect(() => {
    if (!focused.current) setText(String(valueMs))
  }, [valueMs])

  const commit = useCallback(() => {
    const raw = text.trim()
    const next = raw === '' ? 0 : Math.round(Number(raw))
    if (!Number.isFinite(next) || next < 0) return
    if (next === valueMs) return
    onCommit(next)
  }, [text, valueMs, onCommit])
  useFieldAutosave(text, commit)

  return (
    <Input
      id={id}
      type="number"
      min="0"
      step="50"
      value={text}
      disabled={disabled}
      title="Milliseconds after GO"
      aria-label="Offset after GO (ms)"
      className="h-8 font-mono text-xs"
      onFocus={() => {
        focused.current = true
        onEditing(true)
      }}
      onChange={(e) => setText(e.target.value)}
      onBlur={() => {
        focused.current = false
        onEditing(false)
        commit()
      }}
      onKeyDown={(e) => {
        if (e.key !== 'Enter') return
        e.preventDefault()
        commit()
      }}
    />
  )
}

/**
 * The cue card's Events (read-only, like the rest of the card): each cannon on a line with its tubes
 * and offsets — `fire A · +0.6 s`, `B · +0.75 s` — in the danger tone the board gives them.
 */
export function CueEventsReadout({ events }: { events: readonly CueEvent[] }) {
  const byFixture = useMemo(() => {
    const out = new Map<string, { name: string; tubes: CueEvent[] }>()
    for (const e of [...events].sort((a, b) => a.offsetMs - b.offsetMs)) {
      const entry = out.get(e.fixtureKey) ?? { name: e.fixtureName, tubes: [] }
      entry.tubes.push(e)
      out.set(e.fixtureKey, entry)
    }
    return [...out.values()]
  }, [events])
  return (
    <div className="space-y-1">
      {byFixture.map((f) => (
        <div key={f.name} className="flex flex-wrap items-center gap-x-3 gap-y-0.5 rounded-md border border-red-500/40 bg-red-500/5 px-2 py-1 text-xs">
          <span className="font-semibold">{f.name}</span>
          {f.tubes.map((t, i) => (
            <span key={t.uuid} className="text-muted-foreground">
              {i === 0 ? 'fire ' : ''}
              {t.triggerLabel} · +{(t.offsetMs / 1000).toFixed(t.offsetMs % 100 === 0 ? 1 : 2)} s
            </span>
          ))}
        </div>
      ))}
      <p className="text-[11px] text-muted-foreground">
        On GO into this cue only, and only while the desk is armed. GO TO a later cue does not fire
        them; Next GO does not preview them.
      </p>
    </div>
  )
}
