import { useCallback, useMemo, useState, type RefObject } from 'react'
import { DndContext, KeyboardSensor, PointerSensor, closestCenter, useSensor, useSensors, type DragEndEvent } from '@dnd-kit/core'
import { SortableContext, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { ChevronDown, ChevronRight, GripVertical, Plus, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { TimingBadge } from '@/components/cues/TimingBadge'
import { TimingFields } from '@/components/cues/TimingEditor'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { EditorReadout } from '@/components/editor/EditorReadout'
import { EffectSummary } from '@/components/fx/EffectSummary'
import { fromLookEffect } from '@/components/fx/effectSummaryTypes'
import { FxEffectFields } from '@/components/fx/FxEffectFields'
import { FxPicker, type DraftEffectPick } from '@/components/fx/FxPicker'
import { selectionKeyOf, storedEffectOf } from '@/components/fx/draftEffect'
import { effectSpeedLabel, secondsLabel } from '@/components/fx/fxConstants'
import { cueEffectDraftOf, runsWord, withCueEffectDraft, type FxDraft } from '@/components/fx/fxEditorModel'
import { useEffectScope } from '@/components/fx/useEffectScope'
import { effectTargetOf } from '@/components/fx/selectionEffectTarget'
import { findEffectEntry } from '@/components/busking/buskingTypes'
import { effectDynamicsParts } from '@/lib/effectDynamics'
import { isOnSeconds } from '@/lib/effectTiming'
import { effectTargetLabel, storedEffectTargets, type EffectTargetFields } from '@/lib/effectTargets'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { useEffectLibraryQuery, type EffectLibraryEntry } from '@/store/fixtureFx'
import { useGroupListQuery } from '@/store/groups'
import { useLookQuery } from '@/store/looks'
import { useSpeedMasterDisplay } from '@/store/speedMasters'
import { DEFERRED_TARGET_TYPE } from '@/api/looksApi'
import type { Cue, CueAdHocEffect, CueLayerDetail, CueTarget } from '@/api/cuesApi'
import { useCueRowStore } from './CueRowStore'
import { useCueValuesView } from './CueValuesView'
import { CueEditorIsolation, useRefusedEditKeys } from './editorIsolation'
import { appendItem, changeDrawn, effectId, indexOfDrawn, moveDrawn, removeDrawn, replaceAt } from './cueListEdits'
import { NO_VALUES_SELECTION } from './CueLooksTab'

const NO_SELECTION: readonly CueTarget[] = []

/**
 * The cue editor's **FX** tab (show-cue-editing plan §4.1, D17): one row per stored effect — its
 * target, effect and property, its speed (a master, or *Seconds*), size, direction and runs, and its
 * fire timing — reorderable. A row opens `FxEffectFields` over the stored effect
 * (`cueEffectDraftOf` / `withCueEffectDraft`), its `FxScope` from the effect's target, with the
 * cue's *Delay · Interval · Random* under it. *+ Effect* opens `FxPicker` **in draft** over the
 * Values selection: a tap adds the effect to the cue with its defaults and runs nothing, a second tap
 * swaps it. Each Look layer's deferred effects are listed under the Look, read-only — they are the
 * Look's, changed in the Looks library.
 *
 * Every write is the cue's whole `adHocEffects` list through the editor's `CueRowStore`, every
 * `sortOrder` restated: a stored write, sending nothing to the stage (P2) — a live cue picks it up
 * on its next first hold. A drag's moves coalesce (400 ms, 2 s); a release, a reorder or an add saves
 * at once. Locked, the rows read.
 */
export function CueEffectsTab(props: {
  cue: Cue
  projectId: number
  readOnlyReason: string | null
  region: RefObject<HTMLElement | null>
  onRequestUnlock?: () => void
}) {
  return (
    <CueEditorIsolation>
      <EffectsBody {...props} />
    </CueEditorIsolation>
  )
}

function EffectsBody({
  cue,
  projectId,
  readOnlyReason,
  region,
  onRequestUnlock,
}: {
  cue: Cue
  projectId: number
  readOnlyReason: string | null
  region: RefObject<HTMLElement | null>
  onRequestUnlock?: () => void
}) {
  const store = useCueRowStore()
  const view = useCueValuesView()
  const { data: library } = useEffectLibraryQuery()
  const { fixtures } = useFixtureLookup()
  const { data: groups } = useGroupListQuery()
  const readOnly = readOnlyReason != null || store == null
  const unlock = useRefusedEditKeys({ region, readOnly, onRequestUnlock })

  // Drawn ahead of the desk while any list write waits, as the Looks tab's layers are.
  const [pending, setPending] = useState<{ base: CueAdHocEffect[]; list: CueAdHocEffect[] } | null>(null)
  const busy = store?.listsBusy === true
  const effects = pending != null && (busy || pending.base === cue.adHocEffects) ? pending.list : cue.adHocEffects
  const [open, setOpen] = useState<number | null>(null)
  const [adding, setAdding] = useState(false)
  /**
   * The effect this picker session added, at its place in the list and on the selection it was
   * added over — what a second tap swaps. A tap over another selection adds afresh.
   */
  const [picked, setPicked] = useState<{ index: number; effectType: string; propertyName: string; over: string } | null>(null)

  /**
   * One gesture: [apply] takes the list it lands on and the list the tab drew it on, and finds its
   * effect in the desk's list by the drawn effect's `uuid` (`cueListEdits.ts`).
   */
  const edit = useCallback(
    (apply: (list: readonly CueAdHocEffect[], drawn: readonly CueAdHocEffect[]) => CueAdHocEffect[] | null, now: boolean) => {
      if (readOnly || store == null) return
      const drawn = effects
      const shown = apply(drawn, drawn)
      if (shown == null) return
      setPending({ base: cue.adHocEffects, list: shown })
      void store.editEffects((stored) => apply(stored, drawn), now)
    },
    [readOnly, store, effects, cue.adHocEffects],
  )
  /** One effect changed, on the desk's own copy of it. */
  const editOne = useCallback(
    (index: number, change: (effect: CueAdHocEffect) => CueAdHocEffect, now: boolean) =>
      edit((list, drawn) => changeDrawn(list, drawn, index, effectId, change), now),
    [edit],
  )

  const move = (from: number, to: number) => {
    edit((list, drawn) => moveDrawn(list, drawn, from, to, effectId), true)
    setOpen((o) => (o == null ? o : o === from ? to : from < o && to >= o ? o - 1 : from > o && to <= o ? o + 1 : o))
    setPicked(null)
  }
  const remove = (index: number) => {
    edit((list, drawn) => removeDrawn(list, drawn, index, effectId), true)
    setOpen((o) => (o == null || o === index ? null : o > index ? o - 1 : o))
    setPicked(null)
  }

  const selection = view?.selectionTargets ?? NO_SELECTION
  const target = useMemo(() => effectTargetOf(selection, fixtures, groups), [selection, fixtures, groups])
  const addRefusal = readOnlyReason ?? (target == null ? NO_VALUES_SELECTION : null)

  const over = selectionKeyOf(selection)
  const swapping = picked != null && picked.over === over ? picked : null
  const onPick = (pick: DraftEffectPick) => {
    if (target == null) return
    const effect = storedEffectOf(pick, target)
    const next = { effectType: pick.effectType, propertyName: pick.propertyName, over }
    if (swapping != null && swapping.index < effects.length) {
      const index = swapping.index
      edit((list, drawn) => {
        const at = indexOfDrawn(list, drawn[index], index, effectId)
        return at < 0 ? appendItem(list, effect) : replaceAt(list, at, effect)
      }, true)
      setPicked({ index, ...next })
    } else {
      edit((list) => appendItem(list, effect), true)
      setPicked({ index: effects.length, ...next })
    }
    setOpen(null)
  }

  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 5 } }), useSensor(KeyboardSensor))
  const ids = effects.map((_, i) => `effect-${i}`)
  const onDragEnd = (event: DragEndEvent) => {
    const { active, over } = event
    if (over == null || active.id === over.id) return
    const from = ids.indexOf(String(active.id))
    const to = ids.indexOf(String(over.id))
    if (from >= 0 && to >= 0) move(from, to)
  }

  return (
    <div className="space-y-2" data-cue-effects-tab>
      {unlock}
      <div className="flex items-center gap-2">
        <EditorLabel>Effects · {effects.length}</EditorLabel>
        <span className="flex-1" />
        <Button
          variant="ghost"
          size="sm"
          className="h-6 gap-1 px-1.5 text-xs"
          disabled={addRefusal != null}
          title={addRefusal ?? `Add an effect on the Values selection — stored in the cue, nothing runs`}
          aria-expanded={adding}
          onClick={() => {
            setAdding((a) => !a)
            setPicked(null)
          }}
        >
          <Plus className="size-3" />
          Effect
        </Button>
      </div>

      {adding && target != null && !readOnly && (
        <div className="rounded-md border bg-card p-2" data-cue-fx-adding>
          <FxPicker
            key={JSON.stringify(selection)}
            target={target}
            draft={{ current: swapping, onPick }}
            onEdit={() => {
              if (picked != null) setOpen(picked.index)
              setAdding(false)
              setPicked(null)
            }}
          />
          <div className="flex justify-end pt-1">
            <Button
              variant="outline"
              size="sm"
              className="h-7 text-xs"
              onClick={() => {
                setAdding(false)
                setPicked(null)
              }}
            >
              Done
            </Button>
          </div>
        </div>
      )}

      {effects.length === 0 ? (
        <p className="text-[11px] text-muted-foreground">No effects of its own.{readOnly ? '' : ' + Effect adds one.'}</p>
      ) : (
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
          <SortableContext items={ids} strategy={verticalListSortingStrategy}>
            <ul className="flex flex-col gap-1">
              {effects.map((effect, index) => (
                <EffectRow
                  key={ids[index]}
                  id={ids[index]}
                  effect={effect}
                  entry={findEffectEntry(library, effect.effectType)}
                  open={open === index}
                  readOnly={readOnly}
                  onToggle={() => setOpen((o) => (o === index ? null : index))}
                  onRemove={() => remove(index)}
                >
                  {open === index && library == null && <EditorReadout className="border-t p-2.5">Reading the effect library…</EditorReadout>}
                  {open === index && library != null && (
                    <CueEffectEditor
                      // Keyed by every Undo too: what it put back is the draft to start from.
                      key={`${index}:${effect.effectType}:${store?.undoSeq ?? 0}`}
                      effect={effect}
                      entry={findEffectEntry(library, effect.effectType)}
                      libraryLoaded={library != null}
                      readOnly={readOnly}
                      onDraft={(draft, release) => editOne(index, (stored) => withCueEffectDraft(stored, draft), release)}
                      onTiming={(timing) => editOne(index, (stored) => ({ ...stored, ...timing }), false)}
                    />
                  )}
                </EffectRow>
              ))}
            </ul>
          </SortableContext>
        </DndContext>
      )}

      <LookEffects layers={cue.layers} projectId={projectId} library={library} />
    </div>
  )
}

function targetFieldsOf(effect: CueAdHocEffect): EffectTargetFields {
  return {
    targetKey: effect.targetKey,
    isGroupTarget: effect.targetType === 'group',
    targetType: effect.targetType,
    targets: effect.targetType === 'selection' ? storedEffectTargets(effect.targetType, effect.targetKey) : null,
  }
}

function EffectRow({
  id,
  effect,
  entry,
  open,
  readOnly,
  onToggle,
  onRemove,
  children,
}: {
  id: string
  effect: CueAdHocEffect
  entry: EffectLibraryEntry | undefined
  open: boolean
  readOnly: boolean
  onToggle: () => void
  onRemove: () => void
  children: React.ReactNode
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id, disabled: readOnly })
  const { fixtureByKey } = useFixtureLookup()
  const target = effectTargetLabel(targetFieldsOf(effect), (t) => (t.type === 'group' ? t.key : (fixtureByKey.get(t.key)?.name ?? t.key)))
  const dynamics = [
    ...effectDynamicsParts(effect),
    ...(effect.nShot != null ? [runsWord(effect.nShot)] : []),
  ]
  return (
    <li
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition, zIndex: isDragging ? 50 : undefined, opacity: isDragging ? 0.5 : undefined }}
      className="rounded border bg-card text-xs"
      data-cue-effect-row={effect.effectType}
    >
      <div className="flex items-center gap-1.5 p-1.5">
        {!readOnly && (
          // The sortable's attributes on the grip, never the row: on the row they make it a
          // focusable `role="button"`, which every key guard stands aside for.
          <button type="button" className="shrink-0 cursor-grab text-muted-foreground hover:text-foreground" {...attributes} {...listeners} aria-label="Reorder effect">
            <GripVertical className="size-3.5" />
          </button>
        )}
        <button
          type="button"
          className="flex min-w-0 flex-1 items-center gap-1.5 text-left"
          aria-expanded={open}
          aria-label={`${open ? 'Close' : 'Open'} ${effect.effectType} on ${target}`}
          onClick={onToggle}
        >
          {open ? <ChevronDown className="size-3 shrink-0" /> : <ChevronRight className="size-3 shrink-0" />}
          <span className="min-w-0 truncate text-muted-foreground" title={target}>
            {target}
          </span>
          <span className="shrink-0 font-medium">{entry?.name ?? effect.effectType}</span>
          {effect.propertyName && <span className="shrink-0 font-mono text-[11px] text-muted-foreground">→ {effect.propertyName}</span>}
          <SpeedLabel effect={effect} entry={entry} />
          {dynamics.length > 0 && <span className="shrink-0 text-muted-foreground">· {dynamics.join(' · ')}</span>}
        </button>
        <TimingBadge delayMs={effect.delayMs} intervalMs={effect.intervalMs} randomWindowMs={effect.randomWindowMs} />
        {!readOnly && (
          <button
            type="button"
            aria-label={`Remove ${effect.effectType}`}
            className="grid size-6 shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-destructive"
            onClick={onRemove}
          >
            <X className="size-3.5" />
          </button>
        )}
      </div>
      {children}
    </li>
  )
}

/** A row's speed: *Seconds* and its cycle, or the cycle on its master (S15) — `1 bar · M2`. */
function SpeedLabel({ effect, entry }: { effect: CueAdHocEffect; entry: EffectLibraryEntry | undefined }) {
  const wallClock = entry?.timingSource === 'WALL_CLOCK'
  const onSeconds = isOnSeconds(effect, entry?.timingSource)
  const master = useSpeedMasterDisplay(wallClock ? effect.rateSpeedMasterUuid : effect.speedMasterUuid)
  const cycle = onSeconds ? secondsLabel(effect.beatDivision) : effectSpeedLabel(effect.beatDivision, entry?.timingSource)
  const who = onSeconds ? 'Seconds' : wallClock ? `Rate M${master?.index ?? 1}` : `M${master?.index ?? 1}`
  return (
    <span className="shrink-0 text-muted-foreground" data-effect-speed>
      · {cycle != null ? `${cycle} · ` : ''}
      {who}
    </span>
  )
}

/**
 * One stored effect's fields: `FxEffectFields` over a draft of its own, seeded when it opens — a
 * refetch after each save must not pull a slider back under a finger — written back through
 * [onDraft], and the cue's fire timing under it.
 */
function CueEffectEditor({
  effect,
  entry,
  libraryLoaded,
  readOnly,
  onDraft,
  onTiming,
}: {
  effect: CueAdHocEffect
  entry: EffectLibraryEntry | undefined
  libraryLoaded: boolean
  readOnly: boolean
  onDraft: (draft: FxDraft, release: boolean) => void
  onTiming: (timing: Pick<CueAdHocEffect, 'delayMs' | 'intervalMs' | 'randomWindowMs'>) => void
}) {
  const [draft, setDraft] = useState<FxDraft>(() => cueEffectDraftOf(effect, entry?.timingSource))
  const [timing, setTiming] = useState<Pick<CueAdHocEffect, 'delayMs' | 'intervalMs' | 'randomWindowMs'>>(() => ({
    delayMs: effect.delayMs ?? null,
    intervalMs: effect.intervalMs ?? null,
    randomWindowMs: effect.randomWindowMs ?? null,
  }))
  const { scope, extendedChannels, settingProperty } = useEffectScope({
    target: targetFieldsOf(effect),
    propertyName: effect.propertyName,
    entry,
    elementMode: draft.elementMode,
    elementFilter: draft.elementFilter,
  })
  const write = (next: FxDraft, release: boolean) => {
    setDraft(next)
    onDraft(next, release)
  }
  return (
    <div className="flex flex-col gap-3 border-t p-2.5" data-cue-effect-editor>
      {entry == null ? (
        <EditorReadout>
          {libraryLoaded ? `“${effect.effectType}” is not in this desk's effect library, so its settings cannot be drawn.` : 'Reading the effect library…'}
        </EditorReadout>
      ) : (
        <FxEffectFields
          entry={entry}
          timingSource={entry.timingSource}
          draft={draft}
          scope={scope}
          extendedChannels={extendedChannels}
          settingProperty={settingProperty}
          propertyName={effect.propertyName}
          readOnly={readOnly}
          set={(patch, release = true) => write({ ...draft, ...patch }, release)}
          setParam={(name, value, release) => write({ ...draft, parameters: { ...draft.parameters, [name]: value } }, release)}
        />
      )}
      <div className="flex flex-col gap-1.5" data-cue-effect-timing>
        <EditorLabel>Delay · Interval · Random</EditorLabel>
        <fieldset disabled={readOnly} className="contents">
          <TimingFields
            values={timing}
            onChange={(next) => {
              setTiming(next)
              onTiming(next)
            }}
          />
        </fieldset>
      </div>
      <EditorReadout>Stored in the cue — nothing runs until it next comes on.</EditorReadout>
    </div>
  )
}

/** Each Look layer's deferred effects, under its Look, read-only — the Look's, edited in its library. */
function LookEffects({
  layers,
  projectId,
  library,
}: {
  layers: readonly CueLayerDetail[]
  projectId: number
  library: EffectLibraryEntry[] | undefined
}) {
  const looks = layers.filter((l) => l.source?.kind === 'LOOK' && l.enabled !== false)
  if (looks.length === 0) return null
  return (
    <div className="space-y-1.5 pt-1" data-cue-look-effects>
      {looks.map((layer, i) => (
        <LookLayerEffects key={`${layer.source!.id}-${i}`} lookId={layer.source!.id} name={layer.source!.name} projectId={projectId} library={library} />
      ))}
    </div>
  )
}

function LookLayerEffects({
  lookId,
  name,
  projectId,
  library,
}: {
  lookId: number
  name: string
  projectId: number
  library: EffectLibraryEntry[] | undefined
}) {
  const { data: look } = useLookQuery({ projectId, lookId })
  const deferred = (look?.effects ?? []).filter((e) => e.targetType === DEFERRED_TARGET_TYPE)
  if (deferred.length === 0) return null
  return (
    <div className="space-y-1">
      <EditorLabel>From {name} · on the layer’s targets</EditorLabel>
      {deferred.map((effect, i) => (
        <EffectSummary key={i} effect={fromLookEffect(effect, library)} className="opacity-80" />
      ))}
    </div>
  )
}
