import type { AttributeDescriptor, ChannelRef } from "../store/fixtures"
import { useGetChannelParkStateQuery } from "../store/park"

/**
 * Extract all ChannelRef values from a property descriptor.
 */
export function getAttributeChannels(attribute: AttributeDescriptor): ChannelRef[] {
  switch (attribute.type) {
    case "slider":
      return [attribute.channel]
    case "colour": {
      const channels = [attribute.redChannel, attribute.greenChannel, attribute.blueChannel]
      if (attribute.whiteChannel) channels.push(attribute.whiteChannel)
      if (attribute.amberChannel) channels.push(attribute.amberChannel)
      if (attribute.uvChannel) channels.push(attribute.uvChannel)
      return channels
    }
    case "whiteMix":
      return [attribute.warmChannel, attribute.coldChannel]
    case "position":
      return [attribute.panChannel, attribute.tiltChannel]
    case "setting":
      return [attribute.channel]
    // A one-shot trigger and its arm can be parked — below the fire level, as a lock-out.
    case "trigger":
      return [attribute.channel, attribute.armChannel]
    // A fixture command's channel likewise — at idle, as a lock-out — and its preconditions' channels
    // are the properties' own.
    case "command":
      return [attribute.channel]
  }
}

/**
 * Check if any channels of a property are parked.
 * Returns a simple boolean — suitable for showing a lock indicator.
 *
 * Note: This calls one RTK Query hook per channel. For properties with
 * many channels (e.g. RGBWAUV colour = 6), this is still fine since
 * each is a cheap in-memory lookup with WebSocket-driven updates.
 */
export function useAttributeParkStatus(attribute: AttributeDescriptor): {
  isAnyParked: boolean
} {
  const channels = getAttributeChannels(attribute)

  // We need to call hooks unconditionally, so we query all possible channels
  // and check the results. Since getAttributeChannels returns 1-6 channels max,
  // we pad to a fixed size and skip extras.
  const ch0 = channels[0]
  const ch1 = channels[1]
  const ch2 = channels[2]
  const ch3 = channels[3]
  const ch4 = channels[4]
  const ch5 = channels[5]

  const { data: p0 } = useGetChannelParkStateQuery(
    { universe: ch0?.universe ?? 0, channelNo: ch0?.channelNo ?? 0 },
    { skip: !ch0 }
  )
  const { data: p1 } = useGetChannelParkStateQuery(
    { universe: ch1?.universe ?? 0, channelNo: ch1?.channelNo ?? 0 },
    { skip: !ch1 }
  )
  const { data: p2 } = useGetChannelParkStateQuery(
    { universe: ch2?.universe ?? 0, channelNo: ch2?.channelNo ?? 0 },
    { skip: !ch2 }
  )
  const { data: p3 } = useGetChannelParkStateQuery(
    { universe: ch3?.universe ?? 0, channelNo: ch3?.channelNo ?? 0 },
    { skip: !ch3 }
  )
  const { data: p4 } = useGetChannelParkStateQuery(
    { universe: ch4?.universe ?? 0, channelNo: ch4?.channelNo ?? 0 },
    { skip: !ch4 }
  )
  const { data: p5 } = useGetChannelParkStateQuery(
    { universe: ch5?.universe ?? 0, channelNo: ch5?.channelNo ?? 0 },
    { skip: !ch5 }
  )

  const isAnyParked =
    (ch0 && p0 !== undefined) ||
    (ch1 && p1 !== undefined) ||
    (ch2 && p2 !== undefined) ||
    (ch3 && p3 !== undefined) ||
    (ch4 && p4 !== undefined) ||
    (ch5 && p5 !== undefined) ||
    false

  return { isAnyParked }
}
