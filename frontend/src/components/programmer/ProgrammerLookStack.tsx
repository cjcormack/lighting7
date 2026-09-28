import { memo, useCallback, useMemo } from 'react'
import { useParams } from 'react-router'
import { LookStack, type LayerHandlers } from '@/components/looks/LookStack'
import { useLookListQuery } from '@/store/looks'
import { useTemplateListQuery } from '@/store/templates'
import { useProgrammerScope, useProgrammerScopeActions } from './ProgrammerScope'
import {
  programmerMoveLayer,
  programmerPatchLayer,
  programmerRemoveLayer,
  useProgrammerLayersQuery,
} from '@/store/programmer'
import type { ProgrammerLayer } from '@/store/programmer'

/**
 * The programmer's Look stack — the same `LookStack` the cue editor draws, over the live
 * programmer instead of a saved cue, at the rail's dense density.
 *
 * That is the whole point of §3.6 of the composition plan rather than a saving: the programmer *is*
 * an unsaved cue, so Record is "save this stack", Include is "load that one" and a layer list that
 * looked different here would be describing one structure twice.
 *
 * **Rows only, since session 3 of the space plan.** The stack used to carry its own section chrome
 * — a LAYERS heading, a count, an Add button, the precedence paragraph and the `AddLayerSheet` —
 * and the rail stacked that above a second, separately-headed FX band. The rail is one list now:
 * its header carries both counts, its `VALUES · top wins` label states the precedence rule, and
 * its footer owns adding (`ProgrammerAddLayerSheet`). What is left here is the translation from
 * a row's position to the layer the desk knows, which is the part only this component can do.
 *
 * Every mutation is a fire-and-forget WS op answered by the whole `programmer.layerState`
 * broadcast, so there is no optimistic update here and none is wanted: the stack is shared between
 * tabs and surfaces, and the server's ordering is the only one that composes.
 */
export const ProgrammerLookStack = memo(function ProgrammerLookStack() {
  const { projectId: projectIdParam } = useParams()
  const projectId = Number(projectIdParam)
  const { data: layerList } = useProgrammerLayersQuery()
  const { data: lookList } = useLookListQuery({ projectId }, { skip: !projectId })
  // Both libraries, because a layer can apply either — and a row that could not find its entry
  // paints as missing, so loading only one would slander every template layer in the stack.
  const { data: templateList } = useTemplateListQuery({ projectId }, { skip: !projectId })
  // Null outside the programmer page. The stack is also rendered by surfaces with no grid beside
  // it, and there the name badge stays a plain badge rather than a dead button.
  const scope = useProgrammerScope()
  const scopeActions = useProgrammerScopeActions()

  const looksById = useMemo(
    () => new Map((lookList ?? []).map((look) => [look.id, look])),
    [lookList],
  )
  const templatesById = useMemo(
    () => new Map((templateList ?? []).map((t) => [t.id, t])),
    [templateList],
  )
  const looksLoaded = lookList != null && templateList != null

  // Every layer the broadcast carries is part of the composition the operator authors. It was
  // filtered here until backend sweep item D4 deleted `ProgrammerLayerStack.installPreview` and
  // the Look editor's live-preview routes with it: the stack can no longer hold a layer that is
  // never recorded and pinned to the tail, so there is nothing left to hold back.
  const layers = useMemo(() => layerList ?? [], [layerList])

  // A stable identity, because `LookStack` memoises its dnd-kit id list on it: an inline arrow
  // would mint a fresh `items` array for `SortableContext` on every render of the pane.
  const keyFor = useCallback((layer: ProgrammerLayer) => `layer-${layer.layerId}`, [])

  const handlers: LayerHandlers = useMemo(
    () => ({
      // Index → `layerId` is this component's job, not `LookStack`'s: the list it renders is
      // filtered, so only the caller knows what position N means.
      onRemove: (index) => {
        const layer = layers[index]
        if (layer) programmerRemoveLayer(layer.layerId)
      },
      onMove: (oldIndex, newIndex) => {
        const layer = layers[oldIndex]
        // No client-side renumbering. The server renumbers the whole stack and re-ranks the running
        // effects in place, so restating `sortOrder` here would be a second opinion on an order we
        // don't own — which is also why `cueUtils`' two layer-order helpers had no caller and went.
        if (layer) programmerMoveLayer(layer.layerId, newIndex)
      },
      onSetEnabled: (index, enabled) => {
        const layer = layers[index]
        if (layer) programmerPatchLayer(layer.layerId, { enabled })
      },
      onSetAmount: (index, amount) => {
        const layer = layers[index]
        if (layer) programmerPatchLayer(layer.layerId, { amount })
      },
      onSetBlendMode: (index, blendMode) => {
        const layer = layers[index]
        if (layer) programmerPatchLayer(layer.layerId, { blendMode })
      },
      onSetPropertyMask: (index, propertyMask) => {
        const layer = layers[index]
        // `propertyMask` is `string | undefined` on the patch, and an omitted field means "leave
        // alone" — so a null (unmasked) has to travel as the empty string, which the server reads
        // as "no mask". Sending `undefined` would make clearing a mask a silent no-op.
        if (layer) programmerPatchLayer(layer.layerId, { propertyMask: propertyMask ?? '' })
      },
      onSetStomp: (index, stomp) => {
        const layer = layers[index]
        if (layer) programmerPatchLayer(layer.layerId, { stomp })
      },
      // The only handler that touches nothing on the server. Focus is a property of *this*
      // client's grid: a second desk looking at the same programmer is entitled to be reading a
      // different layer, and broadcasting a focus would drag its grid around under its hands.
      onFocus: (index) => {
        const layer = layers[index]
        if (layer) scopeActions?.setScope({ kind: 'layer', layerId: layer.layerId })
      },
    }),
    [layers, scopeActions],
  )

  return (
    <LookStack
      dense
      layers={layers}
      looksById={looksById}
      templatesById={templatesById}
      looksLoaded={looksLoaded}
      handlers={handlers}
      emptyNote="No layers. Add one below to put a look on the rig, or tap a busking pad."
      keyFor={keyFor}
      focusedIndex={
        scope?.kind === 'layer' ? layers.findIndex((l) => l.layerId === scope.layerId) : null
      }
    />
  )
})
