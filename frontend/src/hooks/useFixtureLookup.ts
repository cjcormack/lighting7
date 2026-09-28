import { useMemo } from 'react'
import {
  useFixtureListQuery,
  useFixtureTypeListQuery,
  type Fixture,
  type FixtureTypeInfo,
} from '../store/fixtures'

export interface FixtureLookup {
  fixtures: Fixture[] | undefined
  fixtureTypes: FixtureTypeInfo[] | undefined
  fixtureByKey: Map<string, Fixture>
  typeByKey: Map<string, FixtureTypeInfo>
}

export function useFixtureLookup(): FixtureLookup {
  const { data: fixtures } = useFixtureListQuery()
  const { data: fixtureTypes } = useFixtureTypeListQuery()

  const fixtureByKey = useMemo(() => {
    const map = new Map<string, Fixture>()
    fixtures?.forEach((f) => map.set(f.key, f))
    return map
  }, [fixtures])

  const typeByKey = useMemo(() => {
    const map = new Map<string, FixtureTypeInfo>()
    fixtureTypes?.forEach((t) => map.set(t.typeKey, t))
    return map
  }, [fixtureTypes])

  return { fixtures, fixtureTypes, fixtureByKey, typeByKey }
}
