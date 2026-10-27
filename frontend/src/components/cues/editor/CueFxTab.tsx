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
import { FxFields } from '@/components/fx/FxFields'
import { FxPicker, type DraftFxPick } from '@/components/fx/FxPicker'
import { selectionKeyOf, storedFxOf } from '@/components/fx/draftFx'
import { fxSpeedLabel, secondsLabel } from '@/components/fx/fxConstants'
import { cueFxDraftOf, fxSwapOptions, runsWord, swappedDraft, withCueFxDraft, type FxDraft } from '@/components/fx/fxEditorModel'
import { useFxScope } from '@/components/fx/useFxScope'
import { fxTargetOf } from '@/components/fx/selectionFxTarget'
import { findFxEntry } from '@/components/busking/buskingTypes'
import { FxGraphStrip } from '@/components/fxEditor/FxGraphStrip'
import { previewDraftOf, storedFxAttribute } from '@/components/fxEditor/fxStripDraft'
import { useFxPreview } from '@/components/fxEditor/useFxPreview'
import { attributeFamilyForFxFamily } from '@/lib/attributeFamily'
import { fxDynamicsParts } from '@/lib/fxDynamics'
import { isOnSeconds } from '@/lib/fxTiming'
import { fxTargetLabel, storedFxTargets, type FxTargetFields } from '@/lib/fxTargets'
import { drivenCategoryOf } from '@/lib/stepFx'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { usePaletteListQuery } from '@/store/palettes'
import { useFxLibraryQuery, type FxLibraryEntry } from '@/store/fixtureFx'
import { useGroupListQuery } from '@/store/groups'
import { useSpeedMasterDisplay } from '@/store/speedMasters'
import type { Cue, CueAdHocFx, CueTarget } from '@/api/cuesApi'
import { useCueRowStore } from './CueRowStore'
import { useCueValuesView } from './CueValuesView'
import { CueEditorIsolation, useRefusedEditKeys } from './editorIsolation'
import { appendItem, changeDrawn, fxId, indexOfDrawn, moveDrawn, removeDrawn, replaceAt } from './cueListEdits'
import { NO_VALUES_SELECTION } from './CueLooksTab'

const NO_SELECTION: readonly CueTarget[] = []

/**
 * The cue editor's **FX** tab (show-cue-editing plan §4.1, D17): one row per stored effect — its
 * target, effect and property, its speed (a master, or *Seconds*), size, direction and runs, and its
 * fire timing — reorderable. A row opens `FxFields` over the stored effect
 * (`cueFxDraftOf` / `withCueFxDraft`), its `FxScope` from the effect's target, with the
 * cue's *Delay · Interval · Random* under it, and the graph strip of the open FX on its own targets
 * (FX editor plan §4.6) — the FX alone, since what is underneath at its GO is not known here.
 * *+ Effect* opens `FxPicker` **in draft** over the
 * Values selection: a tap adds the effect to the cue with its defaults and runs nothing, a second tap
 * swaps it.
 *
 * Every write is the cue's whole `adHocFx` list through the editor's `CueRowStore`, every
 * `sortOrder` restated: a stored write, sending nothing to the stage (P2) — a live cue picks it up
 * on its next first hold. A drag's moves coalesce (400 ms, 2 s); a release, a reorder or an add saves
 * at once. Locked, the rows read.
 */
export function CueFxTab(props: {
  cue: Cue
  projectId: number
  readOnlyReason: string | null
  region: RefObject<HTMLElement | null>
  onRequestUnlock?: () => void
}) {
  return (
    <CueEditorIsolation>
      <FxBody {...props} />
    </CueEditorIsolation>
  )
}

function FxBody({
  cue,
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
  const { data: library } = useFxLibraryQuery()
  const { fixtures } = useFixtureLookup()
  const { data: groups } = useGroupListQuery()
  const readOnly = readOnlyReason != null || store == null
  const unlock = useRefusedEditKeys({ region, readOnly, onRequestUnlock })

  // Drawn ahead of the desk while any list write waits, as the Looks tab's layers are.
  const [pending, setPending] = useState<{ base: CueAdHocFx[]; list: CueAdHocFx[] } | null>(null)
  const busy = store?.listsBusy === true
  const effects = pending != null && (busy || pending.base === cue.adHocFx) ? pending.list : cue.adHocFx
  const [open, setOpen] = useState<number | null>(null)
  const [adding, setAdding] = useState(false)
  /**
   * The effect this picker session added, at its place in the list and on the selection it was
   * added over — what a second tap swaps. A tap over another selection adds afresh.
   */
  const [picked, setPicked] = useState<{ index: number; fxType: string; attribute: string; over: string } | null>(null)

  /**
   * One gesture: [apply] takes the list it lands on and the list the tab drew it on, and finds its
   * effect in the desk's list by the drawn effect's `uuid` (`cueListEdits.ts`).
   */
  const edit = useCallback(
    (apply: (list: readonly CueAdHocFx[], drawn: readonly CueAdHocFx[]) => CueAdHocFx[] | null, now: boolean) => {
      if (readOnly || store == null) return
      const drawn = effects
      const shown = apply(drawn, drawn)
      if (shown == null) return
      setPending({ base: cue.adHocFx, list: shown })
      void store.editFx((stored) => apply(stored, drawn), now)
    },
    [readOnly, store, effects, cue.adHocFx],
  )
  /** One effect changed, on the desk's own copy of it. */
  const editOne = useCallback(
    (index: number, change: (effect: CueAdHocFx) => CueAdHocFx, now: boolean) =>
      edit((list, drawn) => changeDrawn(list, drawn, index, fxId, change), now),
    [edit],
  )

  const move = (from: number, to: number) => {
    edit((list, drawn) => moveDrawn(list, drawn, from, to, fxId), true)
    setOpen((o) => (o == null ? o : o === from ? to : from < o && to >= o ? o - 1 : from > o && to <= o ? o + 1 : o))
    setPicked(null)
  }
  const remove = (index: number) => {
    edit((list, drawn) => removeDrawn(list, drawn, index, fxId), true)
    setOpen((o) => (o == null || o === index ? null : o > index ? o - 1 : o))
    setPicked(null)
  }

  const selection = view?.selectionTargets ?? NO_SELECTION
  const target = useMemo(() => fxTargetOf(selection, fixtures, groups), [selection, fixtures, groups])
  const addRefusal = readOnlyReason ?? (target == null ? NO_VALUES_SELECTION : null)

  const over = selectionKeyOf(selection)
  const swapping = picked != null && picked.over === over ? picked : null
  const onPick = (pick: DraftFxPick) => {
    if (target == null) return
    const effect = storedFxOf(pick, target)
    const next = { fxType: pick.fxType, attribute: pick.attribute, over }
    if (swapping != null && swapping.index < effects.length) {
      const index = swapping.index
      edit((list, drawn) => {
        const at = indexOfDrawn(list, drawn[index], index, fxId)
        return at < 0 ? appendItem(list, effect) : replaceAt(list, at, effect)
      }, true)
      setPicked({ index, ...next })
    } else {
      edit((list) => appendItem(list, effect), true)
      setPicked({ index: effects.length, ...next })
    }
    setOpen(null)
  }

  /**
   * The open row's type switcher (fx-settings plan D9): the stored FX becomes another type on its
   * attribute, as the picker's draft swap stores one — its own parameters and family, the timing
   * carried, a step spread kept at its offsets over the row's [heads].
   */
  const swapFor = (effect: CueAdHocFx, index: number): CueFxSwap | undefined => {
    const from = findFxEntry(library, effect.fxType)
    const attribute = effect.attribute
    if (from == null || attribute == null || readOnly) return undefined
    return {
      options: fxSwapOptions(library, attribute, from),
      onSwap: (to, heads) => {
        editOne(
          index,
          (stored) => ({
            ...withCueFxDraft(stored, swappedDraft(cueFxDraftOf(stored, from.timingSource), from, to, stored.attribute, heads)),
            fxType: to.name,
            family: drivenCategoryOf(to),
          }),
          true,
        )
        // The picker's next tap swaps from the type the row now holds.
        setPicked((p) => (p != null && p.index === index ? { ...p, fxType: to.name } : p))
      },
    }
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
        <EditorLabel>FX · {effects.length}</EditorLabel>
        <span className="flex-1" />
        <Button
          variant="ghost"
          size="sm"
          className="h-6 gap-1 px-1.5 text-xs"
          disabled={addRefusal != null}
          title={addRefusal ?? `Add an FX on the Values selection — stored in the cue, nothing runs`}
          aria-expanded={adding}
          onClick={() => {
            setAdding((a) => !a)
            setPicked(null)
          }}
        >
          <Plus className="size-3" />
          FX
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
        <p className="text-[11px] text-muted-foreground">No FX of its own.{readOnly ? '' : ' + FX adds one.'}</p>
      ) : (
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
          <SortableContext items={ids} strategy={verticalListSortingStrategy}>
            <ul className="flex flex-col gap-1">
              {effects.map((effect, index) => (
                <FxRow
                  key={ids[index]}
                  id={ids[index]}
                  effect={effect}
                  entry={findFxEntry(library, effect.fxType)}
                  open={open === index}
                  readOnly={readOnly}
                  onToggle={() => setOpen((o) => (o === index ? null : index))}
                  onRemove={() => remove(index)}
                >
                  {open === index && library == null && <EditorReadout className="border-t p-2.5">Reading the FX library…</EditorReadout>}
                  {open === index && library != null && (
                    <CueFxEditor
                      // Keyed by every Undo too: what it put back is the draft to start from.
                      key={`${index}:${effect.fxType}:${store?.undoSeq ?? 0}`}
                      effect={effect}
                      entry={findFxEntry(library, effect.fxType)}
                      libraryLoaded={library != null}
                      readOnly={readOnly}
                      swap={swapFor(effect, index)}
                      onDraft={(draft, release) => editOne(index, (stored) => withCueFxDraft(stored, draft), release)}
                      onTiming={(timing) => editOne(index, (stored) => ({ ...stored, ...timing }), false)}
                    />
                  )}
                </FxRow>
              ))}
            </ul>
          </SortableContext>
        </DndContext>
      )}
    </div>
  )
}

/** A row's type swap: the FX it can become, and the swap over the heads the row runs on. */
interface CueFxSwap {
  options: readonly FxLibraryEntry[]
  onSwap: (to: FxLibraryEntry, heads: number | undefined) => void
}

function targetFieldsOf(effect: CueAdHocFx): FxTargetFields {
  return {
    targetKey: effect.targetKey,
    isGroupTarget: effect.targetType === 'group',
    targetType: effect.targetType,
    targets: effect.targetType === 'selection' ? storedFxTargets(effect.targetType, effect.targetKey) : null,
  }
}

function FxRow({
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
  effect: CueAdHocFx
  entry: FxLibraryEntry | undefined
  open: boolean
  readOnly: boolean
  onToggle: () => void
  onRemove: () => void
  children: React.ReactNode
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id, disabled: readOnly })
  const { fixtureByKey } = useFixtureLookup()
  const target = fxTargetLabel(targetFieldsOf(effect), (t) => (t.type === 'group' ? t.key : (fixtureByKey.get(t.key)?.name ?? t.key)))
  const dynamics = [
    ...fxDynamicsParts(effect),
    ...(effect.runs != null ? [runsWord(effect.runs)] : []),
  ]
  return (
    <li
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition, zIndex: isDragging ? 50 : undefined, opacity: isDragging ? 0.5 : undefined }}
      className="rounded border bg-card text-xs"
      data-cue-effect-row={effect.fxType}
    >
      <div className="flex items-center gap-1.5 p-1.5">
        {!readOnly && (
          // The sortable's attributes on the grip, never the row: on the row they make it a
          // focusable `role="button"`, which every key guard stands aside for.
          <button type="button" className="shrink-0 cursor-grab text-muted-foreground hover:text-foreground" {...attributes} {...listeners} aria-label="Reorder FX">
            <GripVertical className="size-3.5" />
          </button>
        )}
        <button
          type="button"
          className="flex min-w-0 flex-1 items-center gap-1.5 text-left"
          aria-expanded={open}
          aria-label={`${open ? 'Close' : 'Open'} ${effect.fxType} on ${target}`}
          onClick={onToggle}
        >
          {open ? <ChevronDown className="size-3 shrink-0" /> : <ChevronRight className="size-3 shrink-0" />}
          <span className="min-w-0 truncate text-muted-foreground" title={target}>
            {target}
          </span>
          <span className="shrink-0 font-medium">{entry?.name ?? effect.fxType}</span>
          {effect.attribute && <span className="shrink-0 font-mono text-[11px] text-muted-foreground">→ {effect.attribute}</span>}
          <SpeedLabel effect={effect} entry={entry} />
          {dynamics.length > 0 && <span className="shrink-0 text-muted-foreground">· {dynamics.join(' · ')}</span>}
        </button>
        <TimingBadge delayMs={effect.delayMs} intervalMs={effect.intervalMs} randomWindowMs={effect.randomWindowMs} />
        {!readOnly && (
          <button
            type="button"
            aria-label={`Remove ${effect.fxType}`}
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
function SpeedLabel({ effect, entry }: { effect: CueAdHocFx; entry: FxLibraryEntry | undefined }) {
  const wallClock = entry?.timingSource === 'WALL_CLOCK'
  const onSeconds = isOnSeconds(effect, entry?.timingSource)
  const master = useSpeedMasterDisplay(wallClock ? effect.rateSpeedMasterUuid : effect.speedMasterUuid)
  const cycle = onSeconds ? secondsLabel(effect.cycle) : fxSpeedLabel(effect.cycle, entry?.timingSource)
  const who = onSeconds ? 'Seconds' : wallClock ? `Rate M${master?.index ?? 1}` : `M${master?.index ?? 1}`
  return (
    <span className="shrink-0 text-muted-foreground" data-effect-speed>
      · {cycle != null ? `${cycle} · ` : ''}
      {who}
    </span>
  )
}

/**
 * The open FX's graph strip: the stored FX, as edited, asked of the desk as a `{draft}` over its own
 * targets, `include: ONLY`, and drawn as the FX alone. A cue's FX lands on whatever is there at its
 * GO, which nothing here can say, so the strip draws nothing underneath and says so. The cue's
 * project is its row store's.
 */
function CueFxStrip({ effect, entry }: { effect: CueAdHocFx; entry: FxLibraryEntry }) {
  const projectId = useCueRowStore()?.projectId ?? null
  const { data: templates } = usePaletteListQuery({ projectId: projectId ?? 0 }, { skip: projectId == null })
  const attribute = storedFxAttribute(effect, attributeFamilyForFxFamily(effect.family), entry, templates)
  const targets = useMemo(() => storedFxTargets(effect.targetType, effect.targetKey), [effect.targetType, effect.targetKey])
  const draft = useMemo(() => (attribute == null ? null : previewDraftOf(effect, attribute, { targets })), [attribute, effect, targets])
  const preview = useFxPreview(draft == null || projectId == null ? null : { projectId, targets, fxIds: [], drafts: [draft], include: 'ONLY' })
  return (
    <FxGraphStrip
      answer={preview.answer}
      error={preview.error}
      waiting={attribute == null ? 'This FX names no attribute the graph can draw.' : 'Asking the desk…'}
      draw="this"
      caption="This FX on its own targets. What is underneath at its GO is not drawn."
      fxName={() => entry.name}
      bounce={effect.direction === 'BOUNCE'}
    />
  )
}

/**
 * One stored effect's fields: `FxFields` over a draft of its own, seeded when it opens — a
 * refetch after each save must not pull a slider back under a finger — written back through
 * [onDraft], and the cue's fire timing under it.
 */
function CueFxEditor({
  effect,
  entry,
  libraryLoaded,
  readOnly,
  swap,
  onDraft,
  onTiming,
}: {
  effect: CueAdHocFx
  entry: FxLibraryEntry | undefined
  libraryLoaded: boolean
  readOnly: boolean
  swap?: CueFxSwap
  onDraft: (draft: FxDraft, release: boolean) => void
  onTiming: (timing: Pick<CueAdHocFx, 'delayMs' | 'intervalMs' | 'randomWindowMs'>) => void
}) {
  const [draft, setDraft] = useState<FxDraft>(() => cueFxDraftOf(effect, entry?.timingSource))
  const [timing, setTiming] = useState<Pick<CueAdHocFx, 'delayMs' | 'intervalMs' | 'randomWindowMs'>>(() => ({
    delayMs: effect.delayMs ?? null,
    intervalMs: effect.intervalMs ?? null,
    randomWindowMs: effect.randomWindowMs ?? null,
  }))
  const { scope, extendedChannels, settingAttribute } = useFxScope({
    target: targetFieldsOf(effect),
    attribute: effect.attribute,
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
          {libraryLoaded ? `“${effect.fxType}” is not in this desk's FX library, so its settings cannot be drawn.` : 'Reading the FX library…'}
        </EditorReadout>
      ) : (
        <FxFields
          entry={entry}
          timingSource={entry.timingSource}
          draft={draft}
          scope={scope}
          extendedChannels={extendedChannels}
          settingAttribute={settingAttribute}
          attribute={effect.attribute}
          readOnly={readOnly}
          swap={swap && { options: swap.options, onSwap: (to) => swap.onSwap(to, scope.headCount) }}
          set={(patch, release = true) => write({ ...draft, ...patch }, release)}
          setParam={(name, value, release) => write({ ...draft, parameters: { ...draft.parameters, [name]: value } }, release)}
        />
      )}
      {entry != null && <CueFxStrip effect={withCueFxDraft(effect, draft)} entry={entry} />}
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
