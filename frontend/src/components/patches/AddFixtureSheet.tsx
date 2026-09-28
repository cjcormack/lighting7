import { useState, useMemo, useCallback, useRef } from 'react'
import {
  Sheet,
  SheetContent,
  SheetBody,
  SheetHeader,
  SheetTitle,
  SheetFooter,
} from '@/components/ui/sheet'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ChevronLeft, AlertTriangle } from 'lucide-react'
import { toast } from 'sonner'
import { useFixtureTypeListQuery } from '@/store/fixtures'
import { useCreatePatchMutation, usePatchGroupListQuery } from '@/store/patches'
import { GroupComboInput } from './GroupComboInput'
import { FixtureTypePickerContent, type FixtureCountMap } from '@/components/fixtures/FixtureTypePicker'
import { buildFixtureTypeHierarchy, type FixtureTypeHierarchy } from '@/api/fixtureTypeHierarchy'
import type { FixturePatch } from '@/api/patchApi'
import { headNumberFieldError, parseHeadNumberDraft } from '@/lib/headNumber'

interface AddFixtureSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number
  existingPatches: FixturePatch[]
}

type Step = 'type' | 'configure'

function nameToKey(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
}

/** Parse a trailing number: "FOH 3" → { base: "FOH", number: 3 }. Returns null if no trailing number. */
function parseTrailingNumber(value: string): { base: string; number: number } | null {
  const match = value.match(/^(.*?)\s+(\d+)$/)
  if (!match) return null
  return { base: match[1], number: parseInt(match[2], 10) }
}

/** Increment trailing number: "FOH 3" → "FOH 4". No number → unchanged. */
function incrementName(name: string): string {
  const parsed = parseTrailingNumber(name)
  if (!parsed) return name
  return `${parsed.base} ${parsed.number + 1}`
}

/** Find next available start channel that fits `channelCount` */
function nextFittingChannel(patches: FixturePatch[], channelCount: number): number {
  const sorted = patches.slice().sort((a, b) => a.startChannel - b.startChannel)
  let candidate = 1
  for (const p of sorted) {
    const pEnd = p.startChannel + (p.channelCount ?? 1) - 1
    if (candidate + channelCount - 1 < p.startChannel) return candidate
    candidate = pEnd + 1
  }
  return candidate <= 512 ? candidate : 1
}

export function AddFixtureSheet({
  open,
  onOpenChange,
  projectId,
  existingPatches,
}: AddFixtureSheetProps) {
  const [step, setStep] = useState<Step>('type')
  const [selectedTypeKey, setSelectedTypeKey] = useState<string | null>(null)

  const [fixtureName, setFixtureName] = useState('')
  const [key, setKey] = useState('')
  const [keyManuallyEdited, setKeyManuallyEdited] = useState(false)
  const [headDraft, setHeadDraft] = useState('')
  const [universe, setUniverse] = useState(() => {
    if (existingPatches.length === 0) return 0
    return existingPatches[existingPatches.length - 1].universe
  })
  const [startChannel, setStartChannel] = useState(1)
  const [address, setAddress] = useState('')
  const [groupName, setGroupName] = useState('')

  const nameInputRef = useRef<HTMLInputElement>(null)
  const universeInputRef = useRef<HTMLInputElement>(null)
  const startChannelInputRef = useRef<HTMLInputElement>(null)

  const { data: fixtureTypes } = useFixtureTypeListQuery()
  const [createPatch, { isLoading }] = useCreatePatchMutation()
  const { data: patchGroups } = usePatchGroupListQuery(projectId)

  const hierarchy = useMemo<FixtureTypeHierarchy | null>(() => {
    if (!fixtureTypes) return null
    return buildFixtureTypeHierarchy(fixtureTypes)
  }, [fixtureTypes])

  const fixtureCounts = useMemo<FixtureCountMap>(() => {
    const map = new Map<string, number>()
    for (const p of existingPatches) {
      map.set(p.fixtureTypeKey, (map.get(p.fixtureTypeKey) ?? 0) + 1)
    }
    return map
  }, [existingPatches])

  const selectedType = fixtureTypes?.find(t => t.typeKey === selectedTypeKey)
  const channelCount = selectedType?.channelCount ?? 0
  const universeExists = existingPatches.some(p => p.universe === universe)

  // Key derives from name unless manually edited
  const effectiveKey = keyManuallyEdited ? key : nameToKey(fixtureName)

  // Validation
  const lastChannel = startChannel + channelCount - 1
  const channelOverflow = lastChannel > 512
  const existingKeys = useMemo(() => new Set(existingPatches.map(p => p.key)), [existingPatches])
  const keyConflict = effectiveKey ? existingKeys.has(effectiveKey) : false
  const headNumber = parseHeadNumberDraft(headDraft)
  const headError = headNumberFieldError(headDraft, existingPatches, null)

  const universePatch = existingPatches.filter(p => p.universe === universe)
  const overlapWarning = useMemo(() => {
    if (channelCount === 0) return null
    const overlap = universePatch.find(p => {
      const pEnd = p.startChannel + (p.channelCount ?? 1) - 1
      return startChannel <= pEnd && lastChannel >= p.startChannel
    })
    if (overlap) return `Overlaps with "${overlap.displayName}" (ch ${overlap.startChannel}-${overlap.startChannel + (overlap.channelCount ?? 1) - 1})`
    return null
  }, [startChannel, channelCount, lastChannel, universePatch])

  const isValid = selectedTypeKey && effectiveKey && fixtureName.trim() && !channelOverflow && !overlapWarning && !keyConflict && !headError && startChannel >= 1

  // Handlers
  const handleNameChange = (value: string) => {
    setFixtureName(value)
    if (!keyManuallyEdited) {
      setKey(nameToKey(value))
    }
  }

  const handleKeyChange = (value: string) => {
    if (value === '') {
      setKeyManuallyEdited(false)
      setKey(nameToKey(fixtureName))
    } else {
      setKeyManuallyEdited(true)
      setKey(value)
    }
  }

  const handleTypeSelect = useCallback((typeKey: string | null) => {
    if (!typeKey) return
    setSelectedTypeKey(typeKey)

    const type = fixtureTypes?.find(t => t.typeKey === typeKey)
    if (type?.model) {
      setFixtureName(type.model)
      setKey(nameToKey(type.model))
      setKeyManuallyEdited(false)
    }

    const lastUniverse = existingPatches.length > 0
      ? existingPatches[existingPatches.length - 1].universe
      : 0
    setUniverse(lastUniverse)

    const typeChannelCount = type?.channelCount ?? 1
    const patchesInUniverse = existingPatches.filter(p => p.universe === lastUniverse)
    setStartChannel(nextFittingChannel(patchesInUniverse, typeChannelCount))

    setStep('configure')
    setTimeout(() => {
      universeInputRef.current?.focus()
      universeInputRef.current?.select()
    }, 50)
  }, [fixtureTypes, existingPatches])

  const handleUniverseChange = (newUniverse: number) => {
    setUniverse(newUniverse)
    const patchesInUniverse = existingPatches.filter(p => p.universe === newUniverse)
    setStartChannel(nextFittingChannel(patchesInUniverse, channelCount))
  }

  const doPatch = async () => {
    if (!selectedTypeKey || !isValid) return
    const patchedName = fixtureName.trim()
    const patchedKey = effectiveKey

    try {
      await createPatch({
        projectId,
        universe,
        fixtureTypeKey: selectedTypeKey,
        key: patchedKey,
        name: patchedName,
        startChannel,
        headNumber: typeof headNumber === 'number' ? headNumber : undefined,
        address: !universeExists && address ? address : undefined,
        groupName: groupName || undefined,
      }).unwrap()
    } catch {
      // Reported by errorToastMiddleware. Bail out so we don't claim success or advance the
      // form to the next fixture on a patch that never landed.
      return
    }

    toast.success(`Patched ${patchedName}`, {
      description: `${patchedKey} at ${universe}-${String(startChannel).padStart(3, '0')}`,
    })

    // Advance for next fixture: increment name (if it has a trailing number),
    // re-derive key, and move start channel forward
    const nextName = incrementName(patchedName)
    setFixtureName(nextName)
    if (!keyManuallyEdited) {
      setKey(nameToKey(nextName))
    } else {
      // Also increment key if it was manually edited but has a trailing number pattern
      const keyParsed = key.match(/^(.*?)-(\d+)$/)
      if (keyParsed) {
        setKey(`${keyParsed[1]}-${parseInt(keyParsed[2], 10) + 1}`)
      }
    }
    setStartChannel(startChannel + channelCount)
    // The next fixture takes the next head number, as it takes the next name and address.
    if (typeof headNumber === 'number') setHeadDraft(String(headNumber + 1))
    nameInputRef.current?.focus()
  }

  const handleClose = () => {
    setStep('type')
    setSelectedTypeKey(null)
    setFixtureName('')
    setKey('')
    setKeyManuallyEdited(false)
    setHeadDraft('')
    setStartChannel(1)
    setAddress('')
    setGroupName('')
    onOpenChange(false)
  }

  const handleFormKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && isValid && !isLoading) {
      e.preventDefault()
      doPatch()
    }
  }

  return (
    <Sheet open={open} onOpenChange={(v) => { if (!v) handleClose() }}>
      <SheetContent className="flex flex-col gap-0 p-0 sm:max-w-md">
        {step === 'type' && (
          <>
            <SheetHeader className="sr-only">
              <SheetTitle>Patch Fixture</SheetTitle>
            </SheetHeader>
            <FixtureTypePickerContent
              hierarchy={hierarchy}
              fixtureCounts={fixtureCounts}
              onSelect={handleTypeSelect}
              onClose={handleClose}
              options={{
                title: 'Patch Fixture',
                subtitle: 'Choose a fixture type to patch.',
                showClearOption: false,
                dimUnregistered: false,
                closeOnSelect: false,
              }}
            />
          </>
        )}

        {step === 'configure' && selectedType && (
          <>
            <div className="flex items-center gap-2 px-4 pt-4 pb-3">
              <Button
                variant="ghost"
                size="icon"
                className="size-7 shrink-0"
                onClick={() => setStep('type')}
              >
                <ChevronLeft className="size-4" />
              </Button>
              <div>
                <SheetTitle className="text-base">
                  {selectedType.manufacturer} {selectedType.model}
                </SheetTitle>
                <p className="text-xs text-muted-foreground">
                  {selectedType.channelCount} channels
                  {selectedType.modeName && ` · ${selectedType.modeName}`}
                </p>
              </div>
            </div>

            <SheetBody onKeyDown={handleFormKeyDown}>
              <div>
                <div className="flex items-end gap-1.5">
                  <div className="flex flex-col gap-1 min-w-0 flex-1">
                    <label className="text-[10px] text-muted-foreground uppercase tracking-wider">Universe</label>
                    <Input
                      ref={universeInputRef}
                      id="patch-universe"
                      type="number"
                      min={0}
                      value={universe}
                      onChange={e => handleUniverseChange(Number(e.target.value) || 0)}
                      onFocus={e => e.target.select()}
                      onKeyDown={e => {
                        if (e.key === '-') {
                          e.preventDefault()
                          startChannelInputRef.current?.focus()
                          startChannelInputRef.current?.select()
                        }
                      }}
                      className="h-9 text-sm text-center font-mono"
                    />
                  </div>
                  <span className="text-muted-foreground pb-2 font-mono shrink-0">-</span>
                  <div className="flex flex-col gap-1 min-w-0 flex-[2]">
                    <label className="text-[10px] text-muted-foreground uppercase tracking-wider">Start Channel</label>
                    <Input
                      ref={startChannelInputRef}
                      id="patch-start"
                      type="number"
                      min={1}
                      max={512}
                      value={startChannel}
                      onChange={e => setStartChannel(Math.max(1, Number(e.target.value) || 1))}
                      onFocus={e => e.target.select()}
                      className="h-9 text-sm text-center font-mono"
                    />
                  </div>
                </div>
                <p className="text-[10px] text-muted-foreground mt-1.5">
                  <kbd className="rounded border bg-muted px-1 py-0.5 font-mono text-[10px]">-</kbd> channel
                  {" · "}Channels {startChannel}-{Math.min(lastChannel, 512)}
                </p>
              </div>

              {!universeExists && (
                <div className="space-y-1.5">
                  <Label htmlFor="patch-address">ArtNet Address (optional)</Label>
                  <Input
                    id="patch-address"
                    placeholder="e.g. 192.168.1.100"
                    value={address}
                    onChange={e => setAddress(e.target.value)}
                  />
                  <p className="text-xs text-muted-foreground">New universe will be created</p>
                </div>
              )}

              <div className="space-y-1.5">
                <Label htmlFor="patch-name">Name</Label>
                <Input
                  ref={nameInputRef}
                  id="patch-name"
                  value={fixtureName}
                  onChange={e => handleNameChange(e.target.value)}
                  placeholder="e.g. Front PAR 1"
                />
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="patch-head">Head Number</Label>
                <Input
                  id="patch-head"
                  inputMode="numeric"
                  value={headDraft}
                  onChange={e => setHeadDraft(e.target.value)}
                  placeholder="optional — the source console's head number"
                  className="font-mono text-xs"
                />
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="patch-key">Key</Label>
                <Input
                  id="patch-key"
                  value={effectiveKey}
                  onChange={e => handleKeyChange(e.target.value)}
                  placeholder="auto-derived from name"
                  className="font-mono text-xs"
                />
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="patch-group">Group</Label>
                <GroupComboInput
                  value={groupName}
                  onChange={setGroupName}
                  groups={patchGroups ?? []}
                  placeholder="Type to assign or create..."
                />
              </div>

              {/* Warnings */}
              {channelOverflow && (
                <Warning>Extends to channel {lastChannel}. Max start: {512 - channelCount + 1}</Warning>
              )}
              {overlapWarning && (
                <Warning>{overlapWarning}</Warning>
              )}
              {keyConflict && (
                <Warning>Key &ldquo;{effectiveKey}&rdquo; already exists</Warning>
              )}
              {headError && <Warning>{headError}</Warning>}
            </SheetBody>

            <SheetFooter className="flex-row justify-end gap-2">
              <Button variant="outline" onClick={handleClose}>Close</Button>
              <Button
                disabled={!isValid || isLoading}
                onClick={doPatch}
              >
                {isLoading ? "Patching..." : "Patch"}
              </Button>
            </SheetFooter>
          </>
        )}
      </SheetContent>
    </Sheet>
  )
}

function Warning({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex items-start gap-2 p-3 rounded-md bg-destructive/10 text-destructive text-sm">
      <AlertTriangle className="size-4 shrink-0 mt-0.5" />
      <span>{children}</span>
    </div>
  )
}
