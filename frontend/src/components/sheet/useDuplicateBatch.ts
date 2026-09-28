import { useCallback, useRef, useState } from 'react'
import { toast } from 'sonner'
import { duplicateName } from '@/lib/duplicateName'
import { formatError } from '@/lib/formatError'

/**
 * **Duplicate** over a library sheet's selection (library-sheets plan D10, D11): one copy-route call
 * per record, into the same project, named `(Copy n)` against the library's names **and the ones
 * this batch has already minted** (`duplicateName`). The copy route rather than a rebuild from a
 * create, so every child gets a fresh uuid server-side.
 *
 * The copy routes are in `SILENT_ENDPOINTS`, so a refusal is toasted here, once for the batch, under
 * the sheet's key (D14). One batch at a time: `busy` guards a second press synchronously, before
 * the state that disables the verb has rendered.
 */
export function useDuplicateBatch<Item>({
  library,
  name,
  copy,
  toastKey,
}: {
  /** Every record in the library — the names a copy must not clash with. */
  library: readonly Item[]
  name: (item: Item) => string
  /** One copy into this project under [newName] — the route's mutation, unwrapped. */
  copy: (item: Item, newName: string) => Promise<unknown>
  /** The sheet's key — `looks`, `templates`. */
  toastKey: string
}) {
  const [duplicating, setDuplicating] = useState(false)
  const busyRef = useRef(false)

  const duplicate = useCallback(
    async (batch: readonly Item[]) => {
      if (busyRef.current || batch.length === 0) return
      busyRef.current = true
      setDuplicating(true)
      const taken = new Set(library.map(name))
      const refused: string[] = []
      try {
        for (const item of batch) {
          try {
            await copy(item, duplicateName(name(item), taken))
          } catch (err) {
            refused.push(`${name(item)}: ${formatError(err)}`)
          }
        }
      } finally {
        busyRef.current = false
        setDuplicating(false)
      }
      if (refused.length > 0) {
        toast.error(`Not duplicated — ${refused.join('; ')}`, { id: `sheet-write:${toastKey}:duplicate` })
      }
    },
    [copy, library, name, toastKey],
  )

  return { duplicate, duplicating }
}
