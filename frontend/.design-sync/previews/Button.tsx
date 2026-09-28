import { Button } from 'lighting-desk-ui'
import { Play, Plus, Trash2, Lock } from 'lucide-react'

export const Variants = () => (
  <div className="flex flex-wrap items-center gap-2">
    <Button>Record cue</Button>
    <Button variant="secondary">Duplicate</Button>
    <Button variant="outline">Cancel</Button>
    <Button variant="ghost">Skip</Button>
    <Button variant="destructive">Delete stack</Button>
    <Button variant="link">View in programmer</Button>
  </div>
)

export const Sizes = () => (
  <div className="flex flex-wrap items-center gap-2">
    <Button size="sm">Small</Button>
    <Button>Default</Button>
    <Button size="lg">Large</Button>
    <Button size="icon-sm" aria-label="Add">
      <Plus />
    </Button>
    <Button size="icon" aria-label="Go">
      <Play />
    </Button>
    <Button size="icon-lg" aria-label="Lock">
      <Lock />
    </Button>
  </div>
)

export const WithIcon = () => (
  <div className="flex flex-wrap items-center gap-2">
    <Button>
      <Play />
      GO
    </Button>
    <Button variant="outline">
      <Plus />
      New stack
    </Button>
    <Button variant="destructive">
      <Trash2 />
      Delete
    </Button>
  </div>
)

export const Disabled = () => (
  <div className="flex flex-wrap items-center gap-2">
    <Button disabled>Record cue</Button>
    <Button variant="outline" disabled>
      Cancel
    </Button>
    <Button variant="destructive" disabled>
      Delete stack
    </Button>
  </div>
)
