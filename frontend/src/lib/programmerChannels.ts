import { parseProgrammerEntryValue, type ProgrammerParsedValue } from './programmerValue'
import type { ChannelRef, Fixture, PropertyDescriptor } from '../store/fixtures'

/**
 * Resolve the programmer's *property* entries down to the DMX channels they would drive, so a
 * stage view can render "what is the programmer holding" instead of "what is on the wire".
 *
 * This exists because `ProgrammerState.channels` does **not** answer that question. Despite the
 * name it carries the backend's channel *sideband* only — raw `updateChannel` writes on channels
 * with no backing property, raw pan/tilt axis writes, and unpark hand-downs (see `ProgrammerStore`'s
 * "Channel sideband" section in lighting7). A dimmer or colour written through `programmer.set`
 * lands in a property entry and never appears there; worse, `FxEngine.absorbSidebandUnder` deletes
 * sideband slots underneath a deliberate property write. So the sideband supplements this
 * resolution and is never a substitute for it.
 *
 * The value→channel mapping mirrors `PropertyChannelWriter` in lighting7. Keep the two in step —
 * this is the client-side twin and the backend remains the reference.
 */

/** One resolved channel write, keyed the way `channelsApi` keys its value map. */
export interface ResolvedChannel {
  key: string
  value: number
}

/** Lookup key for a channel, matching `channelsApi`'s `"universe:channelNo"` map keys. */
export function channelMapKey(ref: ChannelRef): string {
  return `${ref.universe}:${ref.channelNo}`
}

/** The minimum a programmer entry must expose to be resolvable. */
export interface ResolvableEntry {
  targetKey: string
  propertyName: string
  value: string
  resolvedValue?: string
}

/** One sideband channel as it arrives on `programmer.state`. */
export interface SidebandChannel {
  universe: number
  channel: number
  value: number
}

/**
 * Every property descriptor addressable by a programmer entry, keyed by the entry's `targetKey`.
 *
 * Both fixture keys and **element** keys are present: a multi-head fixture's entries are keyed by
 * element key, not fixture key, so a map built from `Fixture.properties` alone would silently drop
 * every per-head write on a moving-head bar.
 */
export type DescriptorsByTarget = ReadonlyMap<string, readonly PropertyDescriptor[]>

/** Build the lookup [DescriptorsByTarget] wants from the fixture list a stage view already has. */
export function descriptorsByTarget(fixtures: Iterable<Fixture>): DescriptorsByTarget {
  const out = new Map<string, readonly PropertyDescriptor[]>()
  for (const fixture of fixtures) {
    out.set(fixture.key, fixture.properties)
    for (const element of fixture.elements ?? []) {
      out.set(element.key, element.properties)
    }
  }
  return out
}

/**
 * The channels one programmer entry drives, or an empty array when it drives none.
 *
 * Empty covers three cases, all correct as "contributes nothing to the picture": the target has no
 * such property, the value doesn't parse (an unresolved palette reference, or a value form this
 * build doesn't know), or the parsed shape doesn't match the descriptor's shape. The last mirrors
 * `applyStagedValue`'s fall-through in the programmer sheet — a mismatch means the programmer holds
 * something this property cannot render, and guessing would be worse than showing nothing.
 */
export function resolveEntryChannels(
  descriptors: readonly PropertyDescriptor[] | undefined,
  entry: ResolvableEntry,
): ResolvedChannel[] {
  if (!descriptors) return []
  const parsed = parseProgrammerEntryValue(entry)
  if (!parsed) return []
  const descriptor = descriptors.find((d) => d.name === entry.propertyName)
  if (!descriptor) return []
  return channelsFor(descriptor, parsed)
}

function channelsFor(
  descriptor: PropertyDescriptor,
  parsed: ProgrammerParsedValue,
): ResolvedChannel[] {
  switch (descriptor.type) {
    // Slider and setting are both one channel carrying one byte, and both arrive as
    // `kind: 'level'` — the wire form is identical and only the descriptor says which is which.
    case 'slider':
    case 'setting':
      return parsed.kind === 'level'
        ? [{ key: channelMapKey(descriptor.channel), value: parsed.value }]
        : []
    case 'colour': {
      if (parsed.kind !== 'colour') return []
      const out: ResolvedChannel[] = [
        { key: channelMapKey(descriptor.redChannel), value: parsed.r },
        { key: channelMapKey(descriptor.greenChannel), value: parsed.g },
        { key: channelMapKey(descriptor.blueChannel), value: parsed.b },
      ]
      // Extended emitters only where the fixture actually has one. The descriptor's optional
      // channels are the client-side equivalent of the backend gating these writes on the
      // WithWhite / WithAmber / WithUv traits, so a trait-less fixture drops the component
      // rather than painting it onto some unrelated channel.
      if (descriptor.whiteChannel) {
        out.push({ key: channelMapKey(descriptor.whiteChannel), value: parsed.w })
      }
      if (descriptor.amberChannel) {
        out.push({ key: channelMapKey(descriptor.amberChannel), value: parsed.a })
      }
      if (descriptor.uvChannel) {
        out.push({ key: channelMapKey(descriptor.uvChannel), value: parsed.uv })
      }
      return out
    }
    // Coarse axes only. A position assignment writes no fine channels on the backend either
    // (`PropertyChannelWriter.resolvePosition`), so emitting them here would make the preview
    // disagree with what the same value produces on the wire.
    case 'position':
      return parsed.kind === 'position'
        ? [
            { key: channelMapKey(descriptor.panChannel), value: parsed.pan },
            { key: channelMapKey(descriptor.tiltChannel), value: parsed.tilt },
          ]
        : []
  }
}

/**
 * The programmer's whole contribution as a channel map, readable like a DMX snapshot.
 *
 * Sideband channels are applied **last**. The backend arbitrates a property entry against a
 * sideband slot on the same channel by write sequence (`ProgrammerStore.Slot.seq`), which never
 * reaches the client, so this ordering is an approximation. It is a safe one: a deliberate operator
 * property write absorbs the sideband beneath it, so a slot surviving under one is not a state the
 * backend normally holds — and where the two can differ, favouring the sideband keeps raw writes
 * visible, which is the case the sideband exists to serve.
 */
export function buildProgrammerChannelMap(
  entries: Iterable<ResolvableEntry>,
  sideband: Iterable<SidebandChannel>,
  descriptors: DescriptorsByTarget,
): Map<string, number> {
  const out = new Map<string, number>()
  for (const entry of entries) {
    for (const resolved of resolveEntryChannels(descriptors.get(entry.targetKey), entry)) {
      out.set(resolved.key, resolved.value)
    }
  }
  for (const channel of sideband) {
    out.set(`${channel.universe}:${channel.channel}`, channel.value)
  }
  return out
}
