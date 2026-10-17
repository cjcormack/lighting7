import { useCallback, useMemo, useState, type RefObject } from 'react'
import { useNavigate } from 'react-router'
import { Layers, Palette, Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { LookStack, type LayerHandlers } from '@/components/looks/LookStack'
import { AddLayerSheet } from '@/components/programmer/AddLayerSheet'
import { HandCueLayerStrip, type HandCueLayerHost } from '@/components/hand/HandLayerTargets'
import { useLookListQuery } from '@/store/looks'
import { useTemplateListQuery } from '@/store/templates'
import type { Cue, CueLayer, CueLayerDetail, CueTarget, LayerSource } from '@/api/cuesApi'
import { useCueRowStore } from './CueRowStore'
import { useCueValuesView } from './CueValuesView'
import { CueEditorIsolation, useRefusedEditKeys } from './editorIsolation'
import { appendItem, changeDrawn, layerId, moveDrawn, removeDrawn } from './cueListEdits'

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
 * *+ Look* and *+ Palette* open the programmer's add-layer picker on the Values selection, with the
 * layer's timing; a row's *Targets* puts the layer on that selection; *Open* leaves for the Look's (or
 * palette's) library, where a contents edit changes every cue that layers it. Locked, the rows read.
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
  const { data: looks } = useLookListQuery({ projectId })
  const { data: templates } = useTemplateListQuery({ projectId })
  const looksById = useMemo(() => new Map((looks ?? []).map((l) => [l.id, l])), [looks])
  const templatesById = useMemo(() => new Map((templates ?? []).map((t) => [t.id, t])), [templates])
  const readOnly = readOnlyReason != null || store == null
  const unlock = useRefusedEditKeys({ region, readOnly, onRequestUnlock })
  const [adding, setAdding] = useState<'look' | 'template' | null>(null)

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
    (apply: (list: readonly CueLayerDetail[], drawn: readonly CueLayerDetail[]) => CueLayerDetail[] | null) => {
      if (readOnly || store == null) return Promise.resolve(false)
      const drawn = layers
      const shown = apply(drawn, drawn)
      if (shown == null) return Promise.resolve(false)
      setPending({ base: cue.layers, list: shown })
      return store.editLayers((stored) => apply(stored, drawn), true)
    },
    [readOnly, store, layers, cue.layers],
  )

  const handlers = useMemo<LayerHandlers>(() => {
    const set = (index: number, patch: Partial<CueLayer>) =>
      void edit((list, drawn) => changeDrawn(list, drawn, index, layerId, (layer) => ({ ...layer, ...patch })))
    return {
      onRemove: (index) => void edit((list, drawn) => removeDrawn(list, drawn, index, layerId)),
      onMove: (from, to) => void edit((list, drawn) => moveDrawn(list, drawn, from, to, layerId)),
      onSetEnabled: (index, enabled) => set(index, { enabled }),
      onSetAmount: (index, amount) => set(index, { amount }),
      onSetBlendMode: (index, blendMode) => set(index, { blendMode }),
      onSetPropertyMask: (index, propertyMask) => set(index, { propertyMask }),
      onSetStomp: (index, stomp) => set(index, { stomp }),
      onSetTargets: selection.length > 0 ? (index) => set(index, { targets: selection.map((t) => ({ ...t })) }) : undefined,
      onOpenSource: (index) => {
        const source = layers[index]?.source
        if (source == null) return
        const library = source.kind === 'TEMPLATE' ? 'palettes' : 'looks'
        navigate(`/projects/${projectId}/${library}?edit=${source.id}`)
      },
    }
  }, [edit, selection, layers, navigate, projectId])
  // Drawn while refused too, so the button says why; `LookStack` reads the refusal for its title.
  const shownHandlers = useMemo<LayerHandlers>(
    () => (handlers.onSetTargets != null ? handlers : { ...handlers, onSetTargets: () => {} }),
    [handlers],
  )

  const append = useCallback(
    (layer: CueLayer) => {
      const source = sourceOf(layer, looksById, templatesById)
      return edit((list) => appendItem(list, { ...layer, source }))
    },
    [edit, looksById, templatesById],
  )
  const add = (layer: CueLayer) => {
    setAdding(null)
    void append(layer)
  }
  const handHost = useMemo<HandCueLayerHost | undefined>(
    () => (store == null ? undefined : { targets: selection, append, undo: store.undo }),
    [store, selection, append],
  )

  const addTitle = (kind: string) =>
    readOnlyReason ??
    (selection.length > 0
      ? `Add a ${kind} layer on the Values selection (${selection.length})`
      : `Add a ${kind} layer — choose where it lands, or select in Values first`)

  return (
    <div className="space-y-2" data-cue-looks-tab>
      {unlock}
      <LookStack
        layers={layers}
        looksById={looksById}
        templatesById={templatesById}
        looksLoaded={looks != null && templates != null}
        handlers={shownHandlers}
        readOnly={readOnly}
        targetsRefusal={selection.length > 0 ? null : NO_VALUES_SELECTION}
        precedenceNote="Later layers win — intensity included — and the cue’s own values beat every layer."
        emptyNote="No looks layered. + Look or + Palette adds one."
        addActions={
          <div className="flex items-center gap-1">
            <Button variant="ghost" size="sm" className="h-6 gap-1 px-1.5 text-xs" disabled={readOnly} title={addTitle('look')} onClick={() => setAdding('look')}>
              <Plus className="size-3" />
              <Layers className="size-3" />
              Look
            </Button>
            <Button variant="ghost" size="sm" className="h-6 gap-1 px-1.5 text-xs" disabled={readOnly} title={addTitle('palette')} onClick={() => setAdding('template')}>
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
      <AddLayerSheet
        open={adding != null}
        onOpenChange={(open) => {
          if (!open) setAdding(null)
        }}
        projectId={projectId}
        defaultTargets={selection}
        kind={adding ?? undefined}
        onAdd={add}
      />
    </div>
  )
}

/** The new layer's source, from the library it was picked from — so the row is named before the cue is read again. */
function sourceOf(
  layer: CueLayer,
  looksById: ReadonlyMap<number, { id: number; uuid: string; name: string }>,
  templatesById: ReadonlyMap<number, { id: number; uuid: string; name: string }>,
): LayerSource | null {
  const look = layer.lookId != null ? looksById.get(layer.lookId) : undefined
  if (look) return { kind: 'LOOK', id: look.id, uuid: look.uuid, name: look.name }
  const template = layer.templateId != null ? templatesById.get(layer.templateId) : undefined
  if (template) return { kind: 'TEMPLATE', id: template.id, uuid: template.uuid, name: template.name }
  return null
}
