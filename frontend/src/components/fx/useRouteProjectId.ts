import { useParams } from 'react-router'

/**
 * The project the route names, or undefined outside one — the FX library page has none. The FX
 * colour pickers are mounted from sheets that thread no project down, so their recent values read it
 * here.
 */
export function useRouteProjectId(): number | undefined {
  const { projectId } = useParams<{ projectId: string }>()
  const id = projectId != null ? Number(projectId) : NaN
  return Number.isFinite(id) ? id : undefined
}
