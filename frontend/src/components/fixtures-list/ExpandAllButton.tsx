import { ChevronsDownUp, ChevronsUpDown } from 'lucide-react'
import { Button } from '@/components/ui/button'

/**
 * *Expand all* while anything shown is closed, else *Collapse all* (programmer-fixes plan D12).
 * The words ride the title and the label class the caller passes, so a row short of width can draw
 * the glyph alone.
 */
export function ExpandAllButton({
  closed,
  disabled,
  onPress,
  labelClassName,
}: {
  /** Something shown is closed: the press expands. */
  closed: boolean
  /** Nothing shown can open or close. */
  disabled: boolean
  onPress: (open: boolean) => void
  labelClassName: string
}) {
  const label = closed ? 'Expand all' : 'Collapse all'
  const Icon = closed ? ChevronsUpDown : ChevronsDownUp
  return (
    <Button
      variant="outline"
      size="sm"
      className="shrink-0"
      disabled={disabled}
      onClick={() => onPress(closed)}
      title={`${label} — ⌥-click a chevron for its level alone`}
      aria-label={label}
    >
      <Icon className="size-3.5" />
      <span className={labelClassName}>{label}</span>
    </Button>
  )
}
