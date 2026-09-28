import { useEffect, useMemo, useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { Label } from '@/components/ui/label'
import { Loader2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useResolveTemplateMutation } from '@/store/templates'
import { templateIntentSwatch } from '@/lib/templateIntent'
import type { TemplateResolution, TemplateRow } from '@/api/templatesApi'

/** How long to sit still before asking. A colour drag emits a change per pointer move. */
const DEBOUNCE_MS = 250

/**
 * "Resolves to" — what each head will actually receive, live against the real patch.
 *
 * The panel the whole type-agnostic idea rests on. A template promises "this works on any head that
 * can do the thing", and a promise an operator cannot check before saving is worse than the
 * fixture-type constraint it replaces — so this asks the **server**, which runs the same
 * `TemplateResolver` the cook does. Nothing here computes a colour, a slot or a clamp: if it did, the
 * editor would be promising what a second implementation thinks the rig will do.
 *
 * A head with nothing in the family does not appear. A head that *could* have taken it and cannot —
 * no dimmer, no annotated degree range — appears as unsupported with the reason, because "why is that
 * bar missing?" is exactly the question this exists to answer.
 */
export function TemplateResolvesTo({
  projectId,
  rows,
}: {
  projectId: number
  rows: TemplateRow[]
}) {
  const [resolve, { isLoading }] = useResolveTemplateMutation()
  const [entries, setEntries] = useState<TemplateResolution[] | null>(null)
  const [failed, setFailed] = useState(false)

  // Serialised, so the effect keys on the *content* of the draft rather than on the array identity —
  // which changes on every render of the editor above.
  const key = useMemo(() => JSON.stringify(rows), [rows])

  useEffect(() => {
    const parsed: TemplateRow[] = JSON.parse(key)
    if (parsed.length === 0) {
      setEntries(null)
      setFailed(false)
      return
    }
    let cancelled = false
    const timer = setTimeout(() => {
      resolve({ projectId, rows: parsed })
        .unwrap()
        .then((result) => {
          if (!cancelled) {
            setEntries(result.entries)
            setFailed(false)
          }
        })
        .catch(() => {
          // A draft mid-edit can legitimately be invalid (a half-typed hex), and the write boundary
          // refuses it with a 400. That is not worth an alert here — the Save button reports it —
          // so the panel just says it cannot answer yet.
          if (!cancelled) setFailed(true)
        })
    }, DEBOUNCE_MS)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [key, projectId, resolve])

  if (rows.length === 0) {
    return (
      <div className="space-y-1.5">
        <Label>Resolves to</Label>
        <p className="text-[11px] text-muted-foreground">
          Set a value and this lists every head it reaches, and what each one will receive.
        </p>
      </div>
    )
  }

  return (
    <div className="space-y-1.5">
      <div className="flex items-center gap-2">
        <Label>Resolves to</Label>
        {isLoading && <Loader2 className="size-3 animate-spin text-muted-foreground" />}
        {entries != null && (
          <span className="text-[11px] text-muted-foreground">
            {entries.length} head{entries.length === 1 ? '' : 's'}
          </span>
        )}
      </div>

      {failed ? (
        <p className="text-[11px] text-muted-foreground">
          Not a value the desk can resolve yet.
        </p>
      ) : entries == null ? (
        <p className="text-[11px] text-muted-foreground">Checking against the patch&hellip;</p>
      ) : entries.length === 0 ? (
        <p className="text-[11px] text-muted-foreground">
          No patched head has this attribute, so this template would assert nothing.
        </p>
      ) : (
        <div className="rounded-md border divide-y max-h-56 overflow-y-auto">
          {entries.map((entry) => (
            <ResolutionRow key={`${entry.fixtureKey}:${entry.propertyName}`} entry={entry} />
          ))}
        </div>
      )}
    </div>
  )
}

function ResolutionRow({ entry }: { entry: TemplateResolution }) {
  const swatch = entry.value != null ? templateIntentSwatch(entry.value) : null
  const unsupported = entry.outcome === 'UNSUPPORTED'

  return (
    <div className={cn('flex items-center gap-2 px-2 py-1.5', unsupported && 'opacity-60')}>
      <div className="min-w-0 flex-1">
        <div className="text-xs font-medium truncate">{entry.fixtureName}</div>
        <div className="text-[10px] text-muted-foreground truncate">{entry.typeKey}</div>
      </div>

      {swatch != null && (
        <span
          className="size-4 rounded-sm border border-border/60 shrink-0"
          style={{ background: swatch }}
        />
      )}

      {entry.value != null && swatch == null && (
        <span className="font-mono text-[11px] tabular-nums text-muted-foreground shrink-0">
          {entry.value}
        </span>
      )}

      <OutcomeBadge entry={entry} />
    </div>
  )
}

function OutcomeBadge({ entry }: { entry: TemplateResolution }) {
  switch (entry.outcome) {
    case 'EXACT':
      return null
    case 'SNAPPED':
      return (
        <Badge variant="secondary" className="text-[10px] px-1.5 py-0 shrink-0">
          {entry.detail ?? 'nearest'}
          {/* One decimal: the wheel previews are documented as best-effort approximations, so more
              precision than that would be claiming an accuracy the annotation does not have. */}
          {entry.deltaE != null && ` · ΔE ${entry.deltaE.toFixed(1)}`}
        </Badge>
      )
    case 'CLAMPED':
      return (
        <Badge variant="outline" className="text-[10px] px-1.5 py-0 shrink-0">
          clamped {entry.detail}
        </Badge>
      )
    case 'DEGRADED':
      return (
        <Badge variant="outline" className="text-[10px] px-1.5 py-0 shrink-0">
          {entry.detail}
        </Badge>
      )
    case 'UNSUPPORTED':
      return (
        <Badge variant="destructive" className="text-[10px] px-1.5 py-0 shrink-0">
          {entry.detail ?? 'not supported'}
        </Badge>
      )
  }
}
