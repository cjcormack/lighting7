import { Badge } from 'lighting-desk-ui'
import { Layers, Palette, Lock, Zap } from 'lucide-react'

export const Variants = () => (
  <div className="flex flex-wrap items-center gap-2">
    <Badge>Live</Badge>
    <Badge variant="secondary">Standby</Badge>
    <Badge variant="outline">Blind</Badge>
    <Badge variant="destructive">Blackout</Badge>
  </div>
)

export const WithIcon = () => (
  <div className="flex flex-wrap items-center gap-2">
    <Badge variant="secondary">
      <Layers />
      Warm Wash
    </Badge>
    <Badge variant="secondary">
      <Palette />
      Congo Blue
    </Badge>
    <Badge variant="outline">
      <Lock />
      Locked
    </Badge>
    <Badge>
      <Zap />
      3 effects
    </Badge>
  </div>
)

export const Counts = () => (
  <div className="flex flex-wrap items-center gap-2">
    <Badge className="font-mono">U1</Badge>
    <Badge variant="outline" className="font-mono">
      12 ch
    </Badge>
    <Badge variant="secondary" className="font-mono">
      124 BPM
    </Badge>
    <Badge variant="outline" className="text-[10px] px-1.5 py-0">
      current
    </Badge>
  </div>
)

export const InContext = () => (
  <div className="flex w-full max-w-sm items-center gap-2 rounded-md border px-3 py-2 text-sm">
    <span className="font-mono text-xs text-muted-foreground">12.5</span>
    <span className="flex-1 truncate">Blackout to walk-in</span>
    <Badge variant="outline">Auto</Badge>
    <Badge>GO</Badge>
  </div>
)
