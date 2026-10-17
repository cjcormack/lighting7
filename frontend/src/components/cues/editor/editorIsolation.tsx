import { useEffect, type ReactNode, type RefObject } from 'react'
import { NoProgrammerScope } from '@/components/programmer/ProgrammerScope'
import { NoLookRowStore } from '@/components/programmer/LookRowStore'
import { NoFocusedTemplateLayer } from '@/components/programmer/FocusedTemplateLayer'
import { RailTabClaimContext } from '@/components/programmer/railTab'
import { activeKeyRegion, useKeyRegionTracking } from '@/components/sheet/keyRegion'
import { useCueActionDialogs } from '@/components/runner/cueActionDialogs'
import { isEditableTarget } from '@/lib/domUtils'

/**
 * The programmer's contexts stop here. The cue editor can open over the programmer — its FX band's
 * *on Q12* opens it in a sheet portalled from there, and React context flows through a portal — so
 * the programmer's scope, focused layers and rail-tab claim are cleared: none of them is the cue
 * editor's, and each would dim a column, refuse a write or send a gesture to the rail (P2).
 */
export function CueEditorIsolation({ children }: { children: ReactNode }) {
  return (
    <NoProgrammerScope>
      <NoLookRowStore>
        <NoFocusedTemplateLayer>
          <RailTabClaimContext.Provider value={null}>{children}</RailTabClaimContext.Provider>
        </NoFocusedTemplateLayer>
      </NoLookRowStore>
    </NoProgrammerScope>
  )
}

/**
 * **A refused ⌫ or Enter in a read-only tab is the editor's, never the transport's** — the Values
 * grid's rule, for a tab with no cell selection to hang it on (the Looks and FX tables). Locked, the
 * show binds ⌫ to BACK (`useTransportKeys`, which stands aside from a claimed key), and a key pressed
 * while the editor holds the keys is the operator pointing at the cue: so both are claimed here, in
 * capture, and ask to unlock — or, where the lock is not the operator's, are swallowed. A key on a
 * focused control or a field is that control's; a typed `l` is left alone. Answers the unlock
 * question's element, for a host with no `CueActionDialogsProvider` above it to draw.
 */
export function useRefusedEditKeys({
  region,
  readOnly,
  onRequestUnlock,
}: {
  region: RefObject<HTMLElement | null>
  readOnly: boolean
  onRequestUnlock?: () => void
}): ReactNode {
  useKeyRegionTracking()
  const { openUnlock, local } = useCueActionDialogs()
  useEffect(() => {
    if (!readOnly) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Backspace' && e.key !== 'Delete' && e.key !== 'Enter') return
      if (e.metaKey || e.ctrlKey || e.altKey || e.defaultPrevented) return
      const mine = region.current
      if (mine == null || activeKeyRegion() !== mine) return
      const target = e.target instanceof Element ? e.target : null
      if (target != null) {
        if (isEditableTarget(target)) return
        if (target.closest('button, a[href], [role="button"], [role="tab"], [role="menuitem"]') != null) return
        const blocker = target.closest('[role="dialog"], [role="alertdialog"], [role="menu"], [role="listbox"]')
        if (blocker != null && !blocker.contains(mine)) return
      }
      e.preventDefault()
      if (onRequestUnlock != null) openUnlock(onRequestUnlock)
    }
    window.addEventListener('keydown', onKeyDown, true)
    return () => window.removeEventListener('keydown', onKeyDown, true)
  }, [region, readOnly, onRequestUnlock, openUnlock])
  return local
}
