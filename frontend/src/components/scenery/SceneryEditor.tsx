import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Plus, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useFieldAutosave } from '@/hooks/useFieldAutosave'
import { formatError } from '@/lib/formatError'
import { describeSceneryState, sceneryChoices, statesFor, shownSceneryState } from '@/lib/scenery'
import type { SceneryKey } from '@/lib/scenery'
import { useStageElementListQuery } from '@/store/stageElements'
import type { SceneryChange, SceneryState, SceneryWriteItem } from '@/api/sceneryApi'
import type { StageElementDto } from '@/api/stageElementApi'
import { SceneryControl } from './SceneryControl'

/**
 * One row as the editor holds it: its element, its state, and a cue's clock. Keyed by the element —
 * an owner says one thing about each element, so the key is unique, and it survives the desk's list
 * coming back after a save, so a row does not remount (and drop its focus) under the operator.
 */
interface DraftRow {
  key: string
  elementUuid: string
  state: SceneryState
  /** Milliseconds, as a stored number; null moves with the cue's fade. */
  transitionMs: number | null
}

export interface SceneryEditorProps {
  projectId: number
  /** The owner's list as the desk holds it. */
  scenery: readonly SceneryChange[]
  /** A cue's rows carry their own clock; a stack's set and a Look's scenery have none. */
  withTime: boolean
  /** `Add change` on a cue, `Add state` on a stack's set and a Look. */
  addLabel: string
  /** The whole list, as the owner's `PUT …/scenery` takes it. Resolves on success, rejects on a refusal. */
  onSave: (items: SceneryWriteItem[]) => Promise<unknown>
  disabled?: boolean
  /** A stable id prefix for the rows' fields. */
  idPrefix: string
}

function rowsOf(scenery: readonly SceneryChange[]): DraftRow[] {
  return [...scenery]
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map((c) => ({ key: c.elementUuid, elementUuid: c.elementUuid, state: c.state, transitionMs: c.transitionMs ?? null }))
}

function itemsOf(rows: readonly DraftRow[], withTime: boolean): SceneryWriteItem[] {
  return rows.map((r) => ({
    elementUuid: r.elementUuid,
    state: r.state,
    ...(withTime && r.transitionMs != null ? { transitionMs: r.transitionMs } : {}),
  }))
}

/**
 * A scenery list's editor (stage-view plan session 8; the Cue, Stacks and Looks boards): one row per
 * element — element · time (a cue's only) · remove, and under them the element's `SceneryControl`
 * (scenery-programmer plan D5: the old step select's choices as presets, plus a range and *Shown ·
 * Hidden*) — and an add button. The control writes on **release** here, not as it goes: each write
 * is the whole list's `PUT`, and a drag's thirty a second would be thirty refetches. Every gesture saves
 * the **whole list** at once through [onSave], which is what the desk's route takes, and the desk
 * checks each state against its element's kind; a refusal is drawn under the rows and the rows go
 * back to what the desk holds.
 *
 * The rows are a draft held here, so a refetch that lands while a save is in flight — or while a
 * time is being typed — cannot put an older list back under the operator's hands (the lesson of
 * `StageFocusPanel`). The draft adopts the desk's list again once nothing of its own is pending.
 */
export function SceneryEditor({ projectId, scenery, withTime, addLabel, onSave, disabled = false, idPrefix }: SceneryEditorProps) {
  const { data: elements } = useStageElementListQuery(projectId)
  const byUuid = useMemo(() => new Map((elements ?? []).map((e) => [e.uuid, e])), [elements])
  const ordered = useMemo(
    () => [...(elements ?? [])].sort((a, b) => a.name.localeCompare(b.name)),
    [elements],
  )

  const [rows, setRows] = useState<DraftRow[]>(() => rowsOf(scenery))
  const [error, setError] = useState<string | null>(null)
  const pending = useRef(0)
  const editingTime = useRef(false)

  // Adopt the desk's list when it moves and nothing of ours is in flight or being typed.
  useEffect(() => {
    if (pending.current > 0 || editingTime.current) return
    setRows(rowsOf(scenery))
  }, [scenery])

  const save = useCallback(
    (next: DraftRow[]) => {
      setRows(next)
      setError(null)
      pending.current++
      onSave(itemsOf(next, withTime))
        .catch((e: unknown) => {
          setError(formatError(e))
          // The desk refused the whole list: show what it holds.
          setRows(rowsOf(scenery))
        })
        .finally(() => {
          pending.current--
        })
    },
    [onSave, scenery, withTime],
  )

  const used = new Set(rows.map((r) => r.elementUuid))
  const firstFree = ordered.find((e) => !used.has(e.uuid))

  const add = () => {
    if (firstFree == null) return
    const choice = sceneryChoices(firstFree)[0]
    save([...rows, { key: firstFree.uuid, elementUuid: firstFree.uuid, state: choice.state, transitionMs: null }])
  }

  const update = (key: string, patch: Partial<DraftRow>) =>
    save(rows.map((r) => (r.key === key ? { ...r, ...patch } : r)))

  const remove = (key: string) => save(rows.filter((r) => r.key !== key))

  return (
    <div className="space-y-2">
      {rows.length > 0 && (
        <div className="space-y-1.5">
          {rows.map((row) => {
            const element = byUuid.get(row.elementUuid)
            return (
              <SceneryRow
                key={row.key}
                id={`${idPrefix}-${row.key}`}
                row={row}
                element={element}
                elements={ordered}
                used={used}
                withTime={withTime}
                disabled={disabled}
                onElement={(uuid) => {
                  const next = byUuid.get(uuid)
                  if (next == null) return
                  // Keep the states the new element can take, else its first choice.
                  const keep = statesFor(next, row.state)
                  update(row.key, { key: uuid, elementUuid: uuid, state: keep ?? sceneryChoices(next)[0].state })
                }}
                onState={(patch) => update(row.key, { state: { ...row.state, ...patch } })}
                onUnstate={(key) => {
                  // A row says only what it states: the key let go tracks again (the control
                  // offers this only while the row states something else, so it is never empty).
                  const rest: SceneryState = { ...row.state }
                  delete rest[key]
                  update(row.key, { state: rest })
                }}
                onTime={(ms) => update(row.key, { transitionMs: ms })}
                onTimeEditing={(editing) => {
                  editingTime.current = editing
                }}
                onRemove={() => remove(row.key)}
              />
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
        title={
          ordered.length === 0
            ? 'The stage has no scenery yet — place some from the Stage view (Edit → + Scenery).'
            : firstFree == null
              ? 'Every element already has a row.'
              : undefined
        }
      >
        <Plus className="size-3" />
        {addLabel}
      </Button>
      {ordered.length === 0 && (
        <p className="text-[11px] text-muted-foreground">
          The stage has no scenery yet — place some from the Stage view (Edit → + Scenery).
        </p>
      )}
      {error != null && <p className="text-xs text-destructive">{error}</p>}
    </div>
  )
}

function SceneryRow({
  id,
  row,
  element,
  elements,
  used,
  withTime,
  disabled,
  onElement,
  onState,
  onUnstate,
  onTime,
  onTimeEditing,
  onRemove,
}: {
  id: string
  row: DraftRow
  element: StageElementDto | undefined
  elements: readonly StageElementDto[]
  used: ReadonlySet<string>
  withTime: boolean
  disabled: boolean
  onElement: (uuid: string) => void
  onState: (patch: SceneryState) => void
  onUnstate: (key: SceneryKey) => void
  onTime: (ms: number | null) => void
  onTimeEditing: (editing: boolean) => void
  onRemove: () => void
}) {
  return (
    <div data-scenery-row={row.elementUuid} className="space-y-1.5 rounded-md border px-2 py-1.5">
      <div className={withTime ? 'grid grid-cols-[1fr_5.5rem_1.75rem] items-center gap-1.5' : 'grid grid-cols-[1fr_1.75rem] items-center gap-1.5'}>
        <Select value={row.elementUuid} onValueChange={onElement} disabled={disabled}>
          <SelectTrigger id={`${id}-element`} className="h-8 w-full text-xs" aria-label="Element">
            <SelectValue placeholder="Element" />
          </SelectTrigger>
          <SelectContent>
            {element == null && (
              <SelectItem value={row.elementUuid} disabled>
                Missing element
              </SelectItem>
            )}
            {elements.map((e) => (
              <SelectItem key={e.uuid} value={e.uuid} disabled={e.uuid !== row.elementUuid && used.has(e.uuid)}>
                {e.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        {withTime && (
          <TimeField
            id={`${id}-time`}
            valueMs={row.transitionMs}
            disabled={disabled}
            onCommit={onTime}
            onEditing={onTimeEditing}
          />
        )}
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="size-7"
          aria-label={`Remove ${element?.name ?? 'change'}`}
          disabled={disabled}
          onClick={onRemove}
        >
          <X className="size-3.5" />
        </Button>
      </div>
      {element != null ? (
        // The row's own states laid on the element's base, so the control has every key to draw;
        // a write merges back into the row's states only what the gesture moved, and a key the row
        // does not state is drawn muted, with an Unset × on each one it does.
        <SceneryControl
          element={element}
          state={shownSceneryState(element, row.state)}
          onWrite={onState}
          commit="release"
          stated={new Set(Object.keys(row.state) as SceneryKey[])}
          onUnstate={onUnstate}
          disabled={disabled}
          showLabel={false}
          idPrefix={id}
        />
      ) : (
        <p className="text-[11px] text-muted-foreground">{describeSceneryState(undefined, row.state)}</p>
      )}
    </div>
  )
}

/**
 * A cue change's clock in milliseconds: blank moves with the cue's fade. Its text is its own until
 * it commits — on blur, Enter, or a pause in typing — so a keystroke never waits on a round trip and
 * a refetch never overwrites what is being typed.
 */
function TimeField({
  id,
  valueMs,
  disabled,
  onCommit,
  onEditing,
}: {
  id: string
  valueMs: number | null
  disabled: boolean
  onCommit: (ms: number | null) => void
  onEditing: (editing: boolean) => void
}) {
  const [text, setText] = useState(valueMs == null ? '' : String(valueMs))
  const focused = useRef(false)
  useEffect(() => {
    if (!focused.current) setText(valueMs == null ? '' : String(valueMs))
  }, [valueMs])

  const commit = useCallback(() => {
    const raw = text.trim()
    const next = raw === '' ? null : Math.round(Number(raw))
    if (next != null && (!Number.isFinite(next) || next < 0)) return
    if (next === valueMs) return
    onCommit(next)
  }, [text, valueMs, onCommit])
  useFieldAutosave(text, commit)

  return (
    <Input
      id={id}
      type="number"
      min="0"
      step="100"
      value={text}
      disabled={disabled}
      placeholder="cue"
      title="Milliseconds. Blank moves with the cue's fade."
      aria-label="Time (ms)"
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
