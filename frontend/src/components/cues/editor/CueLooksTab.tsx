import { useCallback, useMemo, useRef, useState, type RefObject } from 'react'
import { useNavigate } from 'react-router'
import { Layers, Palette, Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { LookStack, type LayerHandlers } from '@/components/looks/LookStack'
import { ApplyPicker } from '@/components/press/ApplyPicker'
import type { PressPair } from '@/hooks/usePress'
import type { PaletteFit } from '@/lib/paletteOffer'
import { targetEmitters, targetFamilies, type WriteTarget } from '@/components/fixtures-list/rowModel'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import type { Fixture } from '@/store/fixtures'
import { HandCueLayerStrip, type HandCueLayerHost } from '@/components/hand/HandLayerTargets'
import { useLookListQuery } from '@/store/looks'
import { usePaletteListQuery } from '@/store/palettes'
import type { Cue, CueLayer, CueLayerDetail, CueTarget } from '@/api/cuesApi'
import { useCueRowStore } from './CueRowStore'
import { useCueValuesView } from './CueValuesView'
import { CueEditorIsolation, useRefusedEditKeys } from './editorIsolation'
import { changeDrawn, layerId, moveDrawn, removeDrawn } from './cueListEdits'

const NO_SELECTION: readonly CueTarget[] = []

/** *Targets* with nothing selected in Values. */
export const NO_VALUES_SELECTION = 'Select fixtures or groups in Values first'

/**
 * The cue editor's **Looks** tab (show-cue-editing plan §4.1, D3): `LookStack` at its wide density
 * over the cue's layers — a layer edited as a layer, never as a Look. Every gesture is one whole-list
 * `layers` write through the editor's `CueRowStore`, every `sortOrder` restated from its place, so a
 * reorder keeps each layer's uuid (the desk pairs them by what they apply, §3.4) and a disabled layer
 * is stored. A stored cue write and nothing else (P2): a live cue picks it up on its next first hold.
 *
 * *+ Look* and *+ Palette* open the one picker (`ApplyPicker`, apply-model plan §4.2) on the Values
 * selection, its presses writing the cue (`usePressCue`): a palette replaces the family's other
 * palette layers on those heads, and a second press takes it off — the desk's press rule over the
 * stored layers. A layer's timing and masters are its settings popover's. A row's *Targets* puts the
 * layer on that selection; *Open* leaves for the Look's (or palette's) library, where a contents edit
 * changes every cue that layers it. Locked, the rows read.
 */
export function CueLooksTab({
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
  return (
    <CueEditorIsolation>
      <LooksBody cue={cue} projectId={projectId} readOnlyReason={readOnlyReason} region={region} onRequestUnlock={onRequestUnlock} />
    </CueEditorIsolation>
  )
}

function LooksBody({
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
  const navigate = useNavigate()
  const { fixtures } = useFixtureLookup()
  const { data: looks } = useLookListQuery({ projectId })
  const { data: templates } = usePaletteListQuery({ projectId })
  const looksById = useMemo(() => new Map((looks ?? []).map((l) => [l.id, l])), [looks])
  const palettesById = useMemo(() => new Map((templates ?? []).map((t) => [t.id, t])), [templates])
  const readOnly = readOnlyReason != null || store == null
  const unlock = useRefusedEditKeys({ region, readOnly, onRequestUnlock })
  const [adding, setAdding] = useState<'look' | 'palette' | null>(null)

  // What the tab draws while a write is on its way: each gesture applied to the list it last drew,
  // kept while any is still waiting for its flush or its PATCH, and given up once none is and the
  // cue has been read again — so a refetch between two writes never draws a removed layer back.
  const [pending, setPending] = useState<{ base: CueLayerDetail[]; list: CueLayerDetail[] } | null>(null)
  const busy = store?.listsBusy === true
  const layers = pending != null && (busy || pending.base === cue.layers) ? pending.list : cue.layers

  const selection = view?.selectionTargets ?? NO_SELECTION
  /**
   * One gesture: [apply] takes the list it lands on and the list the tab drew it on, so it finds
   * its layer in the desk's list by the drawn layer's `id` (`cueListEdits.ts`) — another client may
   * have moved the cue's layers since the tab drew them.
   */
  const edit = useCallback(
    (apply: (list: readonly CueLayerDetail[], drawn: readonly CueLayerDetail[]) => CueLayerDetail[] | null, now = true) => {
      if (readOnly || store == null) return Promise.resolve(false)
      const drawn = layers
      const shown = apply(drawn, drawn)
      if (shown == null) return Promise.resolve(false)
      setPending({ base: cue.layers, list: shown })
      return store.editLayers((stored) => apply(stored, drawn), now)
    },
    [readOnly, store, layers, cue.layers],
  )

  const handlers = useMemo<LayerHandlers>(() => {
    const set = (index: number, patch: Partial<CueLayer>, now = true) =>
      void edit((list, drawn) => changeDrawn(list, drawn, index, layerId, (layer) => ({ ...layer, ...patch })), now)
    return {
      onRemove: (index) => void edit((list, drawn) => removeDrawn(list, drawn, index, layerId)),
      onMove: (from, to) => void edit((list, drawn) => moveDrawn(list, drawn, from, to, layerId)),
      onSetEnabled: (index, enabled) => set(index, { enabled }),
      onSetAmount: (index, amount) => set(index, { amount }),
      onSetBlendMode: (index, blendMode) => set(index, { blendMode }),
      onSetAttributeMask: (index, attributeMask) => set(index, { attributeMask }),
      onSetStomp: (index, stomp) => set(index, { stomp }),
      onSetTargets: selection.length > 0 ? (index) => set(index, { targets: selection.map((t) => ({ ...t })) }) : undefined,
      // Typed timing coalesces on the store's flush; a master pick is one gesture and saves at once.
      onSetTiming: (index, timing) =>
        set(index, timing, !('delayMs' in timing || 'intervalMs' in timing || 'randomWindowMs' in timing)),
      onOpenSource: (index) => {
        const source = layers[index]?.source
        if (source == null) return
        const library = source.kind === 'PALETTE' ? 'palettes' : 'looks'
        navigate(`/projects/${projectId}/${library}?edit=${source.id}`)
      },
    }
  }, [edit, selection, layers, navigate, projectId])
  // Drawn while refused too, so the button says why; `LookStack` reads the refusal for its title.
  const shownHandlers = useMemo<LayerHandlers>(
    () => (handlers.onSetTargets != null ? handlers : { ...handlers, onSetTargets: () => {} }),
    [handlers],
  )

  const pickerPair = useMemo<PressPair>(() => ({ targets: selection, families: null }), [selection])
  // What the selected heads can take, as the programmer's strip reads its marquee: an offer, never a
  // refusal — the desk still answers the press.
  const pickerFit = useMemo<PaletteFit>(() => {
    const heads = cueWriteTargets(selection, fixtures ?? [])
    return heads.length === 0 ? { families: null, emitters: null } : { families: targetFamilies(heads), emitters: targetEmitters(heads) }
  }, [selection, fixtures])
  const lookRef = useRef<HTMLButtonElement>(null)
  const paletteRef = useRef<HTMLButtonElement>(null)
  const handHost = useMemo<HandCueLayerHost | undefined>(() => (store == null ? undefined : { targets: selection }), [store, selection])

  const addTitle = (kind: string) =>
    readOnlyReason ??
    (selection.length > 0
      ? `Put a ${kind} on as a layer on the Values selection (${selection.length}), or take it off`
      : kind === 'look'
        ? 'Put a look on as a layer of its own fixtures — or select in Values first'
        : 'Put a per-fixture palette on as a layer — select in Values first for the rest')

  return (
    <div className="space-y-2" data-cue-looks-tab>
      {unlock}
      <LookStack
        layers={layers}
        looksById={looksById}
        palettesById={palettesById}
        looksLoaded={looks != null && templates != null}
        handlers={shownHandlers}
        readOnly={readOnly}
        targetsRefusal={selection.length > 0 ? null : NO_VALUES_SELECTION}
        precedenceNote="Later layers win — intensity included — and the cue’s own values beat every layer."
        emptyNote="No looks layered. + Look or + Palette adds one."
        addActions={
          <div className="flex items-center gap-1">
            <Button
              ref={lookRef}
              variant="ghost"
              size="sm"
              className="h-6 gap-1 px-1.5 text-xs"
              disabled={readOnly}
              title={addTitle('look')}
              aria-expanded={adding === 'look'}
              onClick={() => setAdding((a) => (a === 'look' ? null : 'look'))}
            >
              <Plus className="size-3" />
              <Layers className="size-3" />
              Look
            </Button>
            <Button
              ref={paletteRef}
              variant="ghost"
              size="sm"
              className="h-6 gap-1 px-1.5 text-xs"
              disabled={readOnly}
              title={addTitle('palette')}
              aria-expanded={adding === 'palette'}
              onClick={() => setAdding((a) => (a === 'palette' ? null : 'palette'))}
            >
              <Plus className="size-3" />
              <Palette className="size-3" />
              Palette
            </Button>
          </div>
        }
      />
      {/* The hand's target for this cue (multi-screen plan §3.5), never while locked — locked is
          the running state, and a cue must not gain a layer from a stray tap. It lands on the
          Values selection through this tab's writes, as + Look does. */}
      {!readOnly && <HandCueLayerStrip projectId={projectId} cue={cue} host={handHost} />}
      {adding != null && !readOnly && (
        <ApplyPicker
          projectId={projectId}
          open
          onOpenChange={(open) => {
            if (!open) setAdding(null)
          }}
          anchorRef={adding === 'look' ? lookRef : paletteRef}
          library={adding === 'look' ? 'looks' : 'palettes'}
          pair={pickerPair}
          fit={pickerFit}
          offerNew={false}
        />
      )}
    </div>
  )
}

/**
 * The heads a cue selection names, as the palette offer reads them: a group as its members, a cell
 * by its element key, a fixture as itself. A key nothing resolves is left out.
 */
export function cueWriteTargets(targets: readonly CueTarget[], fixtures: readonly Fixture[]): WriteTarget[] {
  const out: WriteTarget[] = []
  for (const target of targets) {
    if (target.type === 'group') {
      for (const fixture of fixtures) {
        if (fixture.groups.includes(target.key)) out.push({ key: fixture.key, attributes: fixture.attributes, elements: fixture.elements })
      }
      continue
    }
    const fixture = fixtures.find((f) => f.key === target.key)
    if (fixture != null) {
      out.push({ key: fixture.key, attributes: fixture.attributes, elements: fixture.elements })
      continue
    }
    for (const parent of fixtures) {
      const element = parent.elements?.find((e) => e.key === target.key)
      if (element != null) {
        out.push({ key: element.key, attributes: element.attributes, fixtureKey: parent.key })
        break
      }
    }
  }
  return out
}
