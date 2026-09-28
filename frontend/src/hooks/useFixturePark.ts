import { useMemo, useCallback } from "react"
import type { Fixture } from "../store/fixtures"
import {
  useGetParkStateListQuery,
  useParkChannelMutation,
  useUnparkChannelMutation,
} from "../store/park"
import { lightingApi } from "../api/lightingApi"

export function useFixturePark(fixture: Fixture | null | undefined) {
  const { data: parkStateList } = useGetParkStateListQuery()
  const [runParkChannel] = useParkChannelMutation()
  const [runUnparkChannel] = useUnparkChannelMutation()

  const parkedCount = useMemo(() => {
    if (!fixture || !parkStateList) return 0
    return fixture.channels.filter((ch) =>
      parkStateList.some((p) => p.universe === fixture.universe && p.channel === ch.channelNo)
    ).length
  }, [fixture, parkStateList])

  const totalChannels = fixture?.channels.length ?? 0
  const isFullyParked = totalChannels > 0 && parkedCount === totalChannels
  const isPartiallyParked = parkedCount > 0 && !isFullyParked

  const parkFixture = useCallback(() => {
    if (!fixture) return
    fixture.channels.forEach((ch) => {
      const value = lightingApi.channels.get(fixture.universe, ch.channelNo)
      runParkChannel({ universe: fixture.universe, channelNo: ch.channelNo, value })
    })
  }, [fixture, runParkChannel])

  const unparkFixture = useCallback(() => {
    if (!fixture) return
    fixture.channels.forEach((ch) => {
      runUnparkChannel({ universe: fixture.universe, channelNo: ch.channelNo })
    })
  }, [fixture, runUnparkChannel])

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
