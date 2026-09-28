import { Sparkles } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from '@/components/ui/tooltip'

interface AiChatToggleProps {
  isVisible: boolean
  onToggle: () => void
}

export function AiChatToggle({ isVisible, onToggle }: AiChatToggleProps) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          onClick={onToggle}
          className={
            isVisible
              ? 'text-primary-foreground bg-primary-foreground/20 hover:bg-primary-foreground/30'
              : 'text-primary-foreground hover:bg-primary-foreground/10'
          }
        >
          <Sparkles className="size-5" />
        </Button>
      </TooltipTrigger>
      <TooltipContent>
        {isVisible ? 'Hide' : 'Show'} Lux
      </TooltipContent>
    </Tooltip>
  )
}
