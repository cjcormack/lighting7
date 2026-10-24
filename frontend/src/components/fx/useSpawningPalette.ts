import { skipToken } from '@reduxjs/toolkit/query'
import { useCurrentProjectQuery } from '@/store/projects'
import { usePaletteListQuery } from '@/store/palettes'
import type { ActiveFx } from '@/store/fixtureFx'
import type { PaletteSummary } from '@/api/palettesApi'
import { isPaletteLayerInstance } from './fxEditorModel'

/**
 * The FX palette a pad's running instance was spawned from — the palette list's summary DTO,
 * which is what *edited* compares the instance with (fixture-fx-sheets plan §3.3, D20) — or null for
 * any other effect, a template no longer in the list, or one that no longer holds an effect.
 *
 * The current project's list: a programmer layer only ever runs in the project that is current. Its
 * own module so a host test can stub it without a store.
 */
export function useSpawningPalette(
  effect: Pick<ActiveFx, 'paletteId' | 'programmerLayerId' | 'cueId'>,
): { palette: PaletteSummary; projectId: number } | null {
  const layered = isPaletteLayerInstance(effect)
  const { data: project } = useCurrentProjectQuery(layered ? undefined : skipToken)
  const { data: templates } = usePaletteListQuery(layered && project != null ? { projectId: project.id } : skipToken)
  if (!layered || project == null) return null
  const template = templates?.find((t) => t.id === effect.paletteId)
  return template?.kind === 'fx' && template.fx != null ? { palette: template, projectId: project.id } : null
}
