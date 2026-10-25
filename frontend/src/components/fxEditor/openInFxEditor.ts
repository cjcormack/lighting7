import { useCallback } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { setFxTabView, setPickedFx } from '@/lib/fxEditorView'

/**
 * **Open in FX editor** ⤢ (FX editor plan §4.6; programmer-toolbar plan D9): the programmer's FX
 * tab on *Selection*, with [fxId] picked when one is named. Who selects the heads is the caller's —
 * the fixture sheet's tray selects its target first; row C's FX picker has the selection already.
 * From the programmer itself the address is replaced, as a tab press replaces it.
 */
export function useOpenInFxEditor(): (projectId: number, fxId?: number | null) => void {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  return useCallback(
    (projectId: number, fxId: number | null = null) => {
      if (fxId != null) setPickedFx(fxId)
      setFxTabView('selection')
      const programmer = `/projects/${projectId}/programmer`
      navigate(`${programmer}?table=fx`, { replace: pathname === programmer })
    },
    [navigate, pathname],
  )
}
