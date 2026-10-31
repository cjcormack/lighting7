import { useEffect, useRef, useState } from 'react'
import { lightingApi } from '@/api/lightingApi'
import type { KeyStackLayer } from '@/api/programmerWsApi'

/**
 * What a lane sits on (FX editor plan D3): the top value of `programmer.keyStack` for one head and
 * attribute — park, then the programmer's slot (Local or a pressed layer), the cue's value, the base
 * — asked when the lane draws and again whenever that key's entry or provenance moves. The desk says
 * what is underneath; nothing here works it out.
 */
export function useUnderneath(head: string | null, attribute: string | null): KeyStackLayer | null {
  const [layer, setLayer] = useState<KeyStackLayer | null>(null)
  const seq = useRef(0)

  useEffect(() => {
    if (head == null || attribute == null) {
      seq.current += 1
      setLayer(null)
      return
    }
    let live = true
    let queued = false
    const ask = () => {
      const mine = ++seq.current
      lightingApi.programmer.keyStack('fixture', head, attribute).then(
        (answer) => {
          if (!live || mine !== seq.current) return
          setLayer(topValue(answer.stacks[0]?.layers ?? [], answer.blind))
        },
        () => {
          if (live && mine === seq.current) setLayer(null)
        },
      )
    }
    ask()
    const sub = lightingApi.programmer.subscribeToKey(head, attribute, () => {
      if (queued) return
      queued = true
      queueMicrotask(() => {
        queued = false
        if (live) ask()
      })
    })
    return () => {
      live = false
      sub.unsubscribe()
    }
  }, [head, attribute])

  return layer
}

/**
 * The first layer holding a value, top first: what the lane's FX are folded over. While the
 * programmer is [blind] its slots are listed but hold nothing on stage, so the cue's value or the
 * base is underneath (`LayerResolver.fallbackFor`).
 */
export function topValue(layers: readonly KeyStackLayer[], blind = false): KeyStackLayer | null {
  return (
    layers.find(
      (l) => l.kind === 'PARK' || (l.kind === 'PROGRAMMER' && !blind) || l.kind === 'MOVE_IN_DARK' || l.kind === 'CUE' || l.kind === 'BASE',
    ) ?? null
  )
}

/**
 * The line's words — *Local 80 %*, *Q12 60 %*, *Night 40 %*, *Base 0 %*, *Parked*: who holds it,
 * then its value, a level as a percent.
 */
export function underneathLabel(layer: KeyStackLayer | null, cueLabel: (cueId: number) => string | undefined): string | null {
  if (layer == null) return null
  if (layer.kind === 'PARK') return 'Parked'
  const who =
    layer.kind === 'PROGRAMMER'
      ? layer.owner === 'layers'
        ? (layer.layerSource?.name ?? 'A layer')
        : 'Local'
      : layer.kind === 'MOVE_IN_DARK'
        ? 'Move in dark'
        : layer.kind === 'CUE'
          ? ((layer.cueId != null ? cueLabel(layer.cueId) : undefined) ?? 'Cue')
          : 'Base'
  const value = layer.value != null ? valueWords(layer.value) : null
  return value != null ? `${who} ${value}` : who
}

function valueWords(value: string): string {
  if (/^\d{1,3}$/.test(value)) return `${Math.round((Number(value) / 255) * 100)} %`
  return value.length > 14 ? `${value.slice(0, 13)}…` : value
}
