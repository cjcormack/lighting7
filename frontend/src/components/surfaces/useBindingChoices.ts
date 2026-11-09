import { useCallback, useMemo } from "react"
import { useVisibleFixtureListQuery } from "@/store/fixtures"
import { useGroupListQuery } from "@/store/groups"
import { availableAttributesOf, type AvailableAttribute } from "@/hooks/useTargetAttributes"
import type { SurfaceSubject } from "@/store/surfaces"
import { useRecordOptions } from "./recordOptions"
import type { SubjectChoices } from "./BindingForm"

const NONE: readonly AvailableAttribute[] = []

/**
 * What the binding form offers: the selection, the visible groups and fixtures, the records by kind,
 * and the attributes a subject's heads have — a fixture's own, a group's members', and the whole
 * visible rig's for the selection or an encoder page.
 */
export function useBindingChoices(projectId: number): {
  subjects: SubjectChoices
  attributesFor: (on: SurfaceSubject | null) => readonly AvailableAttribute[]
} {
  const { data: fixtures } = useVisibleFixtureListQuery()
  const { data: groups } = useGroupListQuery()
  const records = useRecordOptions(projectId)

  const subjects = useMemo<SubjectChoices>(
    () => ({
      groups: (groups ?? []).map((group) => ({ key: group.name, label: group.name })),
      fixtures: (fixtures ?? []).flatMap((fixture) => [
        { key: fixture.key, label: fixture.name },
        ...(fixture.elements ?? []).map((element) => ({ key: element.key, label: `${fixture.name} · ${element.displayName}` })),
      ]),
      records,
    }),
    [groups, fixtures, records],
  )

  const byFixture = useMemo(
    () => new Map((fixtures ?? []).map((fixture) => [fixture.key, availableAttributesOf(fixture.attributes)])),
    [fixtures],
  )
  const rig = useMemo(() => {
    const seen = new Map<string, AvailableAttribute>()
    for (const list of byFixture.values()) for (const attribute of list) if (!seen.has(attribute.name)) seen.set(attribute.name, attribute)
    return [...seen.values()]
  }, [byFixture])
  const memberKeys = useMemo(() => new Map((groups ?? []).map((group) => [group.name, group.memberKeys ?? []])), [groups])

  const attributesFor = useCallback(
    (on: SurfaceSubject | null): readonly AvailableAttribute[] => {
      if (on?.type === "fixture") return byFixture.get(on.key) ?? byFixture.get(on.key.split(".")[0]) ?? NONE
      if (on?.type === "group") {
        const seen = new Map<string, AvailableAttribute>()
        for (const key of memberKeys.get(on.key) ?? []) {
          for (const attribute of byFixture.get(key) ?? NONE) if (!seen.has(attribute.name)) seen.set(attribute.name, attribute)
        }
        return seen.size > 0 ? [...seen.values()] : rig
      }
      return rig
    },
    [byFixture, memberKeys, rig],
  )

  return { subjects, attributesFor }
}
