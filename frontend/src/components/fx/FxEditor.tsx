import { useCallback, useEffect, useMemo, useRef, useState, type MutableRefObject, type ReactNode } from 'react'
import { ChevronDown, ChevronRight, Pause, Play, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Slider } from '@/components/ui/slider'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { cn } from '@/lib/utils'
import { lightingApi } from '@/api/lightingApi'
import { dmxToDegrees, degreesToDmx } from '@/lib/axisDegrees'
import { groupMemberFixtures } from '@/lib/fxTargetProperties'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import {
  useEffectLibraryQuery,
  usePauseFxMutation,
  useResumeFxMutation,
  type ActiveEffect,
  type EffectLibraryEntry,
  type EffectParameterDef,
} from '@/store/fixtureFx'
import { usePauseGroupFxMutation, useResumeGroupFxMutation } from '@/store/groups'
import { useMaster1Uuid, useSpeedMasterLiveQuery } from '@/store/speedMasters'
import { useIsDeskConnected } from '@/store/status'
import type { Fixture, SettingPropertyDescriptor } from '@/store/fixtures'
import { EditorLabel } from '../editor/EditorLabel'
import { EditorReadout } from '../editor/EditorReadout'
import { EditorFooter } from '../editor/EditorFooter'
import { useLivePush } from '../editor/useLivePush'
import { SheetField } from '../fixtureSheet/SheetField'
import { fromPct, toPct } from '../fixtures-list/cells/SliderCell'
import { findEffectEntry } from '../busking/buskingTypes'
import { FxColourPicker } from './FxColourPicker'
import { FxColourListPicker } from './FxColourListPicker'
import { detectExtendedChannels } from './colourUtils'
import {
  BLEND_MODE_OPTIONS,
  DISTRIBUTION_STRATEGY_OPTIONS,
  ELEMENT_FILTER_OPTIONS,
  ELEMENT_MODE_OPTIONS,
} from './fxConstants'
import {
  blendForLevelMode,
  centreModeOf,
  clampByte,
  defaultParameters,
  degreesToSize,
  draftOf,
  hasCentre,
  isLevelEffect,
  isRatioParam,
  levelModeOf,
  paramAxis,
  paramRole,
  positionAxesOf,
  OPTIONAL_FIELDS,
  sameRequest,
  sizeToDegrees,
  updateRequestOf,
  withCentreMode,
  type CentreMode,
  type FxDraft,
  type FxScope,
  type LevelMode,
  type OptionalField,
  type PositionAxes,
} from './fxEditorModel'

/**
 * The speed segment, in beats a cycle (Fx board) — ⅛ to 16. A stored division outside the eight
 * (a triplet) presses none of them and the read-out still says what it is.
 */
const SPEED_BEATS: readonly { value: number; label: string }[] = [
  { value: 0.125, label: '⅛' },
  { value: 0.25, label: '¼' },
  { value: 0.5, label: '½' },
  { value: 1, label: '1' },
  { value: 2, label: '2' },
  { value: 4, label: '4' },
  { value: 8, label: '8' },
  { value: 16, label: '16' },
]

const CURVES = [
  'LINEAR',
  'SINE_IN',
  'SINE_OUT',
  'SINE_IN_OUT',
  'QUAD_IN',
  'QUAD_OUT',
  'QUAD_IN_OUT',
  'EXPO_IN',
  'EXPO_OUT',
  'EXPO_IN_OUT',
] as const

/** The board's words for the built-ins' parameters; anything else is its name as words. */
const PARAM_LABELS: Record<string, string> = {
  panCenter: 'Pan',
  tiltCenter: 'Tilt',
  panRadius: 'Pan size',
  tiltRadius: 'Tilt size',
  panRange: 'Pan range',
  tiltRange: 'Tilt range',
  min: 'Low',
  max: 'High',
  attackRatio: 'Attack',
  holdRatio: 'Hold',
  fadeRatio: 'Fade',
  dutyCycle: 'Duty',
  onRatio: 'On',
}

export function paramLabel(name: string): string {
  return (
    PARAM_LABELS[name] ??
    name
      .replace(/([A-Z])/g, ' $1')
      .replace(/^./, (s) => s.toUpperCase())
      .trim()
  )
}

/**
 * The live effect editor (fixture-fx-sheets plan D11, D12, D12a, D15; §3.3) — `EffectParameterForm`'s
 * parameter inputs rebuilt on the editor kit, and the one editor every running effect is edited in:
 * the FX tray's rows (inline), the programmer rail's *Edit…* and `FxSheet`'s chips (both through
 * `EditorSurface`), and the add sheet after the picker.
 *
 * **There is no Apply.** Every control writes as it moves through `useLivePush` — a 50 ms floor, a
 * dedupe on the whole write, the release always landing — into `updateFx`, which swaps the
 * parameters and keeps the effect's id and phase. A typed field commits on Enter. **Revert** sends
 * the settings the editor opened with, in one write; **Done** goes back to the host's list and
 * changes nothing; **×** stops the effect.
 *
 * The draft is the editor's own, seeded when it opens: the operator is the only writer while it is
 * open, and a refetch of the effect list (every write answers one) must not pull a slider back
 * under a finger. A host keys the editor on the effect's id *and* type, so the picker's type swap
 * opens a fresh one.
 *
 * Two questions replace the blend for the effects they are about: a movement effect's *Centre*
 * (D12 — Around is Additive with the centre pinned at 128 and hidden, Absolute is Override with the
 * centre shown) and a level effect's *Over the level underneath* (D12a — Replace is Override, Within
 * is Multiply). The blend itself stays under Advanced for every effect, so nothing it could reach
 * before is out of reach now.
 */
export function FxEditor({
  effect,
  onDone,
  onStop,
  flushRef,
  className,
}: {
  effect: ActiveEffect
  /** Back to the host's list. */
  onDone: () => void
  /** × — the host stops the effect (and asks first where that is its rule). */
  onStop: () => void
  /**
   * Filled with "land whatever move the floor still holds", for a host that acts on the effect as
   * the editor goes — the add sheet absorbing it into a Look on close must write the last move first,
   * or the absorb takes the instance away and the unmount's flush reaches nothing.
   */
  flushRef?: MutableRefObject<(() => void) | null>
  className?: string
}) {
  const { data: library } = useEffectLibraryQuery()
  const entry = useMemo(() => findEffectEntry(library, effect.effectType), [library, effect.effectType])
  const { fixtures, fixtureByKey } = useFixtureLookup()
  const connected = useIsDeskConnected()
  const master1Uuid = useMaster1Uuid()

  const targetFixtures = useMemo((): Fixture[] => {
    if (effect.isGroupTarget) return groupMemberFixtures(fixtures, effect.targetKey)
    const fixture = fixtureByKey.get(effect.targetKey)
    return fixture ? [fixture] : []
  }, [effect.isGroupTarget, effect.targetKey, fixtures, fixtureByKey])

  const scope = useMemo((): FxScope => {
    const multiElement = targetFixtures.some((f) => (f.elements?.length ?? 0) > 1)
    if (effect.isGroupTarget) return { heads: true, elementMode: multiElement, elementFilter: multiElement }
    const heads = targetFixtures.some((f) => (f.elementGroupProperties?.length ?? 0) > 0)
    return { heads, elementMode: false, elementFilter: heads }
  }, [effect.isGroupTarget, targetFixtures])

  const axes = useMemo(() => (entry?.category === 'position' ? positionAxesOf(targetFixtures) : null), [entry, targetFixtures])
  const extendedChannels = useMemo(
    () => (entry?.category === 'colour' ? detectExtendedChannels(targetFixtures.map((f) => f.properties ?? [])) : undefined),
    [entry, targetFixtures],
  )
  const settingProperty = useMemo(() => {
    for (const fixture of targetFixtures) {
      const p = fixture.properties?.find((d) => d.name === effect.propertyName && d.type === 'setting')
      if (p) return p as SettingPropertyDescriptor
    }
    return undefined
  }, [targetFixtures, effect.propertyName])

  const [snapshot] = useState(() => draftOf(effect))
  const [draft, setDraft] = useState<FxDraft>(snapshot)
  const touched = useRef(false)
  /** The optional fields the operator has set — `updateRequestOf` sends only those. */
  const setFields = useRef(new Set<OptionalField>())

  // A write that never left the browser (the socket closed: `updateFx` toasts and answers false) is
  // forgotten as sent, so the next push or release of the same value goes out rather than being
  // deduplicated against a frame the desk never saw.
  const resetRef = useRef<() => void>(() => {})
  const send = useCallback((request: ReturnType<typeof updateRequestOf>) => {
    if (!lightingApi.fx.updateFx(effect.id, request)) resetRef.current()
  }, [effect.id])
  const live = useLivePush(send, { equals: sameRequest })
  resetRef.current = live.reset
  const latest = useRef<ReturnType<typeof updateRequestOf> | null>(null)
  const flushHeld = useCallback(() => {
    if (touched.current && latest.current) live.flush(latest.current)
  }, [live])
  if (flushRef) flushRef.current = flushHeld

  /** A move from a running gesture (`push`) or a release (`flush`). */
  const write = useCallback(
    (next: FxDraft, release: boolean, fields: readonly OptionalField[] = []) => {
      setDraft(next)
      touched.current = true
      for (const field of fields) setFields.current.add(field)
      const request = updateRequestOf(next, setFields.current)
      latest.current = request
      if (release) live.flush(request)
      else live.push(request)
    },
    [live],
  )

  // A move still held by the floor when the editor goes — a Done mid-drag, the host unmounting it —
  // lands rather than being dropped with `useLivePush`'s timer. Declared after the hook, so its
  // cleanup runs after the hook's has cleared the timer, and `flush` dedupes against what was sent.
  const liveRef = useRef(live)
  liveRef.current = live
  useEffect(
    () => () => {
      if (touched.current && latest.current) liveRef.current.flush(latest.current)
    },
    [],
  )

  const revert = () => {
    const request = updateRequestOf(snapshot, setFields.current, master1Uuid)
    // A master the frame could not put back stays drawn as the desk holds it: a rate master has no
    // spelling for *unscaled*, and a never-assigned speed master needs master 1's uuid, which may not
    // have arrived. Drawing the snapshot there would show a state the rig is not in.
    setDraft({
      ...snapshot,
      speedMasterUuid:
        setFields.current.has('speedMasterUuid') && request.speedMasterUuid == null ? draft.speedMasterUuid : snapshot.speedMasterUuid,
      rateSpeedMasterUuid:
        setFields.current.has('rateSpeedMasterUuid') && request.rateSpeedMasterUuid == null
          ? draft.rateSpeedMasterUuid
          : snapshot.rateSpeedMasterUuid,
    })
    latest.current = request
    live.flush(request)
  }

  const [pauseFx] = usePauseFxMutation()
  const [resumeFx] = useResumeFxMutation()
  const [pauseGroupFx] = usePauseGroupFxMutation()
  const [resumeGroupFx] = useResumeGroupFxMutation()
  const togglePause = () => {
    const request = effect.isGroupTarget
      ? (effect.isRunning ? pauseGroupFx : resumeGroupFx)({ id: effect.id, groupName: effect.targetKey })
      : (effect.isRunning ? pauseFx : resumeFx)({ id: effect.id, fixtureKey: effect.targetKey })
    request.unwrap().catch(() => {
      // Reported by errorToastMiddleware.
    })
  }

  const set = (patch: Partial<FxDraft>, release = true) =>
    write(
      { ...draft, ...patch },
      release,
      OPTIONAL_FIELDS.filter((field) => field in patch),
    )
  const setParam = (name: string, value: string, release: boolean) =>
    write({ ...draft, parameters: { ...draft.parameters, [name]: value } }, release)

  const readOnly = !connected
  const name = entry?.name ?? effect.effectType
  const dirty = JSON.stringify(draft) !== JSON.stringify(snapshot)

  return (
    <div data-fx-editor={effect.id} className={cn('flex flex-col gap-3', className)}>
      <div className="flex items-center gap-1.5">
        <span className="min-w-0 truncate text-[13px] font-medium">{name}</span>
        <span className="shrink-0 font-mono text-[11px] text-muted-foreground">→ {effect.propertyName}</span>
        <span className="flex-1" />
        {/* Pause and resume, which every host's editor carries — the rail's and `FxSheet`'s had them
            on `ActiveEffectSheet`, and their rows have no other. */}
        <button
          type="button"
          aria-label={effect.isRunning ? `Pause ${name}` : `Resume ${name}`}
          disabled={readOnly}
          onClick={togglePause}
          className="grid size-6 shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50"
        >
          {effect.isRunning ? <Pause className="size-3.5" /> : <Play className="size-3.5" />}
        </button>
        <button
          type="button"
          aria-label={`Stop ${name}`}
          disabled={readOnly}
          onClick={() => {
            // A move the floor still holds goes before the stop, never after it — sent from the
            // unmount it would reach a stopped effect and come back as `FX_NOT_FOUND`.
            flushHeld()
            onStop()
          }}
          className="grid size-6 shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-destructive disabled:opacity-50"
        >
          <X className="size-3.5" />
        </button>
      </div>

      {entry == null ? (
        <EditorReadout>
          {library == null
            ? 'Reading the effect library…'
            : `“${effect.effectType}” is not in this desk's effect library, so its settings cannot be drawn.`}
        </EditorReadout>
      ) : (
        <EditorBody
          entry={entry}
          effect={effect}
          draft={draft}
          scope={scope}
          axes={axes}
          extendedChannels={extendedChannels}
          settingProperty={settingProperty}
          readOnly={readOnly}
          set={set}
          setParam={setParam}
        />
      )}

      <EditorFooter note="Live — each change lands as you make it">
        <Button variant="outline" size="sm" className="h-7" disabled={readOnly || !dirty} onClick={revert}>
          Revert
        </Button>
        <Button
          size="sm"
          className="h-7"
          onClick={() => {
            flushHeld()
            onDone()
          }}
        >
          Done
        </Button>
      </EditorFooter>
    </div>
  )
}

function EditorBody({
  entry,
  effect,
  draft,
  scope,
  axes,
  extendedChannels,
  settingProperty,
  readOnly,
  set,
  setParam,
}: {
  entry: EffectLibraryEntry
  effect: ActiveEffect
  draft: FxDraft
  scope: FxScope
  axes: PositionAxes | null
  extendedChannels: ReturnType<typeof detectExtendedChannels>
  settingProperty: SettingPropertyDescriptor | undefined
  readOnly: boolean
  set: (patch: Partial<FxDraft>, release?: boolean) => void
  setParam: (name: string, value: string, release: boolean) => void
}) {
  const centred = hasCentre(entry)
  // An absent centre is the library's default (128 on every built-in) — a script or a cue may store a
  // Circle without spelling it, and that is still Around.
  const centreMode = centred ? centreModeOf(draft.blendMode, { ...defaultParameters(entry), ...draft.parameters }) : null
  const level = isLevelEffect(entry)
  const levelMode = level ? levelModeOf(draft.blendMode) : null

  const roles = entry.parameters.map((param) => ({ param, role: paramRole(entry, param) }))
  // Around hides the centre: it is pinned at 128, and showing it would offer a knob that turns the
  // orbit into an offset nobody asked for.
  const main = roles.filter(({ role }) => role !== 'shape' && !(role === 'centre' && centreMode === 'around'))
  const shape = roles.filter(({ role }) => role === 'shape')

  const paramControl = ({ param, role }: { param: EffectParameterDef; role: ReturnType<typeof paramRole> }) => (
    <ParamControl
      key={param.name}
      param={param}
      role={role}
      value={draft.parameters[param.name] ?? param.defaultValue}
      axes={axes}
      extendedChannels={extendedChannels}
      settingProperty={param.name === 'level' ? settingProperty : undefined}
      readOnly={readOnly}
      onChange={(value, release) => setParam(param.name, value, release)}
    />
  )

  return (
    <>
      <SpeedSection effect={effect} entry={entry} draft={draft} readOnly={readOnly} set={set} />

      {centred && (
        <Question
          label="Centre"
          value={centreMode}
          readOnly={readOnly}
          options={[
            { value: 'around', label: 'Around current position' },
            { value: 'absolute', label: 'Absolute' },
          ]}
          onChange={(mode) => set(withCentreMode(draft, mode as CentreMode))}
          readout={
            centreMode === 'around'
              ? 'Orbits whatever holds the position underneath — your value, else the cue’s, else the base.'
              : centreMode === 'absolute'
                ? 'Moves about the centre set here, whatever is underneath.'
                : `Blended ${draft.blendMode.toLowerCase()} — see Advanced.`
          }
        />
      )}

      {level && (
        <Question
          label="Over the level underneath"
          value={levelMode}
          readOnly={readOnly}
          options={[
            { value: 'replace', label: 'Replace it' },
            { value: 'within', label: 'Within it' },
          ]}
          onChange={(mode) => set({ blendMode: blendForLevelMode(mode as LevelMode) })}
          readout={
            levelMode === 'replace'
              ? 'Runs between its low and high whatever the level underneath.'
              : levelMode === 'within'
                ? 'Runs inside the level underneath — scaled by it.'
                : `Blended ${draft.blendMode.toLowerCase()} — see Advanced.`
          }
        />
      )}

      {main.length > 0 && <div className="flex flex-col gap-2.5">{main.map(paramControl)}</div>}

      {shape.length > 0 && (
        <Disclosure
          label="Shape"
          summary={shape.map(({ param }) => shapeSummary(param, draft.parameters[param.name] ?? param.defaultValue))}
        >
          {shape.map(paramControl)}
        </Disclosure>
      )}

      <Disclosure
        label="Advanced"
        summary={[
          `phase ${Math.round(draft.phaseOffset * 360)}°`,
          draft.blendMode.toLowerCase(),
          ...(scope.heads ? [`step timing ${draft.stepTiming ? 'on' : 'off'}`] : []),
        ]}
      >
        <SliderRow
          label="Phase"
          unit="°"
          value={draft.phaseOffset}
          min={0}
          max={1}
          step={0.01}
          display={(v) => Math.round(v * 360)}
          fromDisplay={(d) => Math.max(0, Math.min(1, d / 360))}
          displayMax={360}
          readOnly={readOnly}
          onChange={(v, release) => set({ phaseOffset: v }, release)}
        />
        <SelectRow
          label="Blend"
          value={draft.blendMode}
          readOnly={readOnly}
          options={BLEND_MODE_OPTIONS}
          onChange={(blendMode) => set({ blendMode })}
        />
        {scope.heads && (
          <label className="flex items-center gap-2 text-xs">
            <input
              type="checkbox"
              checked={draft.stepTiming}
              disabled={readOnly}
              onChange={(e) => set({ stepTiming: e.target.checked })}
              className="rounded border-input"
            />
            <span>Step timing</span>
            <span className="text-muted-foreground">the speed steps from head to head</span>
          </label>
        )}
        {scope.heads && (
          <SelectRow
            label="Distribution"
            value={draft.distributionStrategy}
            readOnly={readOnly}
            options={DISTRIBUTION_STRATEGY_OPTIONS}
            onChange={(distributionStrategy) => set({ distributionStrategy })}
          />
        )}
        {scope.elementMode && (
          <SelectRow
            label="Element mode"
            value={draft.elementMode}
            readOnly={readOnly}
            options={ELEMENT_MODE_OPTIONS}
            onChange={(elementMode) => set({ elementMode })}
          />
        )}
        {scope.elementFilter && (
          <SelectRow
            label="Heads"
            value={draft.elementFilter}
            readOnly={readOnly}
            options={ELEMENT_FILTER_OPTIONS}
            onChange={(elementFilter) => set({ elementFilter })}
          />
        )}
      </Disclosure>
    </>
  )
}

// ─── Speed ────────────────────────────────────────────────────────────────────────────────────

/**
 * Speed is a segment, with the master as a chip (Fx board). A beat effect's segment is beats a
 * cycle and its chip the speed master; a wall-clock effect's speed is seconds a cycle (typed — its
 * division is seconds, not beats) and its chip the **rate** master, which scales the cycle. The
 * chip names a concrete master: the frame cannot say null, so a pick of M1 sends master 1's uuid,
 * which the desk treats as the default (`SpeedMasterSelect`'s rule).
 */
function SpeedSection({
  effect,
  entry,
  draft,
  readOnly,
  set,
}: {
  effect: ActiveEffect
  entry: EffectLibraryEntry
  draft: FxDraft
  readOnly: boolean
  set: (patch: Partial<FxDraft>, release?: boolean) => void
}) {
  const wallClock = (entry.timingSource ?? effect.timingSource) === 'WALL_CLOCK'
  const masterUuid = wallClock ? draft.rateSpeedMasterUuid : draft.speedMasterUuid
  const chip = (
    <MasterChip
      kind={wallClock ? 'rate' : 'speed'}
      value={masterUuid}
      readOnly={readOnly}
      onChange={(uuid) => set(wallClock ? { rateSpeedMasterUuid: uuid } : { speedMasterUuid: uuid })}
    />
  )
  const pressed = SPEED_BEATS.find((o) => Math.abs(o.value - draft.beatDivision) < 0.001)

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center gap-2">
        <EditorLabel>Speed</EditorLabel>
        <span className="flex-1" />
        {chip}
      </div>
      {wallClock ? (
        <div className="flex items-center gap-2">
          <SheetField
            label="Cycle seconds"
            unit="s"
            value={draft.beatDivision}
            min={0.05}
            max={600}
            step={0.1}
            disabled={readOnly}
            onEnter={(n) => n > 0 && set({ beatDivision: n })}
          />
          <EditorReadout>seconds a cycle</EditorReadout>
        </div>
      ) : (
        <>
          <ToggleGroup
            type="single"
            size="sm"
            value={pressed ? String(pressed.value) : ''}
            disabled={readOnly}
            onValueChange={(v) => v && set({ beatDivision: Number(v) })}
            aria-label="Speed"
            className="flex w-full flex-wrap justify-start"
          >
            {SPEED_BEATS.map((option) => (
              <ToggleGroupItem
                key={option.value}
                value={String(option.value)}
                aria-label={`${option.label} ${option.value === 1 ? 'beat' : 'beats'} a cycle`}
                className="min-w-8 flex-1 px-1.5 text-xs"
              >
                {option.label}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
          {/* Any division the segment does not name — a triplet's ⅓ — is typed here, as the old
              form's note values reached it; the segment is the common eight. */}
          <div className="flex items-center gap-2">
            <SheetField
              label="Beats a cycle"
              value={Math.round(draft.beatDivision * 1000) / 1000}
              min={0.01}
              max={256}
              step={0.125}
              disabled={readOnly}
              onEnter={(n) => n > 0 && set({ beatDivision: n })}
            />
            <EditorReadout>{pressed ? 'beats a cycle' : 'beats a cycle — not one of the eight'}</EditorReadout>
          </div>
        </>
      )}
    </div>
  )
}

/** The master as a chip — `M2 · 96`, `Unscaled` for a wall-clock effect with no rate master. */
function MasterChip({
  kind,
  value,
  readOnly,
  onChange,
}: {
  kind: 'speed' | 'rate'
  value: string | null
  readOnly: boolean
  onChange: (uuid: string) => void
}) {
  const { data: masters } = useSpeedMasterLiveQuery(undefined)
  const listable = (masters ?? []).filter((m) => m.uuid != null)
  const current =
    value != null ? listable.find((m) => m.uuid === value) : kind === 'speed' ? listable.find((m) => m.index === 1) : undefined
  const label = current ? `M${current.index} · ${Math.round(current.bpm)}` : kind === 'rate' ? 'Unscaled' : 'M1'
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          disabled={readOnly || listable.length === 0}
          aria-label={kind === 'rate' ? `Rate master: ${label}` : `Speed master: ${label}`}
          className="inline-flex h-5 shrink-0 items-center gap-1 rounded-full border px-2 font-mono text-[10px] text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50"
        >
          {kind === 'rate' && <span className="font-sans">Rate</span>}
          {label}
          <ChevronDown className="size-3" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuRadioGroup value={current?.uuid ?? ''} onValueChange={onChange}>
          {listable.map((m) => (
            <DropdownMenuRadioItem key={m.uuid} value={m.uuid!} className="text-xs">
              M{m.index} · {m.name}
              <span className="ml-2 tabular-nums text-muted-foreground">{Math.round(m.bpm * 10) / 10} bpm</span>
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

// ─── Questions, disclosures, rows ─────────────────────────────────────────────────────────────

function Question({
  label,
  value,
  options,
  readOnly,
  onChange,
  readout,
}: {
  label: string
  value: string | null
  options: { value: string; label: string }[]
  readOnly: boolean
  onChange: (value: string) => void
  readout: ReactNode
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <EditorLabel>{label}</EditorLabel>
      <ToggleGroup
        type="single"
        size="sm"
        value={value ?? ''}
        disabled={readOnly}
        aria-label={label}
        onValueChange={(v) => v && onChange(v)}
        className="flex w-full"
      >
        {options.map((option) => (
          <ToggleGroupItem key={option.value} value={option.value} className="min-w-0 flex-1 truncate px-2 text-xs">
            {option.label}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
      <EditorReadout>{readout}</EditorReadout>
    </div>
  )
}

/** A folded section whose summary line says what is inside — *Shape · attack 10% · quad out*. */
function Disclosure({ label, summary, children }: { label: string; summary: string[]; children: ReactNode }) {
  const [open, setOpen] = useState(false)
  return (
    <div className="flex flex-col gap-2.5">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="flex min-w-0 items-center gap-1 text-left text-[11px] text-muted-foreground hover:text-foreground"
      >
        {open ? <ChevronDown className="size-3.5 shrink-0" /> : <ChevronRight className="size-3.5 shrink-0" />}
        <span className="font-medium">{label}</span>
        {!open && summary.length > 0 && <span className="truncate">· {summary.join(' · ')}</span>}
      </button>
      {open && <div className="flex flex-col gap-2.5 border-l-2 border-muted pl-2.5">{children}</div>}
    </div>
  )
}

function shapeSummary(param: EffectParameterDef, value: string): string {
  const type = param.type.toLowerCase()
  const label = paramLabel(param.name).toLowerCase()
  if (type === 'easingcurve') return value.toLowerCase().replace(/_/g, ' ')
  if (type === 'boolean') return `${label} ${value === 'true' ? 'on' : 'off'}`
  if (isRatioParam(param)) return `${label} ${Math.round(Number(value) * 100)}%`
  return `${label} ${value}`
}

/**
 * A slider beside an Enter-committing field. The slider works in the stored value and writes as it
 * moves (`push`), landing on release (`flush`); the field shows [display] of it in [unit].
 */
function SliderRow({
  label,
  unit,
  value,
  min,
  max,
  step,
  display = (v) => v,
  fromDisplay = (d) => d,
  displayMin,
  displayMax,
  readOnly,
  onChange,
}: {
  label: string
  unit?: string
  value: number
  min: number
  max: number
  step: number
  display?: (value: number) => number
  fromDisplay?: (display: number) => number
  displayMin?: number
  displayMax?: number
  readOnly: boolean
  onChange: (value: number, release: boolean) => void
}) {
  return (
    <div className="flex flex-col gap-1">
      <EditorLabel>{label}</EditorLabel>
      <div className="flex items-center gap-2">
        <Slider
          aria-label={label}
          value={[Math.max(min, Math.min(max, value))]}
          min={min}
          max={max}
          step={step}
          disabled={readOnly}
          onValueChange={([v]) => v !== undefined && onChange(v, false)}
          onValueCommit={([v]) => v !== undefined && onChange(v, true)}
          className="min-w-[60px] flex-1"
        />
        <SheetField
          label={`${label}${unit ? ` ${unitWord(unit)}` : ''}`}
          unit={unit}
          value={Math.round(display(value) * 100) / 100}
          min={displayMin ?? display(min)}
          max={displayMax ?? display(max)}
          disabled={readOnly}
          onEnter={(d) => onChange(Math.max(min, Math.min(max, fromDisplay(d))), true)}
        />
      </div>
    </div>
  )
}

function unitWord(unit: string): string {
  if (unit === '%') return 'percent'
  if (unit === '°') return 'degrees'
  if (unit === 's') return 'seconds'
  return unit
}

function SelectRow({
  label,
  value,
  options,
  readOnly,
  onChange,
}: {
  label: string
  value: string
  options: readonly { value: string; label: string; description?: string }[]
  readOnly: boolean
  onChange: (value: string) => void
}) {
  return (
    <div className="flex flex-col gap-1">
      <EditorLabel>{label}</EditorLabel>
      <Select value={value} onValueChange={onChange} disabled={readOnly}>
        <SelectTrigger aria-label={label} className="h-7 text-xs">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {options.map((option) => (
            <SelectItem key={option.value} value={option.value} className="text-xs">
              <span>{option.label}</span>
              {option.description && <span className="ml-2 text-muted-foreground">{option.description}</span>}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  )
}

/**
 * One parameter, in its role's unit (`paramRole`): a level as a percent, a size in degrees of travel
 * where the head annotates and bytes elsewhere, a centre or a sweep's end as a position on its axis,
 * a ratio as a percent, a curve as a list, a colour through the FX colour pickers.
 */
function ParamControl({
  param,
  role,
  value,
  axes,
  extendedChannels,
  settingProperty,
  readOnly,
  onChange,
}: {
  param: EffectParameterDef
  role: ReturnType<typeof paramRole>
  value: string
  axes: PositionAxes | null
  extendedChannels: ReturnType<typeof detectExtendedChannels>
  settingProperty: SettingPropertyDescriptor | undefined
  readOnly: boolean
  onChange: (value: string, release: boolean) => void
}) {
  const type = param.type.toLowerCase()
  const label = paramLabel(param.name)
  const num = Number(value) || 0

  if (settingProperty != null && settingProperty.options.length > 0) {
    return (
      <SelectRow
        label="Setting"
        value={value}
        readOnly={readOnly}
        options={settingProperty.options.map((o) => ({ value: String(o.level), label: o.displayName }))}
        onChange={(v) => onChange(v, true)}
      />
    )
  }

  if (type === 'ubyte') {
    const axisName = paramAxis(param.name)
    const axis = axes != null && axisName != null ? axes[axisName] : null
    if ((role === 'size') && axis != null) {
      return (
        <SliderRow
          label={label}
          unit="°"
          value={num}
          min={0}
          max={255}
          step={1}
          display={(v) => Math.round(sizeToDegrees(v, axis))}
          fromDisplay={(d) => degreesToSize(d, axis)}
          readOnly={readOnly}
          onChange={(v, release) => onChange(String(clampByte(v)), release)}
        />
      )
    }
    if ((role === 'centre' || role === 'axis') && axis != null) {
      return (
        <SliderRow
          label={label}
          unit="°"
          value={num}
          min={0}
          max={255}
          step={1}
          display={(v) => Math.round(dmxToDegrees(v, axis) ?? 0)}
          fromDisplay={(d) => degreesToDmx(d, axis) ?? 0}
          displayMin={Math.min(axis.degMin, axis.degMax)}
          displayMax={Math.max(axis.degMin, axis.degMax)}
          readOnly={readOnly}
          onChange={(v, release) => onChange(String(clampByte(v)), release)}
        />
      )
    }
    if (role === 'level') {
      return (
        <SliderRow
          label={label}
          unit="%"
          value={num}
          min={0}
          max={255}
          step={1}
          display={toPct}
          fromDisplay={(d) => fromPct(Math.max(0, Math.min(100, d)))}
          displayMin={0}
          displayMax={100}
          readOnly={readOnly}
          onChange={(v, release) => onChange(String(clampByte(v)), release)}
        />
      )
    }
    return (
      <SliderRow
        label={label}
        value={num}
        min={0}
        max={255}
        step={1}
        readOnly={readOnly}
        onChange={(v, release) => onChange(String(clampByte(v)), release)}
      />
    )
  }

  if (type === 'double' || type === 'float') {
    if (isRatioParam(param)) {
      return (
        <SliderRow
          label={label}
          unit="%"
          value={num}
          min={0}
          max={1}
          step={0.01}
          display={(v) => Math.round(v * 100)}
          fromDisplay={(d) => Math.max(0, Math.min(1, d / 100))}
          displayMin={0}
          displayMax={100}
          readOnly={readOnly}
          onChange={(v, release) => onChange(String(v), release)}
        />
      )
    }
    // The wire declares no range (`FU-FE-FX-PARAM-RANGE`); a double defaulting above 1 is a guess at
    // 0–10, which only a script's effect reaches. The field takes anything typed.
    return (
      <SliderRow
        label={label}
        value={num}
        min={0}
        max={10}
        step={0.1}
        displayMax={1_000_000}
        readOnly={readOnly}
        onChange={(v, release) => onChange(String(v), release)}
      />
    )
  }

  if (type === 'int') {
    // From the declared default and nothing else, so the range never moves under a drag
    // (`EffectParameterForm`'s rule); the field reaches a value outside it.
    const max = Math.max(255, (Number(param.defaultValue) || 0) * 2)
    return (
      <SliderRow
        label={label}
        unit={/Ms$/.test(param.name) ? 'ms' : undefined}
        value={num}
        min={0}
        max={max}
        step={1}
        displayMax={1_000_000}
        readOnly={readOnly}
        onChange={(v, release) => onChange(String(Math.round(v)), release)}
      />
    )
  }

  if (type === 'boolean') {
    return (
      <label className="flex items-center gap-2 text-xs">
        <input
          type="checkbox"
          checked={value === 'true'}
          disabled={readOnly}
          onChange={(e) => onChange(String(e.target.checked), true)}
          className="rounded border-input"
        />
        <span>{label}</span>
        {param.description && <span className="text-muted-foreground">{param.description}</span>}
      </label>
    )
  }

  if (type === 'easingcurve') {
    return (
      <SelectRow
        label={label}
        value={value}
        readOnly={readOnly}
        options={CURVES.map((c) => ({ value: c, label: c.toLowerCase().replace(/_/g, ' ') }))}
        onChange={(v) => onChange(v, true)}
      />
    )
  }

  if (type === 'colour') {
    return (
      <FxColourPicker
        value={value}
        onChange={(v) => onChange(v, false)}
        label={label}
        description={param.description}
        extendedChannels={extendedChannels}
      />
    )
  }

  if (type === 'colourlist') {
    return (
      <FxColourListPicker
        value={value}
        onChange={(v) => onChange(v, true)}
        label={label}
        description={param.description}
        extendedChannels={extendedChannels}
      />
    )
  }

  return <TextParam label={label} value={value} readOnly={readOnly} onEnter={(v) => onChange(v, true)} />
}

/** A parameter the editor has no control for: a text field, committed on Enter. */
function TextParam({
  label,
  value,
  readOnly,
  onEnter,
}: {
  label: string
  value: string
  readOnly: boolean
  onEnter: (value: string) => void
}) {
  const [text, setText] = useState<string | null>(null)
  return (
    <div className="flex flex-col gap-1">
      <EditorLabel>{label}</EditorLabel>
      <input
        aria-label={label}
        disabled={readOnly}
        value={text ?? value}
        onChange={(e) => setText(e.target.value)}
        onBlur={() => setText(null)}
        onKeyDown={(e) => {
          if (e.key !== 'Enter' || text == null) return
          e.preventDefault()
          onEnter(text)
          setText(null)
        }}
        className="h-7 rounded-md border bg-transparent px-2 text-xs"
      />
    </div>
  )
}
