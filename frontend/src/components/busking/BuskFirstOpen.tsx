import { Button } from '@/components/ui/button'

/**
 * What a project with no busk pages offers (D11).
 *
 * *Start from your library* is a generator, not a migration: it reads the templates and Looks that
 * exist and lays them out plainly — a stacking bank per family, the Looks that can be busked, and
 * an empty Cues bank. **Nothing it makes is solo**, because a page that arrived with exclusivity
 * already set would make its first press do something nobody asked for.
 */
export function BuskFirstOpen({
  onStartFromLibrary,
  onStartEmpty,
  busy,
}: {
  onStartFromLibrary: () => void
  onStartEmpty: () => void
  busy: boolean
}) {
  return (
    <div className="flex min-h-0 flex-1 items-center justify-center p-6">
      <div className="max-w-md rounded-lg border bg-card p-6 text-center">
        <h2 className="text-base font-semibold">Build a busk page</h2>
        <p className="mt-2 text-sm text-muted-foreground">
          A page is rows of banks, and a bank holds pads — templates, Looks and cues, in whatever
          order suits the show. Start from what is already in the library, or from nothing.
        </p>
        <div className="mt-5 flex justify-center gap-2">
          <Button onClick={onStartFromLibrary} disabled={busy}>
            Start from your library
          </Button>
          <Button variant="outline" onClick={onStartEmpty} disabled={busy}>
            Start empty
          </Button>
        </div>
      </div>
    </div>
  )
}
