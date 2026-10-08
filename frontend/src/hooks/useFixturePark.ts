import { useMemo, useCallback } from "react"
import type { ChannelRef, Fixture } from "../store/fixtures"
import {
  useGetParkStateListQuery,
  useParkChannelMutation,
  useUnparkChannelMutation,
} from "../store/park"
import { lightingApi } from "../api/lightingApi"

export function useFixturePark(fixture: Fixture | null | undefined) {
  return useChannelsPark(useMemo(() => (fixture ? fixtureChannelRefs(fixture) : []), [fixture]))
}

/** Every channel a fixture is patched on. */
export function fixtureChannelRefs(fixture: Fixture): ChannelRef[] {
  return fixture.channels.map((ch) => ({ universe: fixture.universe, channelNo: ch.channelNo }))
}

/**
 * Park over any set of channels — a fixture's, or a group sheet's members' (fixture-fx-sheets plan
 * D1: the group sheet is the fixture sheet's body, header included), where a member that is one
 * head of a bar brings that head's channels and not the bar's.
 */
export function useChannelsPark(channels: readonly ChannelRef[]) {
  const { data: parkStateList } = useGetParkStateListQuery()
  const [runParkChannel] = useParkChannelMutation()
  const [runUnparkChannel] = useUnparkChannelMutation()

  const parkedCount = useMemo(() => {
    if (!parkStateList) return 0
    return channels.filter((ch) => parkStateList.some((p) => p.universe === ch.universe && p.channel === ch.channelNo)).length
  }, [channels, parkStateList])

  const totalChannels = channels.length
  const isFullyParked = totalChannels > 0 && parkedCount === totalChannels
  const isPartiallyParked = parkedCount > 0 && !isFullyParked

  const parkFixture = useCallback(() => {
    channels.forEach((ch) => {
      const value = lightingApi.channels.get(ch.universe, ch.channelNo)
      runParkChannel({ universe: ch.universe, channelNo: ch.channelNo, value })
    })
  }, [channels, runParkChannel])

  const unparkFixture = useCallback(() => {
    channels.forEach((ch) => {
      runUnparkChannel({ universe: ch.universe, channelNo: ch.channelNo })
    })
  }, [channels, runUnparkChannel])

  return {
    parkedCount,
    totalChannels,
    isFullyParked,
    isPartiallyParked,
    isAnyParked: parkedCount > 0,
    parkFixture,
    unparkFixture,
  }
}
