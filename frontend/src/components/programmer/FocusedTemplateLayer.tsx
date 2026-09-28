import { createContext, useContext, useMemo } from 'react'
import { parsePropertyMask } from '@/lib/attributeFamily'
import { useFixtureListQuery } from '@/store/fixtures'
import { useProgrammerLayersQuery } from '@/store/programmer'
import { useTemplateListQuery } from '@/store/templates'
import { expandTargets } from './LookRowStore'
import { focusedLayerId, useProgrammerScope } from './ProgrammerScope'
import type { ReactNode } from 'react'
import type { AttributeFamily } from '@/lib/attributeFamily'
import type { CueTarget } from '@/api/cuesApi'
import type { TemplateSummary } from '@/api/templatesApi'

/**
 * The focused layer, when it applies a **template** — the template half of `LookRowStore`.
 *
 * A separate context rather than an arm of that one, because the two answer different questions and
 * a template layer has no rows to store: `LookRowStore` owns a *draft* and a save cadence, and
 * engages only for a LOOK source for that reason. This owns a read.
 *
 * Provided **once above the grid**, for `LookRowStore`'s reason: `useScopedRowValues` runs per row,
 * and a `useTemplateListQuery` in there would be one store subscription and one re-render path per
 * visible row for a value that changes when the library does.
 *
 * Three consumers, and each needs a different part of it: the grid (what a cell shows and whether it
 * is editable), `LayerRowNotices` (which sentence), and `ProgrammerScopeBand` (the wave on the
 * badge).
 */
export interface FocusedTemplateLayerValue {
  /** The focused stack line. Two layers may apply one template, so this is what addresses it. */
  layerId: number
  templateId: number
  /** From the layer frame, so a row is labelled before the library lands. */
  name: string | undefined
  /** The library entry, once it has landed — undefined while loading, or if it is gone. */
  template: TemplateSummary | undefined
  /**
   * What it holds (fx-templates D1), or undefined until the library says.
   *
   * **Undefined is not "value"**: an effect template's grid is a live read and a value template's is
   * empty, so guessing during the fetch would make the grid flash the wrong one.
   */
  kind: 'value' | 'effect' | undefined
  /** The layer's `propertyMask`, parsed. Empty means it asserts every attribute. */
  mask: readonly AttributeFamily[]
  /** Fixture and element keys the layer asserts on. `null` means it names no targets at all. */
  targetedKeys: ReadonlySet<string> | null
  /** The layer's target list verbatim, so `AddToTargetsButton` can append without re-reading it. */
  targets: readonly CueTarget[]
}

const FocusedTemplateLayerContext = createContext<FocusedTemplateLayerValue | null>(null)

/** Null unless a layer is focused *and* that layer applies a template. */
export function useFocusedTemplateLayer(): FocusedTemplateLayerValue | null {
  return useContext(FocusedTemplateLayerContext)
}

export function FocusedTemplateLayerProvider({
  projectId,
  children,
}: {
  projectId: number
  children: ReactNode
}) {
  const scope = useProgrammerScope()
  const layerId = focusedLayerId(scope)
  const { data: layers } = useProgrammerLayersQuery()
  const layer = layerId == null ? undefined : layers?.find((l) => l.layerId === layerId)
  const templateId = layer?.source.kind === 'TEMPLATE' ? layer.source.id : undefined

  // The whole library rather than one template: it is the same cache entry the stack rail and the
  // strip already hold, so this costs a subscription and no request. There is no `templates/{id}`
  // query to prefer anyway.
  const { data: templates } = useTemplateListQuery(
    { projectId },
    { skip: !projectId || templateId == null },
  )
  const { data: fixtures } = useFixtureListQuery()

  const targets = layer?.targets
  const targetedKeys = useMemo(
    () => (targets == null ? null : expandTargets(targets, fixtures)),
    [targets, fixtures],
  )

  const value = useMemo<FocusedTemplateLayerValue | null>(() => {
    if (layer == null || templateId == null) return null
    const template = templates?.find((t) => t.id === templateId)
    return {
      layerId: layer.layerId,
      templateId,
      name: layer.source.name,
      template,
      kind: template?.kind,
      mask: parsePropertyMask(layer.propertyMask),
      targetedKeys,
      targets: layer.targets,
    }
  }, [layer, templateId, templates, targetedKeys])

  return (
    <FocusedTemplateLayerContext.Provider value={value}>
      {children}
    </FocusedTemplateLayerContext.Provider>
  )
}
