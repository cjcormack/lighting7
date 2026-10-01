import { useEffect } from 'react'
import { toast } from 'sonner'
import { lightingApi } from '@/api/lightingApi'

/**
 * Says so when a fire did not happen (stage-view plan session 9, D16): a cue's events on an
 * unarmed desk, a spent tube a cue event reached, an arm that dropped before an event's offset, a
 * surface's fire while disarmed. Every window toasts it — the GO went, the confetti did not, and the
 * operator may be looking at any screen. Mounted once per window by `Layout`; renders nothing.
 */
export function EffectsAnnouncer() {
  useEffect(() => {
    const sub = lightingApi.effects.subscribeSkipped((skipped) => {
      toast.warning(skipped.message, {
        id: `effects-skipped-${skipped.cueId ?? 'x'}-${skipped.reason}-${skipped.tubes.map((t) => `${t.fixture}.${t.trigger}`).join(',')}`,
      })
    })
    return () => sub.unsubscribe()
  }, [])
  return null
}
