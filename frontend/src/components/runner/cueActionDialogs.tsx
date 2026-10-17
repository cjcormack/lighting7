import { useCallback, useMemo, useState, type ReactNode } from 'react'
import { RecordSheet } from '@/components/programmer/RecordSheet'
import { CueDeleteDialog, type CueToDelete } from '@/components/cues/CueDeleteDialog'
import { UpdateStageDialog, type UpdateStageTarget } from '@/components/cues/UpdateStageDialog'
import { UnlockShowDialog } from './UnlockShowDialog'
import { CueActionDialogsContext, useSharedCueActionDialogs, type CueActionDialogOpeners } from './cueActionDialogsContext'

export type { CueActionDialogOpeners } from './cueActionDialogsContext'

/**
 * The cue action set's dialogs — the Record sheet, the delete confirm, *Update stage*'s confirm and
 * the unlock question — held **above the views**, so a host that unmounts cannot take one away
 * mid-gesture.
 *
 * The host is what goes: a card open only because it is live collapses on a GO from MIDI or another
 * window, a phone card moves on to the next cue, the cue sheet's bar empties with the selection —
 * and a dialog mounted inside one closed with it. `CueActionDialogsProvider` is mounted once, in
 * `Layout`; `CueActions` opens its dialogs through it, and draws them itself only where no provider
 * is mounted (a test rendering the set alone).
 */
interface CueActionDialogState {
  record: { projectId: number; cueId: number; name: string } | null
  /** Kept with null cues once closed, so the confirm stays mounted and animates shut. */
  remove: { projectId: number; cues: CueToDelete[] | null } | null
  updateStage: UpdateStageTarget | null
  unlock: (() => void) | null
}

const noop = () => {}

const CLOSED: CueActionDialogState = { record: null, remove: null, updateStage: null, unlock: null }

function useDialogState(): [CueActionDialogState, CueActionDialogOpeners, (next: Partial<CueActionDialogState>) => void] {
  const [state, setState] = useState<CueActionDialogState>(CLOSED)
  const patch = useCallback((next: Partial<CueActionDialogState>) => setState((s) => ({ ...s, ...next })), [])
  const openers = useMemo<CueActionDialogOpeners>(
    () => ({
      openRecord: (projectId, cueId, name) => patch({ record: { projectId, cueId, name } }),
      openDelete: (projectId, cues) => patch({ remove: { projectId, cues } }),
      openUpdateStage: (target) => patch({ updateStage: target }),
      // A function in state is read as an updater, so it is wrapped once more.
      openUnlock: (onUnlock) => setState((s) => ({ ...s, unlock: onUnlock })),
    }),
    [patch],
  )
  return [state, openers, patch]
}

function CueActionDialogsView({
  state,
  patch,
}: {
  state: CueActionDialogState
  patch: (next: Partial<CueActionDialogState>) => void
}) {
  return (
    <>
      {state.record != null && (
        <RecordSheet
          open
          onOpenChange={(open) => !open && patch({ record: null })}
          projectId={state.record.projectId}
          targetCueId={state.record.cueId}
          targetCueName={state.record.name}
        />
      )}
      {state.remove != null && (
        <CueDeleteDialog
          projectId={state.remove.projectId}
          cues={state.remove.cues}
          onClose={() => patch({ remove: state.remove == null ? null : { ...state.remove, cues: null } })}
        />
      )}
      {state.updateStage != null && (
        <UpdateStageDialog target={state.updateStage} onClose={() => patch({ updateStage: null })} />
      )}
      <UnlockShowDialog
        open={state.unlock != null}
        onOpenChange={(open) => !open && patch({ unlock: null })}
        onUnlock={state.unlock ?? noop}
      />
    </>
  )
}

export function CueActionDialogsProvider({ children }: { children: ReactNode }) {
  const [state, openers, patch] = useDialogState()
  return (
    <CueActionDialogsContext.Provider value={openers}>
      {children}
      <CueActionDialogsView state={state} patch={patch} />
    </CueActionDialogsContext.Provider>
  )
}

/**
 * The openers, and — where no provider is mounted — the dialogs to draw in place. A host renders
 * `local`; under the provider it is null and the dialogs are the provider's.
 */
export function useCueActionDialogs(): CueActionDialogOpeners & { local: ReactNode } {
  const shared = useSharedCueActionDialogs()
  const [state, openers, patch] = useDialogState()
  if (shared != null) return { ...shared, local: null }
  return { ...openers, local: <CueActionDialogsView state={state} patch={patch} /> }
}
