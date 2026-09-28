import type { ReactNode, Ref } from 'react'
import { Delete, Pencil } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { WORD_CLASS } from './toolbarFolds'
import type { CellActionCopy, CellKeyboardPermission } from './cellEntry'

/**
 * The selection bar's verbs for a **cell** selection: Set, Clear, Spread.
 *
 * Set and Clear are the marquee's two keys with a button on them — Enter and Backspace — and they
 * exist because a phone has neither, and because a released drag no longer opens an editor by
 * itself: the gesture says *what* to edit, and this is where the operator says *do it*. Both take
 * the surface's own answer ([permission], the shape `cellKeyboardPermission` returns on the
 * programmer) for whether they are enabled, and the surface's own words ([copy]) for why not, so a
 * button can never promise a gesture the keyboard refuses — and a refused key and a disabled
 * button always agree, since they read one object.
 *
 * Spread is a **slot** rather than a component drawn here, because which spread a surface has is
 * the surface's: the programmer's resolves levels, colours and positions on the desk and walks
 * Speed itself, the patch list's re-spaces addresses by a step, the cue sheet's spreads fade times.
 * Each hands in its own `SpreadPanel` instance (`fixtures-list/SpreadPopover` on the programmer),
 * already folded with `PHONE_FOLDED_CLASS` where the surface folds. The verb reads **Spread** with
 * the wave glyph on every sheet (editor-kit plan D1): the desk already calls the route spread, a
 * Look press "spreads" its rows, and nothing on either side called anything else fan but this verb.
 *
 * Set and Clear keep their icons at every width; only Spread folds on the phone arm. On a phone
 * Set is the only way into a selection's editor, and Clear the only way to clear one cell — so the
 * two controls the phone has no key for are the two it keeps.
 */
export function CellSelectionActions({
  copy,
  permission,
  setRef,
  onSet,
  onClear,
  onRefused,
  spread,
}: {
  copy: CellActionCopy
  /** Whether Set and Clear are offered — the keyboard's own gate, read here so the two agree. */
  permission: CellKeyboardPermission
  /**
   * The Set button itself, which is where the editor it opens is anchored — see `editorAnchorRef`
   * on the sheet's table. The panel belongs to the cell that owns it, so the button is handed down
   * to the grid rather than the editor being hoisted up here.
   */
  setRef?: Ref<HTMLButtonElement>
  /** Open the selection's editor, or close the one this button opened. */
  onSet: () => void
  onClear: () => void
  /**
   * The surface can offer a way past its own refusal. Given, a refused verb is **live and says so
   * when pressed** rather than greyed out: three dead buttons tell an operator that the sheet is
   * broken, where a press that answers "the show is locked — unlock it?" tells them what to do.
   * `permission` still decides which of the two a press does, so a button can no more *act* than
   * before; it only stops being silent.
   */
  onRefused?: () => void
  /** The surface's Spread panel, or nothing where the surface has no column that spreads. */
  spread?: ReactNode
}) {
  return (
    <>
      <Button
        ref={setRef}
        variant="outline"
        size="sm"
        disabled={!permission.entry && !onRefused}
        onClick={permission.entry ? onSet : onRefused}
        title={copy.setTitle}
        aria-label="Set"
      >
        <Pencil className="size-3.5" />
        <span className={WORD_CLASS}>Set</span>
      </Button>
      <Button
        variant="outline"
        size="sm"
        disabled={!permission.clear && !onRefused}
        onClick={permission.clear ? onClear : onRefused}
        title={copy.clearTitle}
        aria-label="Clear cells"
      >
        <Delete className="size-3.5" />
        <span className={WORD_CLASS}>Clear</span>
      </Button>
      {spread}
    </>
  )
}
