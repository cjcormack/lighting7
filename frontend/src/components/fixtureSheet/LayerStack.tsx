import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { Check, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { lightingApi } from '@/api/lightingApi'
import { getProgrammerFadeMs } from '@/lib/programmerFade'
import { getBeatDivisionLabel } from '../fx/fxConstants'
import { EditorSurface } from '../editor/EditorSurface'
import { EditorLabel } from '../editor/EditorLabel'
import { EditorReadout } from '../editor/EditorReadout'
import { useFixtureSheet } from './sheetContext'
import type { KeyStack, KeyStackLayer } from '@/api/programmerWsApi'

/** How a slot's age reads: *just now*, *40 s ago*, *2 min ago*. */
export function formatAge(ageMs: number | null | undefined): string | null {
  if (ageMs == null) return null
  if (ageMs < 5_000) return 'just now'
  if (ageMs < 60_000) return `${Math.round(ageMs / 1000)} s ago`
  return `${Math.round(ageMs / 60_000)} min ago`
}

interface LayerLine {
  title: string
  detail: string | null
  value: string | null
}

/** One stack entry in the Layers board's words. Pure, so the six kinds read as a table. */
export function describeStackLayer(layer: KeyStackLayer, cueLabel: (cueId: number) => string | undefined): LayerLine {
  const effectName = layer.effectName ?? layer.effectType ?? 'Effect'
  const division = layer.beatDivision != null ? getBeatDivisionLabel(layer.beatDivision) : null
  const paused = layer.running === false ? 'paused' : null
  switch (layer.kind) {
    case 'PARK':
      return {
        title: 'Parked',
        detail: 'the rig ignores every layer here',
        value: layer.parkedChannels?.map((c) => String(c.value)).join(', ') ?? null,
      }
    case 'PROGRAMMER_EFFECT':
      return { title: effectName, detail: ['programmer effect', division, paused].filter(Boolean).join(' · '), value: null }
    case 'PROGRAMMER': {
      const age = formatAge(layer.ageMs)
      if (layer.owner === 'layers') {
        return {
          title: layer.layerSource?.name ?? 'A layer',
          detail: ['pressed in the programmer', age && `moved ${age}`].filter(Boolean).join(' · '),
          value: layer.value ?? null,
        }
      }
      const raw = layer.channel != null ? `raw channel ${layer.universe ?? 0}.${layer.channel}` : null
      return {
        title: 'You',
        detail: ['programmer', layer.owner && layer.owner !== 'web' ? layer.owner : null, raw, age && `set ${age}`]
          .filter(Boolean)
          .join(' · '),
        value: layer.value ?? null,
      }
    }
    case 'EFFECT': {
      const cue = layer.cueId != null ? cueLabel(layer.cueId) : undefined
      const home = cue ? `${cue}'s effect` : layer.layerSource?.name ? `${layer.layerSource.name}'s effect` : 'effect'
      return {
        title: effectName,
        detail: [
          home,
          division,
          paused,
          layer.heldBack ? 'your value holds it back; it is still running and resumes in phase when you clear' : null,
        ]
          .filter(Boolean)
          .join(' · '),
        value: null,
      }
    }
    case 'CUE':
      return {
        title: (layer.cueId != null ? cueLabel(layer.cueId) : undefined) ?? 'Cue',
        detail: layer.layerSource?.name ? `the Look layer ${layer.layerSource.name}` : 'its own row',
        value: layer.value ?? null,
      }
    case 'BASE':
      return { title: 'Base', detail: 'nothing asserts it', value: layer.value ?? null }
  }
}

const EDGE: Record<KeyStackLayer['kind'], string> = {
  PARK: 'before:bg-amber-500',
  PROGRAMMER_EFFECT: 'before:bg-violet-500',
  PROGRAMMER: 'before:bg-primary',
  EFFECT: 'before:bg-violet-500',
  CUE: 'before:bg-sky-500',
  BASE: 'before:bg-transparent',
}

/** One distinct stack over a pick, and the heads that share it. */
interface StackSection {
  heads: string[]
  layers: KeyStackLayer[]
}

/**
 * The stacks over a pick, one section per distinct stack (HeadsGroups board, note 4: "the stack
 * lists each source with its heads"). Two heads share a section when every layer reads the same —
 * kind, words, value, on stage, held back — so twelve pixels at one colour are one section.
 */
export function stackSections(
  stacks: readonly KeyStack[],
  nameOf: (key: string) => string,
  cueLabel: (cueId: number) => string | undefined,
): StackSection[] {
  const sections = new Map<string, StackSection>()
  for (const stack of stacks) {
    const signature = JSON.stringify(
      stack.layers.map((l) => [l.kind, describeStackLayer(l, cueLabel), l.onStage, !!l.heldBack]),
    )
    const section = sections.get(signature)
    if (section) section.heads.push(nameOf(stack.targetKey))
    else sections.set(signature, { heads: [nameOf(stack.targetKey)], layers: stack.layers })
  }
  return [...sections.values()]
}

/**
 * A property's stack (D5): every layer with something to say, **top wins**, the one on stage
 * ticked and anything held back said out loud. The desk's answer (`programmer.keyStack`, W1) — the
 * client never works out what is underneath — asked when the stack opens and asked again whenever
 * the key's provenance moves while it is open.
 *
 * Over a pick of heads (D13) it is one stack per head, laid out as one section per distinct stack
 * with the heads that share it named above it; a group's *All* asks the desk once, with the group
 * target, which answers per member.
 *
 * Opened from a row's source chip through `EditorSurface`: beside the row on a desk, a bottom sheet
 * on a phone. *Clear yours* is the row's × from inside the stack.
 */
export function LayerStack({
  open,
  onOpenChange,
  heads,
  group,
  propertyName,
  label,
  trigger,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The heads the row covers — one, for a row on one head. */
  heads: readonly { key: string; name: string }[]
  /** A group's *All*: ask the group, and clear the group entry. */
  group?: string
  propertyName: string
  label: string
  trigger: ReactNode
}) {
  const { cueLabel, connected } = useFixtureSheet()
  const [answer, setAnswer] = useState<{ blind: boolean; stacks: KeyStack[] } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const seq = useRef(0)
  const headsKey = heads.map((h) => h.key).join('\u0000')
  const headsRef = useRef(heads)
  headsRef.current = heads

  const ask = useCallback(() => {
    const mine = ++seq.current
    const keys = headsKey.split('\u0000')
    const request: Promise<{ blind: boolean; stacks: KeyStack[] }> =
      group != null
        ? lightingApi.programmer.keyStack('group', group, propertyName)
        : // Settled, not all: one head the desk could not answer for leaves the others' stacks drawn.
          Promise.allSettled(keys.map((key) => lightingApi.programmer.keyStack('fixture', key, propertyName))).then((results) => {
            const answers = results.flatMap((r) => (r.status === 'fulfilled' ? [r.value] : []))
            if (answers.length === 0) throw (results[0] as PromiseRejectedResult).reason
            return { blind: answers.some((a) => a.blind), stacks: answers.flatMap((a) => a.stacks) }
          })
    request.then(
      (next) => {
        if (mine !== seq.current) return
        setAnswer(next)
        setError(null)
      },
      (e: unknown) => {
        if (mine !== seq.current) return
        setError(e instanceof Error ? e.message : 'The desk did not answer')
      },
    )
  }, [headsKey, group, propertyName])

  useEffect(() => {
    if (!open) {
      // Anything in flight is disowned; the next open asks afresh.
      seq.current += 1
      setAnswer(null)
      setError(null)
      return
    }
    ask()
    // Re-asked on every move of these keys' entries or provenance while open — `provenanceState`
    // is what says the stack under them changed. One write moves every picked head's key at once,
    // so the moves coalesce into one ask a microtask later: twelve pixels are twelve requests, not
    // twelve times twelve.
    let queued = false
    let live = true
    const askSoon = () => {
      if (queued) return
      queued = true
      queueMicrotask(() => {
        queued = false
        if (live) ask()
      })
    }
    const subs = headsKey.split('\u0000').map((key) => lightingApi.programmer.subscribeToKey(key, propertyName, askSoon))
    return () => {
      live = false
      subs.forEach((s) => s.unsubscribe())
    }
  }, [open, ask, headsKey, propertyName])

  const stacks = answer?.stacks ?? []
  const nameOf = (key: string) => headsRef.current.find((h) => h.key === key)?.name ?? key
  const sections = stackSections(stacks, nameOf, cueLabel)
  const layers = stacks.flatMap((s) => s.layers)
  // A slot only a pressed layer holds is not the operator's to clear: `clearEntry` leaves layer
  // slots, as the row's × knows (`rowSourceOf`).
  const holding = (stack: KeyStack) => stack.layers.some((l) => l.kind === 'PROGRAMMER' && l.owner !== 'layers')
  const holds = stacks.some(holding)
  const heldBack = layers.some((l) => l.heldBack)
  const clearYours = () => {
    const fade = getProgrammerFadeMs()
    if (group != null) {
      lightingApi.programmer.clearEntry('group', group, propertyName, fade)
      return
    }
    for (const stack of stacks) {
      if (holding(stack)) lightingApi.programmer.clearEntry('fixture', stack.targetKey, propertyName, fade)
    }
  }

  return (
    <EditorSurface open={open} onOpenChange={onOpenChange} title={`${label} · top wins`} trigger={trigger} contentClassName="w-80 p-0">
      <div className="flex flex-col" data-testid="layer-stack">
        <div className="flex h-10 items-center gap-2 border-b px-3">
          <EditorLabel>{label} · top wins</EditorLabel>
          {answer?.blind && (
            <span className="ml-auto rounded-full border border-amber-500/55 px-1.5 text-[10px] font-semibold text-amber-600 dark:text-amber-400">
              BLIND
            </span>
          )}
        </div>
        {answer == null && error == null && (
          <div className="flex items-center justify-center py-6">
            <Loader2 className="size-4 animate-spin text-muted-foreground" />
          </div>
        )}
        {error != null && <EditorReadout className="px-3 py-3" error={error} />}
        {sections.map((section, s) => (
          <div key={s} data-stack-section>
            {(sections.length > 1 || heads.length > 1) && (
              <div className="border-b border-border/50 bg-muted/40 px-3 py-1 text-[10.5px] text-muted-foreground" data-stack-heads>
                {section.heads.join(', ')}
              </div>
            )}
            {section.layers.map((layer, i) => (
              <StackLayerRow key={i} layer={layer} cueLabel={cueLabel} />
            ))}
          </div>
        ))}
        {answer != null && (
          <div className="flex items-center gap-2 px-3 py-2 text-[11px] text-muted-foreground">
            {holds ? (
              <Button variant="outline" size="sm" className="h-6 px-2 text-[11px]" disabled={!connected} onClick={clearYours}>
                {heldBack ? 'Clear yours — let it run' : 'Clear yours'}
              </Button>
            ) : (
              <span>The programmer holds nothing here.</span>
            )}
          </div>
        )}
      </div>
    </EditorSurface>
  )
}

function StackLayerRow({ layer, cueLabel }: { layer: KeyStackLayer; cueLabel: (cueId: number) => string | undefined }) {
  const line = describeStackLayer(layer, cueLabel)
  return (
    <div
      data-kind={layer.kind}
      data-on-stage={layer.onStage || undefined}
      className={cn(
        'relative flex gap-2.5 border-b border-border/50 py-2 pr-3 pl-4',
        "before:absolute before:top-2 before:bottom-2 before:left-0 before:w-[3px] before:rounded-r-sm before:content-['']",
        EDGE[layer.kind],
        !layer.onStage && 'opacity-70',
      )}
    >
      <div className="min-w-0 flex-1">
        <div className={cn('text-[12.5px] font-medium', layer.heldBack && 'line-through decoration-amber-500/80')}>{line.title}</div>
        {line.detail && <div className="text-[11px] text-muted-foreground">{line.detail}</div>}
      </div>
      <div className="flex shrink-0 flex-col items-end gap-1">
        {line.value != null && <span className="font-mono text-xs">{line.value}</span>}
        {layer.onStage && (
          <span className="inline-flex items-center gap-0.5 rounded-full border border-green-500/45 bg-green-500/15 px-1.5 text-[10px] font-semibold text-green-600 dark:text-green-400">
            <Check className="size-2.5" aria-hidden />
            on stage
          </span>
        )}
        {layer.heldBack && (
          <span className="rounded-full border border-amber-500/55 px-1.5 text-[10px] font-semibold text-amber-600 dark:text-amber-400">
            held back
          </span>
        )}
      </div>
    </div>
  )
}
