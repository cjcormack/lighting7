import { useMemo, useState } from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { cn } from '@/lib/utils'
import { SWATCH_FLOOR } from '@/lib/colourMath'
import {
  findColourSource,
  findDimmerProperty,
  type ColourPropertyDescriptor,
  type ElementDescriptor,
  type Fixture,
  type SettingPropertyDescriptor,
  type SliderPropertyDescriptor,
} from '@/store/fixtures'
import { useColourAppearance } from '@/hooks/useColourAppearance'
import { useSettingColourPreview } from '@/hooks/usePropertyValues'
import { useDimmerBrightness } from '../fixtures/GelSwatch'
import { GroupPropertyVisualizer, GroupVirtualDimmerSlider } from '../fixtures/GroupPropertyVisualizers'
import type { GroupColourPropertyDescriptor } from '@/api/groupsApi'
import { EditorLabel } from '../editor/EditorLabel'
import { buildSheetRows } from './sheetRows'
import { FamilyGroups } from './FamilyGroups'
import { useFixtureSheet } from './sheetContext'

/**
 * A multi-head fixture's heads, **until session 4's head strip** (D13) replaces this whole section:
 * the all-heads controls the fixture exposes (`elementGroupProperties`, still drawn by the group
 * visualisers session 4 deletes), then one disclosure per head whose rows are the sheet's own —
 * source marks, ×, typed fields — targeted at the head's key.
 */
export function HeadsSection({ fixture }: { fixture: Fixture }) {
  const { connected } = useFixtureSheet()
  const elements = fixture.elements ?? []
  const [expanded, setExpanded] = useState<string | null>(null)
  const fixtureDimmer = findDimmerProperty(fixture.properties)
  const allHeads = fixture.elementGroupProperties ?? []
  // A pixel bar whose colour lives only on its heads, with no dimmer anywhere: the all-heads
  // Dimmer `FixtureContent` drew (`GroupVirtualDimmerSlider`), over the heads' colour.
  const allHeadsColour = allHeads.find((p): p is GroupColourPropertyDescriptor => p.type === 'colour')
  const virtualAllHeadsDimmer =
    allHeadsColour != null &&
    fixtureDimmer == null &&
    !allHeads.some((p) => p.category === 'dimmer') &&
    !fixture.properties.some((p) => p.type === 'colour')
      ? allHeadsColour
      : null
  if (elements.length === 0) return null
  return (
    <section data-heads className="flex flex-col gap-1 border-t pt-2">
      <div className="flex items-center gap-2 px-3 pt-1">
        <EditorLabel>Heads · {elements.length}</EditorLabel>
        <span className="flex gap-1">
          {elements.map((e) => (
            <HeadSwatch key={e.key} element={e} fixtureDimmer={fixtureDimmer} />
          ))}
        </span>
      </div>
      {allHeads.length > 0 && (
        <div className="px-3">
          <EditorLabel className="pt-1">All heads</EditorLabel>
          {virtualAllHeadsDimmer && <GroupVirtualDimmerSlider colourProp={virtualAllHeadsDimmer} isEditing={connected} />}
          {allHeads.map((p) => (
            <GroupPropertyVisualizer key={p.name} property={p} isEditing={connected} />
          ))}
        </div>
      )}
      {elements.map((element) => (
        <HeadDisclosure
          key={element.key}
          element={element}
          open={expanded === element.key}
          onToggle={() => setExpanded((k) => (k === element.key ? null : element.key))}
          fixtureDimmer={fixtureDimmer}
        />
      ))}
    </section>
  )
}

function HeadDisclosure({
  element,
  open,
  onToggle,
  fixtureDimmer,
}: {
  element: ElementDescriptor
  open: boolean
  onToggle: () => void
  fixtureDimmer?: SliderPropertyDescriptor
}) {
  const groups = useMemo(
    () => buildSheetRows(element.properties, { fallbackDimmer: fixtureDimmer }),
    [element.properties, fixtureDimmer],
  )
  return (
    <div className="mx-3 rounded-md border">
      <button
        type="button"
        aria-expanded={open}
        onClick={onToggle}
        className="flex w-full items-center gap-2 px-2 py-1.5 text-left text-sm hover:bg-accent/50"
      >
        <HeadSwatch element={element} fixtureDimmer={fixtureDimmer} />
        <span className="flex-1 font-medium">{element.displayName}</span>
        {open ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
      </button>
      {open && (
        <div className="border-t pb-1">
          <FamilyGroups groups={groups} headKey={element.key} />
        </div>
      )}
    </div>
  )
}

function HeadSwatch({ element, fixtureDimmer }: { element: ElementDescriptor; fixtureDimmer?: SliderPropertyDescriptor }) {
  const source = findColourSource(element.properties)
  const dimmer = findDimmerProperty(element.properties) ?? fixtureDimmer
  if (!source) return <span className="size-4 rounded bg-muted" title={element.displayName} />
  return source.type === 'colour' ? (
    <ColourDot property={source.property} dimmer={dimmer} title={element.displayName} />
  ) : (
    <SettingDot property={source.property} dimmer={dimmer} title={element.displayName} />
  )
}

function ColourDot({ property, dimmer, title }: { property: ColourPropertyDescriptor; dimmer?: SliderPropertyDescriptor; title: string }) {
  const appearance = useColourAppearance(property, dimmer, SWATCH_FLOOR)
  return <span className="size-4 rounded border" style={{ backgroundColor: appearance.appearanceCss }} title={title} />
}

function SettingDot({ property, dimmer, title }: { property: SettingPropertyDescriptor; dimmer?: SliderPropertyDescriptor; title: string }) {
  const preview = useSettingColourPreview(property)
  const brightness = useDimmerBrightness(dimmer)
  return (
    <span
      className={cn('size-4 rounded border')}
      style={{ backgroundColor: preview ?? 'transparent', filter: preview ? `brightness(${brightness})` : undefined }}
      title={title}
    />
  )
}
