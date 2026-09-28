import { cn } from '@/lib/utils'
import { EFFECT_CATEGORY_INFO } from './fxConstants'
import type { EffectLibraryEntry } from '@/store/fixtureFx'

interface EffectCategoryPickerProps {
  effectsByCategory: Record<string, EffectLibraryEntry[]>
  onSelect: (category: string) => void
}

const CATEGORY_ORDER = ['dimmer', 'colour', 'position', 'controls']

export function EffectCategoryPicker({
  effectsByCategory,
  onSelect,
}: EffectCategoryPickerProps) {
  return (
    <div className="flex flex-col gap-2 p-4">
      {CATEGORY_ORDER.map((cat) => {
        const info = EFFECT_CATEGORY_INFO[cat]
        if (!info) return null
        const effects = effectsByCategory[cat]
        const isAvailable = effects && effects.length > 0
        const Icon = info.icon

        return (
          <button
            key={cat}
            disabled={!isAvailable}
            onClick={() => onSelect(cat)}
            className={cn(
              'flex items-center gap-3 p-4 rounded-lg border text-left transition-colors',
              isAvailable
                ? 'hover:bg-accent/50 cursor-pointer'
                : 'opacity-40 cursor-not-allowed',
            )}
          >
            <div className="size-10 rounded-md bg-muted flex items-center justify-center shrink-0">
              <Icon className="size-5" />
            </div>
            <div className="flex-1 min-w-0">
              <div className="font-medium">{info.label}</div>
              <div className="text-xs text-muted-foreground">{info.description}</div>
              {isAvailable && (
                <div className="text-xs text-muted-foreground mt-0.5">
                  {effects.length} effect{effects.length !== 1 ? 's' : ''}
                </div>
              )}
              {!isAvailable && (
                <div className="text-xs text-muted-foreground mt-0.5">
                  Not available for this fixture
                </div>
              )}
            </div>
          </button>
        )
      })}
    </div>
  )
}
