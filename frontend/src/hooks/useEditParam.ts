import { useEffect } from 'react'
import { useSearchParams } from 'react-router'

/**
 * `?edit=<id>` opens that record's editor and is stripped — a library route's address for one
 * record (`/palettes?edit=`, `/looks?edit=`, show-cue-editing plan §11, 18). Waits for the list and
 * for [ready], and opens nothing off the current project, where no row opens either.
 */
export function useEditParam(
  records: readonly { id: number }[] | undefined,
  ready: boolean,
  isCurrentProject: boolean,
  open: (id: number) => void,
): void {
  const [searchParams, setSearchParams] = useSearchParams()
  useEffect(() => {
    const edit = searchParams.get('edit')
    if (edit == null || records == null || !ready) return
    const id = Number(edit)
    if (isCurrentProject && records.some((r) => r.id === id)) open(id)
    const params = new URLSearchParams(searchParams)
    params.delete('edit')
    setSearchParams(params, { replace: true })
    // `open` is the caller's state setter, read when the param arrives — not a reason to re-run.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams, records, ready, isCurrentProject, setSearchParams])
}
