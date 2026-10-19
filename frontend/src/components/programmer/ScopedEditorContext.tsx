import type { ReactNode } from 'react'
import { EditorContextProvider, type EditorContextValue } from './EditorContext'
import { useLookRowStore } from './LookRowStore'
import { useProgrammerScope } from './ProgrammerScope'

/**
 * The `EditorContext` the programmer's scope implies: `lookLayer` while a Look layer is focused and
 * its row store is engaged, `live` otherwise — Output and a focused *template* layer included, which
 * is why every writer under it gates on `cellKeyboardPermission` rather than on this.
 *
 * **Provided unconditionally** — only the value varies, so a subtree under it never remounts on a
 * scope change (`ProgrammerGrid`'s rule, whose `useListSelection` would clear on an unmount). The
 * page keeps an outer `live` provider for the tables, so the dock's Colour and Spread tabs re-enter
 * the grid's arm here or their commits would land in Local while the grid showed a focused Look.
 * One derivation, two mounts, so the two cannot disagree.
 */
export function ScopedEditorContextProvider({ children }: { children: ReactNode }) {
  const scope = useProgrammerScope()
  const store = useLookRowStore()
  const value: EditorContextValue =
    scope?.kind === 'layer' && store ? { kind: 'lookLayer', layerId: scope.layerId, lookId: store.lookId } : { kind: 'live' }
  return <EditorContextProvider value={value}>{children}</EditorContextProvider>
}
