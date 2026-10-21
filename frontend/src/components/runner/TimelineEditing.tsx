import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'
import { ArrowDownToLine, CircleOff, Plus, Replace, Settings2, Sparkles, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Popover, PopoverAnchor, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { cn } from '@/lib/utils'
import type { CueTarget } from '@/api/cuesApi'
import type { SceneryState } from '@/api/sceneryApi'
import type { StageElementDto } from '@/api/stageElementApi'
import type {
  StackTimeline as StackTimelineData,
  TimelineCellRef,
  TimelineCueSnapshot,
  TimelineEditOp,
  TimelineEditResponse,
  TimelineLayer,
  TimelineLayerFields,
} from '@/api/stackTimelineApi'
import { useEditCueStackTimelineMutation } from '@/store/cueStacks'
import { useLookListQuery } from '@/store/looks'
import { useTemplateListQuery } from '@/store/templates'
import { useSpeedMasterListQuery } from '@/store/speedMasters'
import { FAMILY_LABELS, type AttributeFamily } from '@/lib/attributeFamily'
import { shownSceneryState } from '@/lib/scenery'
import { replacePlan, sharedValue, type TimelineColumn } from '@/lib/stackTimeline'
import type { StoredExtraColumn } from '@/lib/stackTimeline/columnOrder'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { SceneryControl } from '@/components/scenery/SceneryControl'

/**
 * Each stack's Undo steps, last first, kept while the window lives (§4.4): one step per gesture,
 * the answer's `before`, which Undo sends back as `restore` ops (P5). Module-level, so switching
 * the stack's view and back keeps them.
 */
const undoSteps = new Map<number, TimelineCueSnapshot[][]>()

/** Forget every stack's Undo — for tests. */
export function resetTimelineUndo(): void {
  undoSteps.clear()
}

/**
 * The Timeline's one write door: each gesture as **one** request to `timeline/edit`, its answer's
 * `before` pushed as one Undo step, and a toast naming what it did with *Undo* on it. ⌘Z and the
 * toast's *Undo* send the last step back as `restore` ops. A refusal is the desk's, toasted by the
 * error middleware, and changes nothing here.
 */
export function useTimelineEdits(projectId: number, stackId: number) {
  const [edit, { isLoading }] = useEditCueStackTimelineMutation()
  const toastId = `timeline-edit-${stackId}`
  const [depth, setDepth] = useState(() => undoSteps.get(stackId)?.length ?? 0)
  const undoRef = useRef<() => void>(() => {})

  const send = useCallback(
    async (ops: TimelineEditOp[], words: (answer: TimelineEditResponse) => string): Promise<TimelineEditResponse | null> => {
      if (ops.length === 0) return null
      let answer: TimelineEditResponse
      try {
        answer = await edit({ projectId, stackId, ops }).unwrap()
      } catch {
        return null
      }
      if (answer.before.length > 0) {
        const steps = undoSteps.get(stackId) ?? []
        steps.push(answer.before)
        undoSteps.set(stackId, steps)
        setDepth(steps.length)
      }
      toast(words(answer), {
        id: toastId,
        action: answer.before.length > 0 ? { label: 'Undo', onClick: () => undoRef.current() } : undefined,
      })
      return answer
    },
    [edit, projectId, stackId, toastId],
  )

  const undo = useCallback(async () => {
    const steps = undoSteps.get(stackId)
    const step = steps?.pop()
    if (steps == null || step == null) return
    setDepth(steps.length)
    try {
      await edit({ projectId, stackId, ops: step.map((cue) => ({ op: 'restore' as const, ...cue })) }).unwrap()
      toast('Put back', { id: toastId })
    } catch {
      steps.push(step)
      setDepth(steps.length)
    }
  }, [edit, projectId, stackId, toastId])
  undoRef.current = () => void undo()

  return { send, undo, canUndo: depth > 0, busy: isLoading }
}

/** What a gesture did, as its toast says it: *Amber on LX1 Orbits in Q5. Lavender released there.* */
export function describeEdit(
  ops: readonly TimelineEditOp[],
  answer: TimelineEditResponse,
  columnOf: (op: TimelineEditOp) => TimelineColumn | undefined,
  cues: (cueIds: readonly number[]) => string,
): string {
  const groups = new Map<string, { column: TimelineColumn | undefined; verb: string; cueIds: number[] }>()
  for (const op of ops) {
    if (op.op === 'restore' || op.op === 'replace' || op.op === 'layerSettings') continue
    const column = columnOf(op)
    const verb = op.op === 'layerOn' ? 'on' : op.op === 'layerOff' ? 'off' : op.op === 'scenerySet' ? 'set' : 'cleared'
    const key = `${column?.id ?? '?'}|${verb}`
    const group = groups.get(key) ?? { column, verb, cueIds: [] }
    group.cueIds.push(op.cueId)
    groups.set(key, group)
  }
  const sentences = [...groups.values()].map(({ column, verb, cueIds }) => {
    const name = column == null ? 'Cell' : column.kind === 'scenery' ? column.name : `${column.name} ${verb === 'on' ? 'on' : 'off'} ${column.sub}`
    const action = column?.kind === 'scenery' ? ` ${verb}` : ''
    return `${name}${action} in ${cues(cueIds)}.`
  })
  const released = [...new Set(answer.released.map((r) => r.source.name))]
  if (released.length > 0) sentences.push(`${released.join(', ')} released there.`)
  if (sentences.length === 0) return answer.changed.length === 0 ? 'Nothing changed' : `${cues(answer.changed)} changed.`
  if (answer.changed.length === 0) return 'Nothing changed'
  return sentences.join(' ')
}

// ── The selection bar's verbs ─────────────────────────────────────────────────────────────────────

export interface TimelineVerbState {
  on: TimelineEditOp[]
  off: TimelineEditOp[]
  fillDown: TimelineEditOp[]
  /** The one Look or palette column the selection is in, with the cues whose cell is filled. */
  replace: { column: TimelineColumn; cueIds: number[] } | null
  layers: { cueId: number; layer: TimelineLayer }[]
}

/**
 * *On · Off · Fill ↓ · Replace with… · Settings…* (§4.4), each **disabled when it would change
 * nothing**. Locked, a press asks to unlock ([onRefused]), or the verbs are disabled with the
 * reason where the lock is not the operator's to lift.
 */
export function TimelineVerbs({
  state,
  locked,
  lockedReason,
  onRefused,
  onSend,
  replacePopover,
  settingsPopover,
}: {
  state: TimelineVerbState
  locked: boolean
  lockedReason: string
  onRefused?: () => void
  onSend: (ops: TimelineEditOp[], label: string) => void
  replacePopover: (trigger: ReactNode) => ReactNode
  settingsPopover: (trigger: ReactNode) => ReactNode
}) {
  const refused = locked && onRefused == null
  const press = (ops: TimelineEditOp[], label: string) => () => (locked ? onRefused?.() : onSend(ops, label))
  const verb = (label: string, icon: ReactNode, ops: TimelineEditOp[], hint: string, shortcut?: string) => (
    <Button
      variant="outline"
      size="sm"
      disabled={refused || ops.length === 0}
      title={refused ? lockedReason : ops.length === 0 ? `${label} would change nothing` : `${hint}${shortcut ? ` (${shortcut})` : ''}`}
      onClick={press(ops, label)}
      data-timeline-verb={label}
    >
      {icon}
      <span className="hidden @[760px]:inline">{label}</span>
    </Button>
  )
  const replaceTrigger = (
    <Button
      variant="outline"
      size="sm"
      disabled={refused || state.replace == null}
      title={refused ? lockedReason : state.replace == null ? 'Select filled cells in one Look or palette column' : 'Replace this record over the selected cues'}
      data-timeline-verb="Replace"
      onClick={locked ? (e) => (e.preventDefault(), onRefused?.()) : undefined}
    >
      <Replace className="size-3.5" />
      <span className="hidden @[760px]:inline">Replace with…</span>
    </Button>
  )
  const settingsTrigger = (
    <Button
      variant="outline"
      size="sm"
      disabled={refused || state.layers.length === 0}
      title={refused ? lockedReason : state.layers.length === 0 ? 'Select a filled Look or palette cell' : "The selected layers' settings"}
      data-timeline-verb="Settings"
      onClick={locked ? (e) => (e.preventDefault(), onRefused?.()) : undefined}
    >
      <Settings2 className="size-3.5" />
      <span className="hidden @[760px]:inline">Settings…</span>
    </Button>
  )
  return (
    <>
      {verb('On', <Sparkles className="size-3.5" />, state.on, 'Fill each empty cell from its nearest filled cell', '⏎')}
      {verb('Off', <CircleOff className="size-3.5" />, state.off, 'Take the selected cells off', '⌫')}
      {verb('Fill ↓', <ArrowDownToLine className="size-3.5" />, state.fillDown, "Copy each column's top row down", '⌘D')}
      {locked ? replaceTrigger : replacePopover(replaceTrigger)}
      {locked ? settingsTrigger : settingsPopover(settingsTrigger)}
    </>
  )
}

// ── Replace with… ─────────────────────────────────────────────────────────────────────────────────

type RecordChoice = { kind: 'LOOK' | 'TEMPLATE'; id: number; name: string }

/** *Replace with…* (D11): the selected column's record swapped for another over the selected cues. */
export function TimelineReplacePopover({
  trigger,
  projectId,
  column,
  cueIds,
  timeline,
  deskTargets,
  targetName,
  cues,
  onReplace,
}: {
  trigger: ReactNode
  projectId: number
  column: TimelineColumn
  cueIds: number[]
  timeline: StackTimelineData
  deskTargets: readonly CueTarget[]
  targetName: (target: CueTarget) => string
  cues: (cueIds: readonly number[]) => string
  onReplace: (op: TimelineEditOp) => void
}) {
  const [open, setOpen] = useState(false)
  const { data: looks } = useLookListQuery({ projectId }, { skip: !open })
  const { data: palettes } = useTemplateListQuery({ projectId }, { skip: !open })
  const records = useMemo<RecordChoice[]>(
    () => [
      ...(palettes ?? []).map((p) => ({ kind: 'TEMPLATE' as const, id: p.id, name: p.name })),
      ...(looks ?? []).map((l) => ({ kind: 'LOOK' as const, id: l.id, name: l.name })),
    ],
    [looks, palettes],
  )
  const [record, setRecord] = useState<string>('')
  const [targetKey, setTargetKey] = useState<string | null>(null)
  const [keepSettings, setKeepSettings] = useState(true)
  const targets = useMemo(() => {
    const out = new Map<string, CueTarget | null>([['*', null]])
    if (column.target) out.set(`${column.target.type}:${column.target.key}`, column.target)
    for (const t of deskTargets) out.set(`${t.type}:${t.key}`, t)
    return out
  }, [column.target, deskTargets])
  const defaultTarget = deskTargets[0] ?? column.target
  const chosenTargetKey = targetKey ?? (defaultTarget ? `${defaultTarget.type}:${defaultTarget.key}` : '*')
  const chosen = records.find((r) => `${r.kind}:${r.id}` === record)
  const from: TimelineCellRef = { source: { kind: column.kind === 'look' ? 'LOOK' : 'TEMPLATE', id: column.sourceId! }, target: column.target }
  const to: TimelineCellRef | null = chosen ? { source: { kind: chosen.kind, id: chosen.id }, target: targets.get(chosenTargetKey) ?? null } : null
  const plan = to ? replacePlan(timeline.entries, cueIds, from, to) : null
  const same = to != null && to.source.kind === from.source.kind && to.source.id === from.source.id && (to.target?.key ?? null) === (from.target?.key ?? null)
  const count = plan == null ? 0 : plan.swaps.length + plan.holds.length
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      <PopoverContent className="w-80 space-y-3 p-3" data-timeline-replace>
        <div className="flex items-baseline justify-between">
          <b className="text-sm">Replace in {cues(cueIds)}</b>
          <span className="text-[11px] text-muted-foreground">{cueIds.length} cell{cueIds.length === 1 ? '' : 's'}</span>
        </div>
        <div className="text-xs text-muted-foreground">
          {column.name} · {column.sub} with
        </div>
        <div className="flex gap-2">
          <select
            aria-label="Replacement"
            className="h-8 min-w-0 flex-1 rounded-md border bg-background px-2 text-sm"
            value={record}
            onChange={(e) => setRecord(e.target.value)}
          >
            <option value="">Choose…</option>
            <optgroup label="Palettes">
              {records.filter((r) => r.kind === 'TEMPLATE').map((r) => (
                <option key={`TEMPLATE:${r.id}`} value={`TEMPLATE:${r.id}`}>{r.name}</option>
              ))}
            </optgroup>
            <optgroup label="Looks">
              {records.filter((r) => r.kind === 'LOOK').map((r) => (
                <option key={`LOOK:${r.id}`} value={`LOOK:${r.id}`}>{r.name}</option>
              ))}
            </optgroup>
          </select>
          <select
            aria-label="Target"
            className="h-8 w-28 rounded-md border bg-background px-2 text-sm"
            value={chosenTargetKey}
            onChange={(e) => setTargetKey(e.target.value)}
          >
            {[...targets].map(([key, t]) => (
              <option key={key} value={key}>{t == null ? 'Own heads' : targetName(t)}</option>
            ))}
          </select>
        </div>
        <label className="flex items-center gap-2 text-xs">
          <input type="checkbox" checked={keepSettings} onChange={(e) => setKeepSettings(e.target.checked)} />
          Keep each layer&apos;s rank and settings
        </label>
        {plan != null && !same && (
          <p className="text-[11px] leading-snug text-muted-foreground" data-replace-readout>
            {plan.swaps.length > 0 && `${cues(plan.swaps)} swap ${column.name} for ${chosen?.name}${keepSettings ? ' in place' : ''}. `}
            {plan.holds.length > 0 && `${cues(plan.holds)} already hold${plan.holds.length === 1 ? 's' : ''} ${chosen?.name}, so ${column.name} comes off there. `}
            Nothing is sent to the stage.
          </p>
        )}
        <div className="flex justify-end gap-2">
          <Button variant="outline" size="sm" onClick={() => setOpen(false)}>Cancel</Button>
          <Button
            size="sm"
            disabled={to == null || same || count === 0}
            onClick={() => {
              if (to == null) return
              onReplace({ op: 'replace', cueIds: [...new Set(cueIds)], from, to, keepSettings })
              setOpen(false)
            }}
          >
            Replace {count > 0 ? count : ''}
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  )
}

// ── Settings… ─────────────────────────────────────────────────────────────────────────────────────

const BLENDS: { value: string; label: string }[] = [
  { value: 'OVERRIDE', label: 'Replace' },
  { value: 'ADDITIVE', label: 'Add' },
  { value: 'MAX', label: 'Max' },
  { value: 'MIN', label: 'Min' },
  { value: 'MULTIPLY', label: 'Multiply' },
]
const MASK_FAMILIES: readonly AttributeFamily[] = ['INTENSITY', 'COLOUR', 'POSITION', 'BEAM']

function maskSet(mask: string | null): Set<string> {
  return new Set((mask ?? '').split(',').map((f) => f.trim().toUpperCase()).filter((f) => f.length > 0))
}

/**
 * *Settings…* (D11): the selected layers' own fields, as the cue editor's Looks tab draws them —
 * every change one request over every layer, and a field the layers disagree on reading *mixed*
 * until it is set.
 */
export function TimelineSettingsPopover({
  trigger,
  projectId,
  layers,
  cues,
  onWrite,
}: {
  trigger: ReactNode
  projectId: number
  layers: { cueId: number; layer: TimelineLayer }[]
  cues: (cueIds: readonly number[]) => string
  onWrite: (ops: TimelineEditOp[], label: string) => void
}) {
  const [open, setOpen] = useState(false)
  const { data: masters } = useSpeedMasterListQuery({ projectId }, { skip: !open })
  const write = (fields: TimelineLayerFields, label: string) =>
    onWrite(layers.map(({ cueId, layer }) => ({ op: 'layerSettings' as const, cueId, layerId: layer.id, fields })), label)
  const value = <T,>(read: (l: TimelineLayer) => T) => sharedValue(layers.map(({ layer }) => read(layer)))
  const amount = value((l) => l.amount)
  const blend = value((l) => l.blendMode.toUpperCase())
  const mask = value((l) => l.propertyMask)
  const stomp = value((l) => l.stomp)
  const enabled = value((l) => l.enabled)
  const delay = value((l) => l.delayMs ?? null)
  const interval = value((l) => l.intervalMs ?? null)
  const random = value((l) => l.randomWindowMs ?? null)
  const speed = value((l) => l.speedMasterUuid)
  const rate = value((l) => l.rateSpeedMasterUuid)
  const first = layers[0]?.layer
  const sources = new Set(layers.map(({ layer }) => `${layer.source.kind}:${layer.source.id}`))
  const wide = layers.find(({ layer }) => layer.targets.length > 1)
  const title =
    layers.length === 1 && first ? `${cues([layers[0].cueId])} · ${first.source.name}` : `${layers.length} layers · ${cues(layers.map((l) => l.cueId))}`
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      <PopoverContent className="w-80 space-y-3 p-3" data-timeline-settings>
        <div className="flex items-baseline justify-between gap-2">
          <b className="truncate text-sm">{title}</b>
          {layers.length === 1 && first?.rank != null && <span className="shrink-0 text-[11px] text-muted-foreground">rank {first.rank}</span>}
        </div>
        <SettingRow label="Enabled">
          <SegmentButtons
            value={enabled === 'mixed' ? null : enabled ? 'on' : 'off'}
            options={[{ value: 'on', label: 'On' }, { value: 'off', label: 'Off' }]}
            onChange={(v) => write({ enabled: v === 'on' }, v === 'on' ? 'Enabled' : 'Disabled')}
          />
        </SettingRow>
        <SettingRow label="Amount">
          <CommitNumber
            label="Amount"
            unit="%"
            value={amount === 'mixed' || amount == null ? null : Math.round(amount * 100)}
            mixed={amount === 'mixed'}
            min={0}
            max={100}
            onCommit={(n) => n != null && write({ amount: n / 100 }, `Amount ${n}%`)}
          />
        </SettingRow>
        <SettingRow label="Blend">
          <SegmentButtons
            value={blend === 'mixed' || blend == null ? null : blend}
            options={BLENDS}
            onChange={(v) => write({ blendMode: v }, `Blend ${BLENDS.find((b) => b.value === v)?.label}`)}
          />
        </SettingRow>
        <SettingRow label="Mask">
          <div className="flex flex-wrap gap-1">
            {MASK_FAMILIES.map((family) => {
              const on = mask !== 'mixed' && (mask == null ? true : maskSet(mask).has(family))
              return (
                <button
                  key={family}
                  type="button"
                  aria-pressed={on}
                  className={cn('rounded border px-1.5 py-0.5 text-[11px]', on ? 'bg-muted font-semibold' : 'text-muted-foreground', mask === 'mixed' && 'border-dashed')}
                  onClick={() => {
                    const current = mask === 'mixed' || mask == null ? new Set<string>(MASK_FAMILIES) : maskSet(mask)
                    if (current.has(family)) current.delete(family)
                    else current.add(family)
                    if (current.size === 0) return
                    const next = MASK_FAMILIES.filter((f) => current.has(f))
                    write({ propertyMask: next.length === MASK_FAMILIES.length ? null : next.join(',') }, 'Mask')
                  }}
                >
                  {FAMILY_LABELS[family].singular}
                </button>
              )
            })}
          </div>
        </SettingRow>
        <SettingRow label="Stomp">
          <SegmentButtons
            value={stomp === 'mixed' ? null : stomp ? 'on' : 'off'}
            options={[{ value: 'off', label: 'Off' }, { value: 'on', label: 'Stomp' }]}
            onChange={(v) => write({ stomp: v === 'on' }, v === 'on' ? 'Stomp on' : 'Stomp off')}
          />
        </SettingRow>
        <SettingRow label="Timing">
          <div className="grid grid-cols-3 gap-1">
            <CommitNumber label="Delay" unit="ms" value={delay === 'mixed' ? null : (delay ?? null)} mixed={delay === 'mixed'} min={0} nullable onCommit={(n) => write({ delayMs: n }, 'Delay')} />
            <CommitNumber label="Every" unit="ms" value={interval === 'mixed' ? null : (interval ?? null)} mixed={interval === 'mixed'} min={0} nullable onCommit={(n) => write({ intervalMs: n }, 'Interval')} />
            <CommitNumber label="Random" unit="ms" value={random === 'mixed' ? null : (random ?? null)} mixed={random === 'mixed'} min={0} nullable onCommit={(n) => write({ randomWindowMs: n }, 'Random window')} />
          </div>
        </SettingRow>
        <SettingRow label="Masters">
          <div className="grid grid-cols-2 gap-1">
            {(
              [
                ['Speed', speed, 'speedMasterUuid'],
                ['Rate', rate, 'rateSpeedMasterUuid'],
              ] as const
            ).map(([label, current, field]) => (
              <select
                key={field}
                aria-label={`${label} master`}
                className="h-7 rounded-md border bg-background px-1 text-xs"
                value={current === 'mixed' ? 'mixed' : (current ?? '')}
                onChange={(e) => e.target.value !== 'mixed' && write({ [field]: e.target.value || null }, `${label} master`)}
              >
                {current === 'mixed' && <option value="mixed">{label} · mixed</option>}
                <option value="">{label} · none</option>
                {(masters ?? []).map((m) => (
                  <option key={m.uuid} value={m.uuid}>{label} · M{m.masterIndex}</option>
                ))}
              </select>
            ))}
          </div>
        </SettingRow>
        {wide && (
          <p className="text-[11px] text-muted-foreground">
            {wide.layer.source.name}&apos;s layer covers {wide.layer.targets.length} targets — one write changes every cell it covers.
          </p>
        )}
        <p className="text-[11px] text-muted-foreground">Writes the cue and sends nothing to the stage.</p>
        {sources.size === 1 && first && <OpenSourceButton projectId={projectId} source={first.source} />}
      </PopoverContent>
    </Popover>
  )
}

/** *Open Look* / *Open palette*: the library, on the record's editor. Mounted only in the open popover. */
function OpenSourceButton({ projectId, source }: { projectId: number; source: TimelineLayer['source'] }) {
  const navigate = useNavigate()
  const look = source.kind === 'LOOK'
  return (
    <div className="flex justify-end">
      <Button variant="ghost" size="sm" onClick={() => navigate(`/projects/${projectId}/${look ? 'looks' : 'palettes'}?edit=${source.id}`)}>
        Open {look ? 'Look' : 'palette'}
      </Button>
    </div>
  )
}

function SettingRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[64px_1fr] items-center gap-2">
      <EditorLabel>{label}</EditorLabel>
      <div className="min-w-0">{children}</div>
    </div>
  )
}

function SegmentButtons({
  value,
  options,
  onChange,
}: {
  value: string | null
  options: readonly { value: string; label: string }[]
  onChange: (value: string) => void
}) {
  return (
    <div className="flex flex-wrap gap-1">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          aria-pressed={value === option.value}
          className={cn(
            'rounded border px-1.5 py-0.5 text-[11px]',
            value === option.value ? 'bg-muted font-semibold' : 'text-muted-foreground',
            value == null && 'border-dashed',
          )}
          onClick={() => value !== option.value && onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

/** A number committed on Enter or blur — never per keystroke, so a typed value is one request. */
function CommitNumber({
  label,
  unit,
  value,
  mixed,
  min,
  max,
  nullable,
  onCommit,
}: {
  label: string
  unit: string
  value: number | null
  mixed: boolean
  min?: number
  max?: number
  nullable?: boolean
  onCommit: (value: number | null) => void
}) {
  const [draft, setDraft] = useState<string | null>(null)
  const commit = () => {
    if (draft == null) return
    const text = draft.trim()
    setDraft(null)
    if (text === '') {
      if (nullable && value != null) onCommit(null)
      return
    }
    const n = Number(text)
    if (!Number.isFinite(n) || (min != null && n < min) || (max != null && n > max)) return
    if (n !== value) onCommit(Math.round(n))
  }
  return (
    <span className="relative block">
      <Input
        aria-label={label}
        className="h-7 pr-6 text-xs"
        inputMode="numeric"
        placeholder={mixed ? 'mixed' : nullable ? label : ''}
        value={draft ?? (value == null ? '' : String(value))}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault()
            commit()
          }
          if (e.key === 'Escape') setDraft(null)
        }}
      />
      <span className="pointer-events-none absolute inset-y-0 right-1.5 flex items-center text-[10px] text-muted-foreground">{unit}</span>
    </span>
  )
}

// ── A scenery cell ────────────────────────────────────────────────────────────────────────────────

/**
 * A scenery cell's popover (D9): the element's states for its kind and the cue's transition, each a
 * write of the cue's change, and *Clear change*, which hands the cue back to tracking.
 */
export function TimelineSceneryPopover({
  anchor,
  element,
  cueLabel,
  state,
  tracked,
  transitionMs,
  onClose,
  onSet,
  onClear,
}: {
  anchor: () => Element | null
  element: StageElementDto
  cueLabel: string
  state: SceneryState | null
  tracked: SceneryState | null
  transitionMs: number | null
  onClose: () => void
  onSet: (state: SceneryState, transitionMs: number | null) => void
  onClear: () => void
}) {
  const virtualRef = useRef({ getBoundingClientRect: () => anchor()?.getBoundingClientRect() ?? new DOMRect() })
  useEffect(() => {
    virtualRef.current = { getBoundingClientRect: () => anchor()?.getBoundingClientRect() ?? new DOMRect() }
  }, [anchor])
  const shown = shownSceneryState(element, tracked ?? undefined, state ?? undefined)
  return (
    <Popover open onOpenChange={(open) => !open && onClose()}>
      <PopoverAnchor virtualRef={virtualRef} />
      <PopoverContent className="w-72 space-y-3 p-3" data-timeline-scenery>
        <div className="text-sm font-semibold">
          {element.name} in {cueLabel} <span className="font-normal text-muted-foreground">{state ? 'change' : 'tracked'}</span>
        </div>
        <SceneryControl
          element={element}
          state={shown}
          commit="release"
          showLabel={false}
          onWrite={(patch) => onSet({ ...(state ?? {}), ...patch }, transitionMs)}
        />
        <div className="grid grid-cols-[64px_1fr] items-center gap-2">
          <EditorLabel>Over</EditorLabel>
          <CommitNumber
            label="Transition"
            unit="ms"
            value={transitionMs}
            mixed={false}
            min={0}
            nullable
            onCommit={(n) => onSet(state ?? shown, n)}
          />
        </div>
        <div className="flex justify-end">
          <Button variant="outline" size="sm" disabled={state == null} onClick={onClear}>
            <Trash2 className="size-3.5" />
            Clear change
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  )
}

// ── + Column ──────────────────────────────────────────────────────────────────────────────────────

/**
 * *+ Column* (D11): searches the Looks and palettes; a palette takes the desk selection's first
 * target unless another is picked, a Look its own fixtures. The column is this window's until one
 * of its cells is filled, after which it is derived like any other.
 */
export function TimelineAddColumn({
  projectId,
  deskTargets,
  targetName,
  onAdd,
}: {
  projectId: number
  deskTargets: readonly CueTarget[]
  targetName: (target: CueTarget) => string
  onAdd: (column: StoredExtraColumn) => void
}) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [targetKey, setTargetKey] = useState<string | null>(null)
  const { data: looks } = useLookListQuery({ projectId }, { skip: !open })
  const { data: palettes } = useTemplateListQuery({ projectId }, { skip: !open })
  const targets = useMemo(() => new Map<string, CueTarget>(deskTargets.map((t) => [`${t.type}:${t.key}`, t])), [deskTargets])
  const q = query.trim().toLowerCase()
  const matches = (name: string) => q === '' || name.toLowerCase().includes(q)
  const picked = targetKey == null ? undefined : targetKey === '*' ? null : (targets.get(targetKey) ?? null)
  const paletteTarget = picked === undefined ? (deskTargets[0] ?? null) : picked
  const add = (column: StoredExtraColumn) => {
    onAdd(column)
    setOpen(false)
    setQuery('')
  }
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button variant="outline" size="sm" className="h-7 shrink-0 gap-1.5 px-2 text-xs" data-timeline-add-column>
          <Plus className="size-3.5" />
          Column
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-72 space-y-2 p-2">
        <Input aria-label="Find a Look or palette" placeholder="Find a Look or palette" className="h-8 text-sm" value={query} onChange={(e) => setQuery(e.target.value)} autoFocus />
        <select
          aria-label="On"
          className="h-7 w-full rounded-md border bg-background px-2 text-xs"
          value={targetKey ?? (deskTargets[0] ? `${deskTargets[0].type}:${deskTargets[0].key}` : '*')}
          onChange={(e) => setTargetKey(e.target.value)}
        >
          <option value="*">Own fixtures / heads</option>
          {deskTargets.map((t) => (
            <option key={`${t.type}:${t.key}`} value={`${t.type}:${t.key}`}>{targetName(t)}, the selection</option>
          ))}
        </select>
        <div className="max-h-64 space-y-2 overflow-y-auto">
          <ListGroup label="Looks">
            {(looks ?? []).filter((l) => matches(l.name)).map((l) => (
              <ListItem
                key={`look-${l.id}`}
                label={l.name}
                sub={picked == null ? 'own fixtures' : targetName(picked)}
                onPick={() => add({ kind: 'look', sourceId: l.id, name: l.name, target: picked ?? null, families: l.families })}
              />
            ))}
          </ListGroup>
          <ListGroup label="Palettes">
            {(palettes ?? []).filter((p) => matches(p.name)).map((p) => (
              <ListItem
                key={`palette-${p.id}`}
                label={p.name}
                sub={paletteTarget == null ? 'own heads' : targetName(paletteTarget)}
                onPick={() => add({ kind: 'palette', sourceId: p.id, name: p.name, target: paletteTarget, families: p.family ? [p.family] : [] })}
              />
            ))}
          </ListGroup>
        </div>
      </PopoverContent>
    </Popover>
  )
}

function ListGroup({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <EditorLabel>{label}</EditorLabel>
      <div className="mt-1 flex flex-col">{children}</div>
    </div>
  )
}

function ListItem({ label, sub, onPick }: { label: string; sub: string; onPick: () => void }) {
  return (
    <button type="button" className="flex items-baseline justify-between gap-2 rounded px-1.5 py-1 text-left text-sm hover:bg-muted" onClick={onPick}>
      <span className="truncate">{label}</span>
      <span className="shrink-0 text-[10px] text-muted-foreground">{sub}</span>
    </button>
  )
}

/** The ⌘D and ⌘Z keys over the Timeline, standing aside for a field, a dialog or a key region. */
export function useTimelineCommandKeys({
  onFillDown,
  onUndo,
  guarded,
}: {
  onFillDown: () => boolean
  onUndo: () => boolean
  guarded: (e: KeyboardEvent) => boolean
}) {
  const ref = useRef({ onFillDown, onUndo, guarded })
  ref.current = { onFillDown, onUndo, guarded }
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (!(e.metaKey || e.ctrlKey) || e.altKey || e.shiftKey) return
      const key = e.key.toLowerCase()
      if (key !== 'd' && key !== 'z') return
      if (e.defaultPrevented || ref.current.guarded(e)) return
      const claimed = key === 'd' ? ref.current.onFillDown() : ref.current.onUndo()
      if (claimed) e.preventDefault()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])
}

