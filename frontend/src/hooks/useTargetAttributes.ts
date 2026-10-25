import { useMemo } from 'react'
import { useFixtureListQuery, useVisibleFixtureListQuery, type AttributeDescriptor } from '@/store/fixtures'
import { useGroupAttributesQuery } from '@/store/groups'
import type { GroupAttributeDescriptor } from '@/api/groupsApi'

/**
 * Fixture and group property lookup, in the shape the binding surfaces want.
 *
 * This closes `FU-FE-USE-TARGET-PROPERTIES`, whose trigger — a further consumer of the same
 * fetch-and-categorise — fired with the MIDI surface library's per-target property chips. The
 * follow-up asked for one hook. A second export, `categoriseAttributes`, bucketed descriptors for
 * the surfaces that *rendered* group properties; its last consumer, `GroupCard`'s group
 * visualisers, became the fixture sheet's rows (fixture-fx-sheets plan session 4), which group by
 * `lib/attributeFamily.ts`'s families instead, so it went.
 *
 * - [useTargetAttributes] is the flat, uniform list: a name, a label, and whether the property can
 *   be driven from a fader or encoder at all. The surfaces that *bind* properties never touch a
 *   channel — they mint a `fixtureAttribute` / `groupAttribute` / `selectionAttribute` target and let
 *   the desk resolve it — so handing them descriptors would be handing them a shape whose
 *   difference between the two kinds they would then have to flatten again.
 *
 * `continuous` mirrors `AttributeChannelResolver`: it writes a **slider** (one channel, scaled into
 * the property's own range) and a **colour** (one of four HSV axes — hue, a fine hue trim,
 * saturation, brightness — moved on the head's current colour; `lib/colourAxis.ts` names them and
 * the library offers a chip per axis), refuses a **setting** by name, and has no arm for a
 * **position** pair. So a position or setting chip on a fader would be a control that silently
 * does nothing, and the library does not offer one.
 *
 * **A colour descriptor expands to the colour plus its bundled emitters.** The fixture descriptor
 * list omits a `bundleWithColour` slider — `white`, `amber`, `uv` — and folds its channel into the
 * colour's `whiteChannel` / `amberChannel` / `uvChannel`; but the desk drives each as a slider by
 * that name (`FixtureAttribute(key, "white")` dispatches today), so [expand] mints one
 * [AvailableAttribute] per present emitter. The name is the category — every fixture type names its
 * bundled emitter that way, and lighting7's `BundledEmitterNamesTest` pins it.
 */

export type TargetAttributeType = AttributeDescriptor['type']

/** One property, in the shape a binding surface needs: what to name, and whether a fader can drive it. */
export interface AvailableAttribute {
  name: string
  displayName: string
  type: TargetAttributeType
  category: string
  /** Writable from a fader or encoder — see the module docblock. */
  continuous: boolean
}

type AnyAttributeDescriptor = AttributeDescriptor | GroupAttributeDescriptor

function isContinuous(type: TargetAttributeType): boolean {
  return type === 'slider' || type === 'colour'
}

function toAvailable(attribute: AnyAttributeDescriptor): AvailableAttribute {
  return {
    name: attribute.name,
    displayName: attribute.displayName,
    type: attribute.type,
    category: attribute.category,
    continuous: isContinuous(attribute.type),
  }
}

/** The bundled emitters, by the colour-descriptor key that says a head has one. */
const EMITTERS = [
  { key: 'whiteChannel', name: 'white' },
  { key: 'amberChannel', name: 'amber' },
  { key: 'uvChannel', name: 'uv' },
] as const

function hasEmitter(attribute: AnyAttributeDescriptor, key: (typeof EMITTERS)[number]['key']): boolean {
  if (attribute.type !== 'colour') return false
  if ('memberColourChannels' in attribute) {
    return attribute.memberColourChannels.some((member) => member[key] != null)
  }
  return attribute[key] != null
}

/** A property as the binding surfaces see it — a colour brings its bundled emitters with it. */
function expand(attribute: AnyAttributeDescriptor): AvailableAttribute[] {
  // A one-shot trigger and a fixture command are listed with the properties but are none: nothing
  // binds, records or sets one, and the desk refuses either by name (`TRIGGER_NOT_STORABLE`,
  // `COMMAND_NOT_STORABLE`). Offering one in a picker would only offer a 400.
  if (attribute.type === 'trigger' || attribute.type === 'command') return []
  const out = [toAvailable(attribute)]
  if (attribute.type !== 'colour') return out
  for (const emitter of EMITTERS) {
    if (!hasEmitter(attribute, emitter.key)) continue
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

function compareAttributeOrder(a: AvailableAttribute, b: AvailableAttribute): number {
  const ra = CATEGORY_RANK.indexOf(a.category)
  const rb = CATEGORY_RANK.indexOf(b.category)
  const na = ra < 0 ? CATEGORY_RANK.length : ra
  const nb = rb < 0 ? CATEGORY_RANK.length : rb
  if (na !== nb) return na - nb
  return a.displayName.localeCompare(b.displayName)
}

/** What a binding surface asks a target for. `{ type, key }` — the shared target shape. */
export interface AttributeTarget {
  type: 'fixture' | 'group'
  key: string
}

export interface TargetAttributes {
  attributes: AvailableAttribute[]
  isLoading: boolean
}

const NO_ATTRIBUTES: AvailableAttribute[] = []

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
export function useTargetAttributes(target: AttributeTarget | null): TargetAttributes {
  const isGroup = target?.type === 'group'
  const { data: fixtures, isLoading: fixturesLoading } = useFixtureListQuery(undefined, {
    skip: target == null || isGroup,
  })
  const { data: groupAttributes, isLoading: groupLoading } = useGroupAttributesQuery(
    target?.key ?? '',
    { skip: !isGroup },
  )

  const attributes = useMemo(() => {
    if (target == null) return NO_ATTRIBUTES
    const descriptors: readonly AnyAttributeDescriptor[] | undefined = isGroup
      ? groupAttributes
      : fixtures?.find((f) => f.key === target.key)?.attributes
    if (descriptors == null) return NO_ATTRIBUTES
    return descriptors.flatMap(expand).sort(compareAttributeOrder)
  }, [target, isGroup, fixtures, groupAttributes])

  return { attributes, isLoading: isGroup ? groupLoading : fixturesLoading }
}

/**
 * Every property name anywhere in the patch, deduplicated.
 *
 * This is the vocabulary a **target-less** binding is chosen from — `SelectionAttribute`, which
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
export function useRigAttributes(): AvailableAttribute[] {
  const { data: fixtures } = useVisibleFixtureListQuery()

  return useMemo(() => {
    const byName = new Map<string, AvailableAttribute>()
    for (const fixture of fixtures ?? []) {
      for (const attribute of fixture.attributes ?? []) {
        for (const available of expand(attribute)) {
          if (!byName.has(available.name)) byName.set(available.name, available)
        }
      }
    }
    return [...byName.values()].sort(compareAttributeOrder)
  }, [fixtures])
}
