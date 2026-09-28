import { useMemo, useState } from 'react'
import { Loader2, XCircle } from 'lucide-react'
import { toast } from 'sonner'
import {
  Sheet,
  SheetBody,
  SheetClose,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { formatError } from '@/lib/formatError'
import { useProjectListQuery } from '@/store/projects'
import { listNames } from './sheetModel'

/**
 * **Copy to…** over a library sheet's selection (library-sheets plan D11, D12) — one sheet for the
 * Look and template libraries, replacing `CopyLookDialog`, which copied one Look at a time from a
 * row menu.
 *
 * It is the one verb live in another project's library (D12's read-only scope), because it is the
 * one that makes the record yours — so the target defaults to **the project the desk is on** when
 * the library shown is someone else's, and to nothing otherwise, where the operator has to say.
 *
 * Each record is one request, sent in order (there is no bulk route, §7). The copy routes are in
 * `SILENT_ENDPOINTS` and this sheet reports them itself (D14): a record that fails — a name the
 * target already holds is the usual one, a 409 — is named in the alert with the reason, and stays
 * in the sheet so *Copy* again resends only what is left; the ones that landed are dropped from it.
 * A new name is offered only for a single record: over a batch one name would clash with itself.
 */
export function CopyToProjectSheet<Item>({
  open,
  onOpenChange,
  items,
  noun,
  name,
  sourceProjectId,
  copy,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The records to copy, in the sheet's visible order. */
  items: readonly Item[]
  /** The library's noun — `look`, `template`. */
  noun: string
  name: (item: Item) => string
  sourceProjectId: number
  /** One copy — the route's mutation, unwrapped, so a refusal throws. */
  copy: (item: Item, targetProjectId: number, newName: string | undefined) => Promise<unknown>
}) {
  const { data: projects } = useProjectListQuery()
  const targets = useMemo(() => (projects ?? []).filter((p) => p.id !== sourceProjectId), [projects, sourceProjectId])
  const running = projects?.find((p) => p.isCurrent)
  const defaultTarget = running != null && running.id !== sourceProjectId ? String(running.id) : ''

  const [targetId, setTargetId] = useState('')
  const [newName, setNewName] = useState('')
  const [pending, setPending] = useState<readonly Item[]>(items)
  const [failures, setFailures] = useState<{ name: string; reason: string }[]>([])
  const [busy, setBusy] = useState(false)

  // Re-seeded on each opening, from the selection the verb was pressed over — during render, on
  // `open`'s rising edge, rather than in an effect keyed on `items`: the list refetching under an
  // open sheet (a copy landing is itself a list change) must not reset what the operator chose.
  const [seededFor, setSeededFor] = useState(false)
  if (open !== seededFor) {
    setSeededFor(open)
    if (open) {
      setTargetId('')
      setNewName('')
      setPending(items)
      setFailures([])
    }
  }

  const single = pending.length === 1
  // The running project stands in until the operator picks — also where the project list had not
  // landed yet at the moment the sheet opened.
  const chosen = targetId !== '' ? targetId : defaultTarget
  const target = targets.find((p) => String(p.id) === chosen)

  const run = async () => {
    if (target == null || pending.length === 0 || busy) return
    setBusy(true)
    const failed: Item[] = []
    const reasons: { name: string; reason: string }[] = []
    let copied = 0
    for (const item of pending) {
      try {
        await copy(item, target.id, single && newName.trim() !== '' ? newName.trim() : undefined)
        copied += 1
      } catch (err) {
        failed.push(item)
        const status = (err as { status?: unknown } | null)?.status
        reasons.push({
          name: name(item),
          reason: status === 409 ? `a ${noun} of that name is already in ${target.name}` : formatError(err),
        })
      }
    }
    setBusy(false)
    if (copied > 0) {
      toast.success(`Copied ${copied} ${copied === 1 ? noun : `${noun}s`} to ${target.name}`, {
        id: `copy-to:${noun}`,
      })
    }
    if (failed.length === 0) {
      onOpenChange(false)
      return
    }
    setPending(failed)
    setFailures(reasons)
  }

  const plural = pending.length === 1 ? noun : `${noun}s`
  return (
    <Sheet open={open} onOpenChange={(next) => !busy && onOpenChange(next)}>
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>
            Copy {pending.length} {plural} to a project
          </SheetTitle>
          <SheetDescription>{listNames(pending.map(name), noun)}</SheetDescription>
        </SheetHeader>
        <SheetBody>
          {failures.length > 0 && (
            <Alert variant="destructive">
              <XCircle className="size-4" />
              <AlertDescription>
                <ul className="space-y-0.5">
                  {failures.map((f, i) => (
                    // Index keys: two records of one library can share a name.
                    <li key={i}>
                      <span className="font-medium">{f.name}</span> was not copied — {f.reason}
                    </li>
                  ))}
                </ul>
              </AlertDescription>
            </Alert>
          )}
          <div className="space-y-2">
            <Label htmlFor="copy-to-target">Target project</Label>
            <Select value={chosen} onValueChange={setTargetId}>
              <SelectTrigger id="copy-to-target" className="w-full">
                <SelectValue placeholder="Select a project" />
              </SelectTrigger>
              <SelectContent>
                {targets.map((project) => (
                  <SelectItem key={project.id} value={String(project.id)}>
                    {project.name}
                    {project.isCurrent && ' (Active)'}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          {single && (
            <div className="space-y-2">
              <Label htmlFor="copy-to-name">New name (optional)</Label>
              <Input
                id="copy-to-name"
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                placeholder={name(pending[0])}
              />
              <p className="text-xs text-muted-foreground">Leave empty to keep the name</p>
            </div>
          )}
        </SheetBody>
        <SheetFooter className="flex-row justify-end gap-2">
          <SheetClose asChild>
            <Button variant="outline" disabled={busy}>
              Cancel
            </Button>
          </SheetClose>
          <Button onClick={() => void run()} disabled={target == null || pending.length === 0 || busy}>
            {busy && <Loader2 className="size-4 animate-spin" />}
            Copy
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}
