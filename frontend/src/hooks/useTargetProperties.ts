import { useMemo } from 'react'
import { useFixtureListQuery, useVisibleFixtureListQuery, type PropertyDescriptor } from '@/store/fixtures'
import { useGroupPropertiesQuery } from '@/store/groups'
import type { GroupPropertyDescriptor } from '@/api/groupsApi'

/**
 * Fixture and group property lookup, in the two shapes surfaces actually want.
 *
 * This closes `FU-FE-USE-TARGET-PROPERTIES`, whose trigger — a further consumer of the same
 * fetch-and-categorise — fired with the MIDI surface library's per-target property chips. The
 * follow-up asked for one hook; there are **two exports**, because the consumers want different
 * halves of the same data and folding them together would lose one:
 *
 * - [categoriseProperties] is pure and generic over both descriptor unions. The surfaces that
 *   *render* properties — `GroupCard` (and through it `GroupDetailModal`) — need
 *   the descriptors themselves, `min` / `max` and channel refs included, because they draw live
 *   controls. All they ever duplicated was the bucketing, in two byte-identical copies.
 * - [useTargetProperties] is the flat, uniform list: a name, a label, and whether the property can
 *   be driven from a fader or encoder at all. The surfaces that *bind* properties never touch a
 *   channel — they mint a `fixtureProperty` / `groupProperty` / `selectionProperty` target and let
 *   the desk resolve it — so handing them descriptors would be handing them a shape whose
 *   difference between the two kinds they would then have to flatten again.
 *
 * `continuous` mirrors `PropertyChannelResolver`: it writes a **slider** (one channel, scaled into
 * the property's own range) and a **colour** (one of four HSV axes — hue, a fine hue trim,
 * saturation, brightness — moved on the head's current colour; `lib/colourAxis.ts` names them and
 * the library offers a chip per axis), refuses a **setting** by name, and has no arm for a
 * **position** pair. So a position or setting chip on a fader would be a control that silently
 * does nothing, and the library does not offer one.
 *
 * **A colour descriptor expands to the colour plus its bundled emitters.** The fixture descriptor
 * list omits a `bundleWithColour` slider — `white`, `amber`, `uv` — and folds its channel into the
 * colour's `whiteChannel` / `amberChannel` / `uvChannel`; but the desk drives each as a slider by
 * that name (`FixtureProperty(key, "white")` dispatches today), so [expand] mints one
 * [AvailableProperty] per present emitter. The name is the category — every fixture type names its
 * bundled emitter that way, and lighting7's `BundledEmitterNamesTest` pins it.
 */

export type TargetPropertyType = PropertyDescriptor['type']

/** One property, in the shape a binding surface needs: what to name, and whether a fader can drive it. */
export interface AvailableProperty {
  name: string
  displayName: string
  type: TargetPropertyType
  category: string
  /** Writable from a fader or encoder — see the module docblock. */
  continuous: boolean
}

type AnyPropertyDescriptor = PropertyDescriptor | GroupPropertyDescriptor

/**
 * The buckets every property-rendering surface splits into, each narrowed to its own descriptor.
 *
 * `Extract` rather than a bare `P[]`: the callers hand these straight to controls that need the
 * shape (`GroupSliderControl` wants `memberChannels`, the colour visualiser wants the emitter
 * channels), and the hand-rolled copies this replaces narrowed by `.filter(p => p.type === …)`.
 * Returning the union would have pushed a cast into every call site.
 */
export interface CategorisedProperties<P extends AnyPropertyDescriptor> {
  colour: Extract<P, { type: 'colour' }>[]
  position: Extract<P, { type: 'position' }>[]
  /** Sliders whose category is `dimmer` — drawn first and largest by every caller. */
  dimmer: Extract<P, { type: 'slider' }>[]
  /** Every other slider. */
  slider: Extract<P, { type: 'slider' }>[]
  setting: Extract<P, { type: 'setting' }>[]
}

/**
 * Split properties into the five buckets the fixture and group views draw.
 *
 * Generic over the descriptor rather than narrowed to a union of both, so a caller keeps the exact
 * type it passed in: `GroupCard` gets `GroupSliderPropertyDescriptor[]` out of `.dimmer` and can
 * hand it straight to the control that needs `memberChannels`.
 */
export function categoriseProperties<P extends AnyPropertyDescriptor>(
  properties: readonly P[] | undefined,
): CategorisedProperties<P> {
  const result: CategorisedProperties<P> = {
    colour: [],
    position: [],
    dimmer: [],
    slider: [],
    setting: [],
  }
  // `switch` narrows `property.type` but cannot narrow `P` itself, so each push asserts what the
  // discriminant has already established. The assertion is at the one place rather than at four
  // call sites, which is the whole point of the `Extract` return type.
  for (const property of properties ?? []) {
    switch (property.type) {
      case 'colour':
        result.colour.push(property as Extract<P, { type: 'colour' }>)
        break
      case 'position':
        result.position.push(property as Extract<P, { type: 'position' }>)
        break
      case 'slider':
        if (property.category === 'dimmer') {
          result.dimmer.push(property as Extract<P, { type: 'slider' }>)
        } else {
          result.slider.push(property as Extract<P, { type: 'slider' }>)
        }
        break
      case 'setting':
        result.setting.push(property as Extract<P, { type: 'setting' }>)
        break
    }
  }
  return result
}

function isContinuous(type: TargetPropertyType): boolean {
  return type === 'slider' || type === 'colour'
}

function toAvailable(property: AnyPropertyDescriptor): AvailableProperty {
  return {
    name: property.name,
    displayName: property.displayName,
    type: property.type,
    category: property.category,
    continuous: isContinuous(property.type),
  }
}

/** The bundled emitters, by the colour-descriptor key that says a head has one. */
const EMITTERS = [
  { key: 'whiteChannel', name: 'white' },
  { key: 'amberChannel', name: 'amber' },
  { key: 'uvChannel', name: 'uv' },
] as const

function hasEmitter(property: AnyPropertyDescriptor, key: (typeof EMITTERS)[number]['key']): boolean {
  if (property.type !== 'colour') return false
  if ('memberColourChannels' in property) {
    return property.memberColourChannels.some((member) => member[key] != null)
  }
  return property[key] != null
}

/** A property as the binding surfaces see it — a colour brings its bundled emitters with it. */
function expand(property: AnyPropertyDescriptor): AvailableProperty[] {
  // A one-shot trigger and a fixture command are listed with the properties but are none: nothing
  // binds, records or sets one, and the desk refuses either by name (`TRIGGER_NOT_STORABLE`,
  // `COMMAND_NOT_STORABLE`). Offering one in a picker would only offer a 400.
  if (property.type === 'trigger' || property.type === 'command') return []
  const out = [toAvailable(property)]
  if (property.type !== 'colour') return out
  for (const emitter of EMITTERS) {
    if (!hasEmitter(property, emitter.key)) continue
    out.push({
      name: emitter.name,
      displayName: emitter.name,
      type: 'slider',
      category: emitter.name,
      continuous: true,
    })
  }
  return out
}

/**
 * Display order: the three a busking operator reaches for first, then the colour and the emitters
 * beside it, then everything else by label.
 *
 * Named categories rather than named properties, so a rig whose dimmer is called `intensity` still
 * sorts first. It is presentation only — nothing downstream reads the order.
 */
const CATEGORY_RANK = ['dimmer', 'pan', 'tilt', 'colour', 'white', 'amber', 'uv']

function comparePropertyOrder(a: AvailableProperty, b: AvailableProperty): number {
  const ra = CATEGORY_RANK.indexOf(a.category)
  const rb = CATEGORY_RANK.indexOf(b.category)
  const na = ra < 0 ? CATEGORY_RANK.length : ra
  const nb = rb < 0 ? CATEGORY_RANK.length : rb
  if (na !== nb) return na - nb
  return a.displayName.localeCompare(b.displayName)
}

/** What a binding surface asks a target for. `{ type, key }` — the shared target shape. */
export interface PropertyTarget {
  type: 'fixture' | 'group'
  key: string
}

export interface TargetProperties {
  properties: AvailableProperty[]
  isLoading: boolean
}

const NO_PROPERTIES: AvailableProperty[] = []

/**
 * The properties of one fixture or group, flat.
 *
 * Both queries are mounted unconditionally and one is skipped, because a hook cannot be. That is
 * cheaper than it looks: `useFixtureListQuery` is one request for the whole rig and is already
 * mounted by most of the app, and the group query is skipped for a fixture target. A group's
 * properties genuinely are a per-group fetch — `GroupSummary` carries a member *count* and no
 * members — so a list of group rows costs one request per row, which is what `/groups` already
 * does with a card per group.
 */
export function useTargetProperties(target: PropertyTarget | null): TargetProperties {
  const isGroup = target?.type === 'group'
  const { data: fixtures, isLoading: fixturesLoading } = useFixtureListQuery(undefined, {
    skip: target == null || isGroup,
  })
  const { data: groupProperties, isLoading: groupLoading } = useGroupPropertiesQuery(
    target?.key ?? '',
    { skip: !isGroup },
  )

  const properties = useMemo(() => {
    if (target == null) return NO_PROPERTIES
    const descriptors: readonly AnyPropertyDescriptor[] | undefined = isGroup
      ? groupProperties
      : fixtures?.find((f) => f.key === target.key)?.properties
    if (descriptors == null) return NO_PROPERTIES
    return descriptors.flatMap(expand).sort(comparePropertyOrder)
  }, [target, isGroup, fixtures, groupProperties])

  return { properties, isLoading: isGroup ? groupLoading : fixturesLoading }
}

/**
 * Every property name anywhere in the patch, deduplicated.
 *
 * This is the vocabulary a **target-less** binding is chosen from — `SelectionProperty`, which
 * writes whatever is selected, and `EncoderBankSet`, which names what every strip encoder becomes.
 * Neither has a fixture to ask, so the only honest answer is the union of the rig: a property no
 * selected head declares simply drops its move (D3), which is a fact about the selection rather
 * than a bad binding.
 *
 * Deduplicated **by name**, and the first descriptor wins. Two fixtures declaring `dimmer` with
 * different ranges are one entry here on purpose — the target carries a name, and the desk resolves
 * the range per head at write time.
 *
 * The rig an operator is offered, so infrastructure is left out: a property only a power relay
 * declares is not one to bind a fader to.
 */
export function useRigProperties(): AvailableProperty[] {
  const { data: fixtures } = useVisibleFixtureListQuery()

  return useMemo(() => {
    const byName = new Map<string, AvailableProperty>()
    for (const fixture of fixtures ?? []) {
      for (const property of fixture.properties ?? []) {
        for (const available of expand(property)) {
          if (!byName.has(available.name)) byName.set(available.name, available)
        }
      }
    }
    return [...byName.values()].sort(comparePropertyOrder)
  }, [fixtures])
}
