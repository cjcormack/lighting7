import { useState, useMemo } from 'react'
import { Button } from '@/components/ui/button'
import {
  Sheet,
  SheetContent,
} from '@/components/ui/sheet'
import { Badge } from '@/components/ui/badge'
import { ChevronLeft, ChevronRight, X } from 'lucide-react'
import { cn } from '@/lib/utils'
import type { FixtureTypeHierarchy, FixtureTypeModel, FixtureTypeMode } from '@/api/fixtureTypeHierarchy'

const GENERIC_MANUFACTURER = 'Generic'

/** Map from typeKey to number of configured fixtures of that type. */
export type FixtureCountMap = Map<string, number>

interface FixtureTypePickerProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  hierarchy: FixtureTypeHierarchy | null
  fixtureCounts: FixtureCountMap
  onSelect: (typeKey: string | null) => void
}

type Step = 'manufacturer' | 'model' | 'mode'

interface ManufacturerEntry {
  name: string
  models: FixtureTypeModel[]
  hasRegistered: boolean
}

function countForMode(counts: FixtureCountMap, mode: FixtureTypeMode): number {
  return counts.get(mode.typeKey) ?? 0
}

function countForModel(counts: FixtureCountMap, model: FixtureTypeModel): number {
  return model.modes.reduce((sum, mode) => sum + countForMode(counts, mode), 0)
}

function countForModels(counts: FixtureCountMap, models: FixtureTypeModel[]): number {
  return models.reduce((sum, model) => sum + countForModel(counts, model), 0)
}

function CountBadge({ count }: { count: number }) {
  if (count === 0) return null
  return (
    <Badge variant="secondary" className="text-[10px] px-1.5 py-0 shrink-0">
      {count}
    </Badge>
  )
}

export interface FixtureTypePickerContentOptions {
  /** Header title (default: "Select Fixture Type") */
  title?: string
  /** Subtitle below the header */
  subtitle?: string
  /** Show the "Any fixture type" clear option (default: true) */
  showClearOption?: boolean
  /** Dim types that aren't currently registered/in use (default: true) */
  dimUnregistered?: boolean
  /** Close the picker after selection (default: true). Set false to let parent handle navigation. */
  closeOnSelect?: boolean
}

/**
 * Inline fixture type picker content (no Dialog wrapper).
 * Can be embedded directly in a Sheet or any container.
 */
export function FixtureTypePickerContent({ hierarchy, fixtureCounts, onSelect, onClose, options }: {
  hierarchy: FixtureTypeHierarchy | null
  fixtureCounts: FixtureCountMap
  onSelect: (typeKey: string | null) => void
  onClose: () => void
  options?: FixtureTypePickerContentOptions
}) {
  const title = options?.title ?? 'Select Fixture Type'
  const subtitle = options?.subtitle
  const showClearOption = options?.showClearOption ?? true
  const dimUnregistered = options?.dimUnregistered ?? true
  const closeOnSelect = options?.closeOnSelect ?? true
  const [step, setStep] = useState<Step>('manufacturer')
  const [selectedManufacturer, setSelectedManufacturer] = useState<ManufacturerEntry | null>(null)
  const [selectedModel, setSelectedModel] = useState<FixtureTypeModel | null>(null)

  // Build manufacturer list, bundling null/empty manufacturers as "Generic"
  const manufacturers = useMemo<ManufacturerEntry[]>(() => {
    if (!hierarchy) return []
    const entries: ManufacturerEntry[] = []
    let generalModels: FixtureTypeModel[] = []

    for (const [mfr, models] of hierarchy.manufacturers.entries()) {
      if (!mfr || mfr === '') {
        generalModels = models
      } else {
        const sorted = [...models].sort((a, b) => a.model.localeCompare(b.model))
        entries.push({
          name: mfr,
          models: sorted,
          hasRegistered: sorted.some((m) => m.isRegistered),
        })
      }
    }

    // Sort alphabetically
    entries.sort((a, b) => a.name.localeCompare(b.name))

    // Add "Generic" at the top if there are models without a manufacturer
    if (generalModels.length > 0) {
      // Sort the generic models alphabetically too
      generalModels.sort((a, b) => a.model.localeCompare(b.model))
      entries.unshift({
        name: GENERIC_MANUFACTURER,
        models: generalModels,
        hasRegistered: generalModels.some((m) => m.isRegistered),
      })
    }

    return entries
  }, [hierarchy])

  const handleSelectManufacturer = (entry: ManufacturerEntry) => {
    setSelectedManufacturer(entry)
    setStep('model')
  }

  const handleSelectModel = (model: FixtureTypeModel) => {
    if (model.modes.length === 1) {
      onSelect(model.modes[0].typeKey)
      if (closeOnSelect) onClose()
    } else {
      setSelectedModel(model)
      setStep('mode')
    }
  }

  const handleSelectMode = (mode: FixtureTypeMode) => {
    onSelect(mode.typeKey)
    if (closeOnSelect) onClose()
  }

  const handleClear = () => {
    onSelect(null)
    if (closeOnSelect) onClose()
  }

  return (
    <>
      {step === 'manufacturer' && (
        <>
          <div className="flex items-center gap-2 px-4 pt-4 pb-3">
            <Button variant="ghost" size="icon" className="size-7 shrink-0" onClick={onClose}>
              <ChevronLeft className="size-4" />
            </Button>
            <div>
              <h3 className="font-medium">{title}</h3>
              {subtitle && (
                <p className="text-xs text-muted-foreground">{subtitle}</p>
              )}
            </div>
          </div>

          <div className="flex-1 overflow-y-auto px-4 pb-4">
            {/* Clear option */}
            {showClearOption && (
              <button
                onClick={handleClear}
                className="flex items-center gap-2 w-full p-3 rounded-md border border-dashed text-left hover:bg-accent/50 transition-colors mb-2"
              >
                <X className="size-4 text-muted-foreground shrink-0" />
                <span className="text-sm text-muted-foreground">Any fixture type</span>
              </button>
            )}

            {manufacturers.length === 0 && (
              <div className="py-6 text-center text-sm text-muted-foreground">
                No fixture types available.
              </div>
            )}

            <div className="flex flex-col gap-1">
              {manufacturers.map((entry) => (
                <button
                  key={entry.name}
                  onClick={() => handleSelectManufacturer(entry)}
                  className={cn(
                    'flex items-center gap-2 p-3 rounded-md border text-left hover:bg-accent/50 transition-colors',
                    dimUnregistered && !entry.hasRegistered && 'opacity-50',
                  )}
                >
                  <div className="flex-1 min-w-0">
                    <div className="text-sm font-medium">{entry.name}</div>
                    <div className="text-xs text-muted-foreground">
                      {entry.models.length} fixture{entry.models.length !== 1 ? 's' : ''}
                    </div>
                  </div>
                  <CountBadge count={countForModels(fixtureCounts, entry.models)} />
                  <ChevronRight className="size-4 text-muted-foreground shrink-0" />
                </button>
              ))}
            </div>
          </div>
        </>
      )}

      {/* Step 2: Model */}
      {step === 'model' && selectedManufacturer && (
        <div className="flex flex-col min-h-0 flex-1">
          <div className="flex items-center gap-2 px-4 pt-4 pb-3">
            <Button
              variant="ghost"
              size="icon"
              className="size-7 shrink-0"
              onClick={() => { setSelectedManufacturer(null); setStep('manufacturer') }}
            >
              <ChevronLeft className="size-4" />
            </Button>
            <h3 className="font-medium">{selectedManufacturer.name}</h3>
          </div>

          <div className="flex-1 overflow-y-auto px-4 pb-4">
            <div className="flex flex-col gap-1">
              {selectedManufacturer.models.map((model) => (
                <button
                  key={model.model}
                  onClick={() => handleSelectModel(model)}
                  className={cn(
                    'flex items-center gap-2 p-3 rounded-md border text-left hover:bg-accent/50 transition-colors',
                    dimUnregistered && !model.isRegistered && 'opacity-50',
                  )}
                >
                  <div className="flex-1 min-w-0">
                    <div className="text-sm font-medium">{model.model}</div>
                    {model.modes.length === 1 && model.modes[0].channelCount != null && (
                      <div className="text-xs text-muted-foreground">
                        {model.modes[0].channelCount} channels
                      </div>
                    )}
                  </div>
                  {model.modes.length > 1 && (
                    <span className="text-[10px] text-muted-foreground shrink-0">
                      {model.modes.length} modes
                    </span>
                  )}
                  <CountBadge count={countForModel(fixtureCounts, model)} />
                  <ChevronRight className="size-4 text-muted-foreground shrink-0" />
                </button>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* Step 3: Mode */}
      {step === 'mode' && selectedModel && (
        <div className="flex flex-col min-h-0 flex-1">
          <div className="flex items-center gap-2 px-4 pt-4 pb-3">
            <Button
              variant="ghost"
              size="icon"
              className="size-7 shrink-0"
              onClick={() => { setSelectedModel(null); setStep('model') }}
            >
              <ChevronLeft className="size-4" />
            </Button>
            <div>
              <h3 className="font-medium">{selectedModel.model}</h3>
              <p className="text-xs text-muted-foreground">
                {selectedManufacturer?.name ?? GENERIC_MANUFACTURER}
              </p>
            </div>
          </div>

          <div className="flex-1 overflow-y-auto px-4 pb-4">
            <div className="flex flex-col gap-1">
              {selectedModel.modes.map((mode) => (
                <button
                  key={mode.typeKey}
                  onClick={() => handleSelectMode(mode)}
                  className={cn(
                    'flex items-center gap-2 p-3 rounded-md border text-left hover:bg-accent/50 transition-colors',
                    dimUnregistered && !mode.isRegistered && 'opacity-50',
                  )}
                >
                  <div className="flex-1 min-w-0">
                    <div className="text-sm font-medium">
                      {mode.modeName ?? mode.typeKey}
                    </div>
                    {mode.channelCount != null && (
                      <div className="text-xs text-muted-foreground">
                        {mode.channelCount} channels
                      </div>
                    )}
                  </div>
                  <CountBadge count={countForMode(fixtureCounts, mode)} />
                  <ChevronRight className="size-4 text-muted-foreground shrink-0" />
                </button>
              ))}
            </div>
          </div>
        </div>
      )}
    </>
  )
}

/** Sheet-wrapped fixture type picker (for standalone use outside a parent Sheet). */
export function FixtureTypePicker({ open, onOpenChange, hierarchy, fixtureCounts, onSelect }: FixtureTypePickerProps) {
  return (
    <Sheet open={open} onOpenChange={(v) => { if (!v) onOpenChange(false) }}>
      <SheetContent className="flex flex-col p-0 gap-0 sm:max-w-md">
        <FixtureTypePickerContent
          hierarchy={hierarchy}
          fixtureCounts={fixtureCounts}
          onSelect={onSelect}
          onClose={() => onOpenChange(false)}
        />
      </SheetContent>
    </Sheet>
  )
}
