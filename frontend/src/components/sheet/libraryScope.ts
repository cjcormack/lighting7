import type { CellActionCopy, CellKeyboardPermission } from './cellEntry'

/** What a library sheet's read-only scope hands the kit: the gate, the words, the reason. */
export interface LibraryScope {
  /** Both cell gestures, refused off the current project. */
  permission: CellKeyboardPermission
  /** The bar's titles — the reason, where the gestures are refused. */
  copy: (cellCount: number) => CellActionCopy
  /** Why the sheet is read-only, or null on the current project. For the bar's strip and each verb. */
  reason: string | null
  /** True where the sheet is read-only — every value cell inert. */
  readOnly: boolean
}

/**
 * **Another project's library is the sheet's read-only scope** (library-sheets plan D12) — the cue
 * lock's shape, as `LOCKED_REASON` is for the cue sheet: the marquee and the selection still work,
 * every value verb is disabled with this reason, and *Copy to…* is the one live verb, since it is
 * the one that makes the record yours.
 *
 * Speed Masters is **half exempt** and does not take this whole: its routes are `withProject`, so
 * its stored fields stay editable from any project, and only the two that write the live show's
 * clocks — BPM and TAP — are read-only off the current project, which that sheet says per column.
 */
export function libraryPermission(
  isCurrentProject: boolean,
  projectName?: string | null,
  /**
   * The sentence for a library with no copy route, where *copy it here* would promise a verb the
   * sheet does not have — the FX Library, whose records are the running show's.
   */
  readOnlyReason?: string,
): LibraryScope {
  if (isCurrentProject) {
    return {
      permission: { entry: true, clear: true },
      copy: (cellCount) => {
        const cells = `${cellCount} selected cell${cellCount === 1 ? '' : 's'}`
        return { setTitle: `Set the ${cells} (Enter)`, clearTitle: `Clear the ${cells} (Backspace)` }
      },
      reason: null,
      readOnly: false,
    }
  }
  const reason =
    readOnlyReason ?? `${projectName ? `${projectName}’s` : 'Another project’s'} library — copy it here to edit`
  return {
    permission: { entry: false, clear: false },
    copy: () => ({ setTitle: reason, clearTitle: reason }),
    reason,
    readOnly: true,
  }
}
