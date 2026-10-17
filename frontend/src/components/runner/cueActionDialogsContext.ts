import { createContext, useContext } from 'react'
import type { CueToDelete } from '@/components/cues/CueDeleteDialog'
import type { UpdateStageTarget } from '@/components/cues/UpdateStageDialog'

/**
 * The openers of the cue action set's dialogs (`cueActionDialogs.tsx`), in a module of their own so
 * a surface that only opens one — the cue editor's *Update stage…* — reaches the provider without
 * importing the dialogs themselves.
 */
export interface CueActionDialogOpeners {
  openRecord: (projectId: number, cueId: number, name: string) => void
  openDelete: (projectId: number, cues: CueToDelete[]) => void
  /** *Update stage…*'s confirm, which reads its counts from a dry run (show-cue-editing plan D11). */
  openUpdateStage: (target: UpdateStageTarget) => void
  /** Ask to unlock; [onUnlock] lifts the lock when the operator says yes. */
  openUnlock: (onUnlock: () => void) => void
}

export const CueActionDialogsContext = createContext<CueActionDialogOpeners | null>(null)

/** The provider's openers, or null where none is mounted. */
export function useSharedCueActionDialogs(): CueActionDialogOpeners | null {
  return useContext(CueActionDialogsContext)
}
