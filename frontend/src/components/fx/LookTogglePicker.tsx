import { useMemo } from 'react'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Badge } from '@/components/ui/badge'
import { Loader2, SwatchBook } from 'lucide-react'
import { useCurrentProjectQuery } from '@/store/projects'
import { useLookListQuery, useToggleLookMutation } from '@/store/looks'
import type { LookSummary } from '@/api/looksApi'

interface LookTogglePickerProps {
  /** Target type and key the Look is put on. */
  targetType: 'fixture' | 'group'
  targetKey: string
  /** Compatible Look ids, computed by the backend from the target's capabilities alone. */
  compatibleLookIds: number[]
}

/**
 * Put a Look on one target from the FX panel, or take it off again.
 *
 * Offers only what `compatibleLookIds` names. That list is **capability-only**: the backend asks
 * "does this target have the properties this Look's deferred effects drive", not "was this Look
 * authored for this fixture", and it excludes nothing else — every Look row is bound now, so the
 * "deferred Looks only" this used to say describes no filter that exists.
 *
 * So a toggle here can land a Look whose rows name other fixtures: those rows still apply to the
 * fixtures they name, and *this* target gets only what the Look defers. The degenerate case is a
 * rows-only Look — no capabilities, so compatible with everything, and a pad for it asserts
 * nothing about this target. That is a gap in the backend's answer, not something to filter
 * around here: one owner for the rule, or the two drift.
 */
export function LookTogglePicker({
  targetType,
  targetKey,
  compatibleLookIds,
}: LookTogglePickerProps) {
  const { data: currentProject } = useCurrentProjectQuery()
  const { data: looks, isLoading } = useLookListQuery(
    { projectId: currentProject?.id ?? 0 },
    { skip: !currentProject },
  )
  const [toggleLook, { isLoading: isApplying }] = useToggleLookMutation()

  const compatible = useMemo(() => {
    if (!looks || compatibleLookIds.length === 0) return []
    const idSet = new Set(compatibleLookIds)
    return looks.filter((look) => idSet.has(look.id))
  }, [looks, compatibleLookIds])

  const handleApply = async (look: LookSummary) => {
    if (!currentProject) return
    await toggleLook({
      projectId: currentProject.id,
      lookId: look.id,
      targets: [{ type: targetType, key: targetKey }],
    })
  }

  if (!currentProject || isLoading || compatible.length === 0) return null

  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button variant="ghost" size="icon" className="size-7 shrink-0" title="Apply look">
          <SwatchBook className="size-4" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-56 p-1">
        <div className="text-xs font-medium text-muted-foreground px-2 py-1.5">Apply look</div>
        <div className="max-h-48 overflow-y-auto">
          {compatible.map((look) => (
            <button
              key={look.id}
              className="flex items-center justify-between w-full px-2 py-1.5 text-sm rounded-sm hover:bg-accent transition-colors text-left"
              onClick={() => handleApply(look)}
              disabled={isApplying}
            >
              <span className="truncate">{look.name}</span>
              {look.effectCount > 0 && (
                <Badge variant="secondary" className="text-[10px] px-1 py-0 shrink-0 ml-2">
                  {look.effectCount} fx
                </Badge>
              )}
            </button>
          ))}
        </div>
        {isApplying && (
          <div className="flex items-center justify-center py-1">
            <Loader2 className="size-3 animate-spin" />
          </div>
        )}
      </PopoverContent>
    </Popover>
  )
}
