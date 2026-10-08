import { EyeOff } from 'lucide-react'
import { useProgrammerFade } from '@/lib/programmerFade'

/** `2.0 s`, `0 s` — the programmer fade as the scope line says it. */
export function formatFade(ms: string | number): string {
  const n = Number(ms) || 0
  return n === 0 ? '0 s' : `${(n / 1000).toFixed(1)} s`
}

/**
 * Where a write goes (Main board, note 2): **Local**, at the programmer fade — the rail's picker,
 * read here and never set. A blind programmer turns it amber, *values staged, not on stage*;
 * offline it says the sheet is read-only. On the right, what this fixture holds — what Release
 * takes. Below 400px of sheet the fade drops off (§4).
 */
export function ScopeLine({ blind, connected, held }: { blind: boolean; connected: boolean; held: string | null }) {
  const fade = useProgrammerFade()
  return (
    <div data-scope-line className="flex h-7 flex-none items-center gap-1.5 border-b px-3 text-[11px] text-muted-foreground">
      {!connected ? (
        <span className="text-destructive">Offline — read-only until the desk is back</span>
      ) : blind ? (
        <>
          <span className="inline-flex h-[18px] items-center gap-1 rounded-full border border-amber-500/55 px-[7px] text-[10px] font-semibold text-amber-600 dark:text-amber-400">
            <EyeOff className="size-3" aria-hidden />
            BLIND
          </span>
          <span className="truncate">values staged, not on stage</span>
        </>
      ) : (
        <span className="truncate">
          Writes to <b className="font-medium text-foreground">Local</b>
          <span className="@max-[400px]/sheet:hidden">
            {' · fade '}
            <b className="font-mono font-medium text-foreground">{formatFade(fade)}</b>
          </span>
        </span>
      )}
      <span className="flex-1" />
      {held && <span className="shrink-0">{held}</span>}
    </div>
  )
}
