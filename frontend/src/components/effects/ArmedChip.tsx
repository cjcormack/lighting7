import { Flame } from 'lucide-react'
import { toast } from 'sonner'
import { cn } from '@/lib/utils'
import { formatError } from '@/lib/formatError'
import { useCurrentProjectQuery } from '@/store/projects'
import { useArmEffectsMutation, useEffectsArm } from '@/store/effects'
import { useArmCountdown } from './useArmCountdown'

/**
 * The red **ARMED** chip (stage-view plan session 9, D16; the Cue board's callout 4): drawn on every
 * window's header while the desk is armed — the app header, and the `ShowHeader` row an immersive
 * view keeps — counting down to when the arm lapses. Tapping it disarms, desk-wide.
 *
 * Nothing while disarmed: the quiet state is the default, and a chip reading "disarmed" all night is
 * exactly the noise this exists to avoid. In Blind the arm still counts down but the cannons' master
 * channels are held down and every fire is rehearsed, which the title says.
 */
export function ArmedChip({ className }: { className?: string }) {
  const arm = useEffectsArm()
  const seconds = useArmCountdown(arm)
  const { data: project } = useCurrentProjectQuery()
  const [setArm, { isLoading }] = useArmEffectsMutation()
  if (!arm.armed || seconds == null) return null

  const disarm = () => {
    if (project == null) return
    setArm({ projectId: project.id, on: false })
      .unwrap()
      .catch((e: unknown) => toast.error(`Couldn't disarm: ${formatError(e)}`))
  }

  return (
    <button
      type="button"
      onClick={disarm}
      disabled={isLoading}
      className={cn(
        'inline-flex h-7 shrink-0 items-center gap-1.5 rounded-md border border-red-400/70 bg-red-600 px-2.5 text-[11px] font-bold uppercase tracking-wider text-white shadow-sm hover:bg-red-700 disabled:opacity-70',
        className,
      )}
      title={
        arm.rehearsal
          ? 'The desk is armed, but the programmer is blind: every fire is rehearsed and the cannons stay safe. Tap to disarm.'
          : 'The desk is armed: cue events and the cannons can fire. Tap to disarm.'
      }
      aria-label={`Armed, ${seconds} seconds left. Disarm`}
    >
      <Flame className="size-3.5" />
      Armed · {seconds}s
    </button>
  )
}
