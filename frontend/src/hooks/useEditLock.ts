import { useCallback, useEffect, useRef } from 'react'
import { useDispatch, useSelector } from 'react-redux'
import {
  lockRequested as lockRequestedAction,
  selectLockRequested,
  unlockRequested,
} from '../store/editLockSlice'
import { useCurrentProjectQuery } from '../store/projects'
import { useAutoRelock } from './useAutoRelock'

/**
 * The lock's rule, for every reader of it (show-view-fixes plan D18). A show is **guarded** while it
 * can be edited, runs, and is in production; only a guarded show locks, warns or offers the control.
 * `notInProduction` is the running show that is not guarded only because it is not in production,
 * which the header names where the control would be (D20).
 */
export function editLockRule({
  canEdit,
  isShowActive,
  inProduction,
  requested,
}: {
  canEdit: boolean
  isShowActive: boolean
  inProduction: boolean
  requested: boolean
}) {
  const guarded = canEdit && isShowActive && inProduction
  const locked = !canEdit || (guarded && requested)
  return {
    guarded,
    locked,
    lockRelevant: guarded,
    unlockedWarning: guarded && !locked,
    notInProduction: canEdit && isShowActive && !inProduction,
  }
}

/**
 * Whether the current project is in production. Unknown — still loading, or an older desk — reads
 * as in production, as the desk's own default does, so a running show never opens unguarded by
 * accident.
 */
export function useInProduction(): boolean {
  const { data } = useCurrentProjectQuery()
  return data?.inProduction ?? true
}

/**
 * The show-editing lock, shared by the Prompt Book and the merged Show view.
 *
 * It exists for one reason: **a stray click mid-show must not change the show the operator is
 * running from.** With the show stopped there is nothing to protect, so everything is simply
 * editable and none of the lock chrome appears — there is no state to warn about. A show **not in
 * production** (a project setting, off while a show is being built or teched) is never guarded
 * either: it stays editable while it runs ([editLockRule]).
 *
 * Three things about it are deliberate and easy to get wrong:
 *
 *  - **It is a stray-click guard, not access control.** The backend has no notion of it and no route
 *    refuses a write on its account, so a second client can edit a "locked" show today. Dressing it
 *    as permission would be worse than not having it. The one thing ANDed in front of it, `canEdit`,
 *    is not a role either — the backend computes it as "is this the current project".
 *  - **It is not the transport gate.** GO must work while locked; locked *is* the normal running
 *    state. Anything that disables the transport (`canOperate`) is a different question.
 *  - **Starting the show re-arms it**, so an edit session begun while stopped cannot silently carry
 *    into a running one. So does putting a running show into production.
 *
 * `onLock` is for surface-specific state that has to stand down with the lock — the Prompt Book
 * drops out of whatever annotation tool was selected, because a live tool with no way to use it is
 * worse than no tool.
 */
export function useEditLock({
  canEdit,
  isShowActive,
  onLock,
}: {
  /** Whether the backend would accept an edit at all. Not a role — see the docblock. */
  canEdit: boolean
  /** Whether the show is running. A stopped show is simply editable. */
  isShowActive: boolean
  onLock?: () => void
}) {
  const dispatch = useDispatch()
  const requested = useSelector(selectLockRequested)
  const inProduction = useInProduction()
  const rule = editLockRule({ canEdit, isShowActive, inProduction, requested })
  const { guarded, locked } = rule

  const lock = useCallback(() => {
    dispatch(lockRequestedAction())
    onLock?.()
    // `onLock` is called on every lock, including the idle re-lock, so it must be cheap and
    // idempotent. Left out of the dependency list on purpose: callers pass an inline closure, and
    // depending on it would give `lock` a new identity every render — which the re-arm effect below
    // would then treat as a reason to re-run.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dispatch])

  /**
   * Starting the show re-arms the lock — on the *transition*, not merely on being mounted while the
   * show runs. Putting a running show into production is the same transition. `canEdit` is left out
   * of it: a host whose `canEdit` arrives after mount (the Prompt Book's, off its book) would
   * otherwise re-lock a fix-it session on every visit.
   *
   * That distinction became load-bearing when the lock moved into the store. Re-arming on mount
   * would re-lock every time the operator moved between Show and the Prompt Book, which is exactly
   * the per-page behaviour sharing the lock exists to remove; a test pins it. It is still safe,
   * because the slice defaults to locked, so a fresh load of a running show is locked regardless —
   * the only way to be mounted unlocked is to have unlocked deliberately in this session.
   */
  const armed = isShowActive && inProduction
  const prevArmedRef = useRef<boolean | null>(null)
  useEffect(() => {
    const wasArmed = prevArmedRef.current
    prevArmedRef.current = armed
    if (armed && wasArmed === false) lock()
  }, [armed, lock])

  /**
   * Auto-re-lock is a mid-show safety net, so it is disarmed whenever the show is not guarded —
   * otherwise its countdown fires at an operator who never asked to be unlocked.
   */
  const relock = useAutoRelock({ locked: locked || !guarded, onRelock: lock })

  const toggleLock = useCallback(() => {
    if (!guarded) return
    if (locked) dispatch(unlockRequested())
    else lock()
  }, [guarded, locked, dispatch, lock])

  return {
    locked,
    /** Only true where the lock is a live concern — used to gate the lock chrome. */
    lockRelevant: rule.lockRelevant,
    /** A guarded show that is unlocked: the amber wash. */
    unlockedWarning: rule.unlockedWarning,
    /** Running and editable only because the show is not in production: the header says so. */
    notInProduction: rule.notInProduction,
    toggleLock,
    lock,
    /** Any edit interaction — resets the idle clock. Also the "stay unlocked" action. */
    noteEdit: relock.noteEdit,
    /** GO/advance — re-locks immediately. Pass as `onBeforeGo` to the transport. */
    noteGo: relock.noteGo,
    stayUnlocked: relock.stayUnlocked,
    countdownSecondsLeft: relock.countdownSecondsLeft,
  }
}
