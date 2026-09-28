import type { ReactNode } from 'react'
import { Loader2 } from 'lucide-react'
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { listNames } from './sheetModel'
import type { BatchDeleteState } from './useBatchDelete'

/**
 * **The one dialog a batch delete asks** (library-sheets plan D13; the Kit board's *One delete for a
 * batch*). The plain deletes have already happened; this names what went, lists the records that
 * are in use with what uses each, and asks once: *Keep them* leaves them (the sheet re-selects them),
 * *Delete anyway* forces only those.
 *
 * A confirmation, so a Dialog and not a Sheet (CLAUDE.md §Sheets vs Dialogs). [describe] is the
 * entity's own — *on 3 busk pages*, *6 references · followed by Movement* — and [consequence] the
 * sentence saying what forcing does to those users, which differs per library.
 */
export function BatchDeleteDialog<Item, S>({
  state,
  noun,
  name,
  describe,
  consequence,
  busy,
  onForce,
  onKeep,
}: {
  state: BatchDeleteState<Item, S> | null
  noun: string
  name: (item: Item) => string
  describe: (item: Item, summary: S) => ReactNode
  consequence: ReactNode
  busy: boolean
  onForce: () => void
  onKeep: () => void
}) {
  const open = state != null
  const inUseCount = state?.inUse.length ?? 0
  const deleted = state?.deleted ?? []
  return (
    <AlertDialog open={open} onOpenChange={(next) => !next && !busy && onKeep()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>
            Delete {state?.total ?? 0} {state?.total === 1 ? noun : `${noun}s`}?
          </AlertDialogTitle>
          <AlertDialogDescription>
            {deleted.length > 0 && (
              <>
                {listNames(deleted, noun)} {deleted.length === 1 ? 'was' : 'were'} deleted.{' '}
              </>
            )}
            {inUseCount === 1 ? 'One is' : `${inUseCount} are`} in use — {consequence}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <ul className="space-y-1.5 text-sm" data-testid="batch-delete-in-use">
          {/* Index keys: the list is built once per batch and never reordered, and two records of
              one library can share a name (two Looks called "Warm Wash"), so a name is no key. */}
          {state?.inUse.map((entry, i) => (
            <li key={i} className="flex flex-wrap items-baseline gap-x-2">
              <span className="font-medium">{name(entry.item)}</span>
              <span className="text-xs text-muted-foreground">{describe(entry.item, entry.summary)}</span>
            </li>
          ))}
        </ul>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={busy}>Keep them</AlertDialogCancel>
          <Button variant="destructive" onClick={onForce} disabled={busy}>
            {busy && <Loader2 className="size-4 animate-spin" />}
            Delete anyway
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
