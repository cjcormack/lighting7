import { Button } from '@/components/ui/button'
import { X, Play, Square } from 'lucide-react'
import { formatMs } from '@/lib/formatMs'
import type { CueTriggerDetail } from '@/api/cuesApi'

const TRIGGER_ICONS: Record<string, typeof Play> = {
  ACTIVATION: Play,
  DEACTIVATION: Square,
}

const TRIGGER_LABELS: Record<string, string> = {
  ACTIVATION: 'On activate',
  DEACTIVATION: 'On deactivate',
}

function describeTrigger(trigger: CueTriggerDetail): string {
  const parts: string[] = []

  // Base label from type
  const label = TRIGGER_LABELS[trigger.triggerType] ?? trigger.triggerType

  // Derive timing description from fields
  if (trigger.intervalMs) {
    const interval = formatMs(trigger.intervalMs)
    if (trigger.randomWindowMs) {
      parts.push(`Every ~${interval} (±${formatMs(trigger.randomWindowMs)})`)
    } else {
      parts.push(`Every ${interval}`)
    }
    if (trigger.delayMs) {
      parts[0] += ` after ${formatMs(trigger.delayMs)}`
    }
  } else if (trigger.delayMs) {
    parts.push(`${label} after ${formatMs(trigger.delayMs)}`)
  } else {
    parts.push(label)
  }

  // Script action description
  const name = trigger.scriptName ?? `Script #${trigger.scriptId}`
  parts.push(`run "${name}"`)

  return parts.join(': ')
}

interface TriggerSummaryProps {
  trigger: CueTriggerDetail
  onClick?: () => void
  onRemove?: () => void
}

export function TriggerSummary({ trigger, onClick, onRemove }: TriggerSummaryProps) {
  const Icon = TRIGGER_ICONS[trigger.triggerType] ?? Play

  return (
    <div
      className="flex items-center gap-2 rounded-md border p-2 text-sm cursor-pointer hover:bg-accent/50 transition-colors"
      onClick={onClick}
    >
      <Icon className="size-3.5 text-muted-foreground shrink-0" />
      <span className="flex-1 truncate text-xs">{describeTrigger(trigger)}</span>
      {onRemove && (
        <Button
          variant="ghost"
          size="icon"
          className="size-7 text-muted-foreground hover:text-destructive shrink-0"
          onClick={(e) => {
            e.stopPropagation()
            onRemove()
          }}
        >
          <X className="size-3.5" />
        </Button>
      )}
    </div>
  )
}
