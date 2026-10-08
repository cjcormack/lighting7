import { skipToken } from '@reduxjs/toolkit/query'
import { useCurrentProjectQuery } from '@/store/projects'
import { useTemplateListQuery } from '@/store/templates'
import type { ActiveEffect } from '@/store/fixtureFx'
import type { TemplateSummary } from '@/api/templatesApi'
import { isTemplateLayerInstance } from './fxEditorModel'

/**
 * The effect template a pad's running instance was spawned from — the template list's summary DTO,
 * which is what *edited* compares the instance with (fixture-fx-sheets plan §3.3, D20) — or null for
 * any other effect, a template no longer in the list, or one that no longer holds an effect.
 *
 * The current project's list: a programmer layer only ever runs in the project that is current. Its
 * own module so a host test can stub it without a store.
 */
export function useSpawningTemplate(
  effect: Pick<ActiveEffect, 'templateId' | 'programmerLayerId' | 'cueId'>,
): { template: TemplateSummary; projectId: number } | null {
  const layered = isTemplateLayerInstance(effect)
  const { data: project } = useCurrentProjectQuery(layered ? undefined : skipToken)
  const { data: templates } = useTemplateListQuery(layered && project != null ? { projectId: project.id } : skipToken)
  if (!layered || project == null) return null
  const template = templates?.find((t) => t.id === effect.templateId)
  return template?.kind === 'effect' && template.effect != null ? { template, projectId: project.id } : null
}
