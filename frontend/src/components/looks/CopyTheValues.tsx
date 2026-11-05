import { useState } from 'react'
import { Loader2 } from 'lucide-react'
import { toast } from 'sonner'
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
import type { HardenResponse } from '@/api/looksApi'
import type { PaletteReference } from '@/api/paletteReference'

/** One stored row that is a palette reference, by the uuid *Copy the values* names it by. */
export interface ReferenceRow {
  uuid: string
  reference: PaletteReference
}

/**
 * *2 values follow DSR Focus* — or *5 values follow 2 palettes* — over [rows]; null when none is a
 * reference.
 */
export function followsSentence(rows: readonly ReferenceRow[]): string | null {
  if (rows.length === 0) return null
  const palettes = new Set(rows.map((row) => row.reference.paletteUuid))
  const subject = `${rows.length} ${rows.length === 1 ? 'value follows' : 'values follow'}`
  return `${subject} ${palettes.size === 1 ? rows[0].reference.name : `${palettes.size} palettes`}.`
}

/**
 * The rows *Copy the values* would harden: the selected references where any is selected, else
 * every reference (palette-references plan D22).
 */
export function rowsToCopy(rows: readonly ReferenceRow[], selected: ReadonlySet<string>): ReferenceRow[] {
  const picked = rows.filter((row) => selected.has(row.uuid))
  return picked.length > 0 ? picked : [...rows]
}

/**
 * The Look sheet's and the cue editor's line about references, and *Copy the values…* (D9, D22):
 * the sentence, and a button that asks once and then makes the chosen references literal at what
 * each head resolves to now, so a palette edit no longer moves them. [onCopy] is the owner's harden
 * route; `null` from it is a refusal already said.
 */
export function CopyTheValues({
  rows,
  selected,
  owner,
  disabled,
  onCopy,
}: {
  rows: readonly ReferenceRow[]
  /** Row uuids picked on the owner's surface; empty is every reference. */
  selected: ReadonlySet<string>
  /** What the values belong to, for the question: *this Look*, *Q12*. */
  owner: string
  /** Why the owner cannot be written here, where it cannot. */
  disabled?: string | null
  onCopy: (rowUuids: string[] | undefined) => Promise<HardenResponse | null>
}) {
  // What the question was asked over, held while it is open: a copy that lands clears the owner's
  // picks, and the closing dialog must not re-word itself over every reference.
  const [asked, setAsked] = useState<{ chosen: ReferenceRow[]; all: boolean } | null>(null)
  const [busy, setBusy] = useState(false)
  const sentence = followsSentence(rows)
  if (sentence == null) return null
  const open = asked != null
  const setOpen = (next: boolean) => {
    if (!next) {
      setAsked(null)
      return
    }
    const chosen = rowsToCopy(rows, selected)
    setAsked({ chosen, all: chosen.length === rows.length })
  }
  const chosen = asked?.chosen ?? []
  const all = asked?.all ?? false
  const run = async () => {
    setBusy(true)
    try {
      const answer = await onCopy(all ? undefined : chosen.map((row) => row.uuid))
      if (answer == null) return
      const kept = answer.kept ?? 0
      const copied = `${answer.hardened} ${answer.hardened === 1 ? 'value' : 'values'} copied`
      if (kept > 0) toast.warning(`${copied} · ${kept} left as ${kept === 1 ? 'a reference' : 'references'}: a head it reaches has no value to keep yet`)
      else toast.success(copied)
      setOpen(false)
    } finally {
      setBusy(false)
    }
  }
  return (
    <div data-copy-the-values className="flex flex-wrap items-center gap-x-3 gap-y-1.5 text-xs text-muted-foreground">
      <span className="min-w-0 flex-1">
        {sentence} <span className="text-foreground">Copy the values</span> makes them literals, which a palette edit no
        longer moves.
      </span>
      <Button
        variant="outline"
        size="sm"
        className="h-7 px-2.5 text-xs"
        disabled={disabled != null}
        title={disabled ?? undefined}
        onClick={() => setOpen(true)}
      >
        Copy the values…
      </Button>
      <AlertDialog open={open} onOpenChange={(next) => !busy && setOpen(next)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              Copy {all && chosen.length > 1 ? 'all ' : ''}
              {chosen.length} {chosen.length === 1 ? 'value' : 'values'}?
            </AlertDialogTitle>
            <AlertDialogDescription>
              {chosen.length === 1 ? 'It becomes' : 'They become'} the literal{chosen.length === 1 ? '' : 's'} each head
              shows now, in {owner}. Retuning {paletteNames(chosen)} will no longer move{' '}
              {chosen.length === 1 ? 'it' : 'them'}.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={busy}>Cancel</AlertDialogCancel>
            <Button onClick={() => void run()} disabled={busy}>
              {busy && <Loader2 className="size-4 animate-spin" />}
              Copy the values
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

function paletteNames(rows: readonly ReferenceRow[]): string {
  const names = [...new Set(rows.map((row) => row.reference.name))]
  return names.length === 1 ? names[0] : `${names.length} palettes`
}
