import { useMemo } from "react"
import { useParams } from "react-router"
import { useSurfaceBindingsQuery } from "@/store/surfaces"
import { BoundControlBadge } from "./BoundControlBadge"
import { effectiveTarget } from "./targetUtils"
import { STRIP_FADER_ATTRIBUTE } from "@/lib/surfaceResolve"

/**
 * Renders a small chip row listing every surface-binding whose target is a
 * `fixtureAttribute` on this fixture. Silent when nothing is bound.
 */
export function FixtureBoundControlsRow({ fixtureKey }: { fixtureKey: string }) {
  const { projectId } = useParams()
  const projectIdNum = Number(projectId)
  const { data: bindings } = useSurfaceBindingsQuery(projectIdNum, { skip: !projectIdNum })

  const byAttribute = useMemo(() => {
    const map = new Map<string, typeof bindings>()
    for (const b of bindings ?? []) {
      const eff = effectiveTarget(b.target)
      if (eff.type !== "fixtureAttribute" || eff.fixtureKey !== fixtureKey) continue
      const list = map.get(eff.attribute) ?? []
      list.push(b)
      map.set(eff.attribute, list)
    }
    return map
  }, [bindings, fixtureKey])

  if (byAttribute.size === 0) return null

  return (
    <div className="flex flex-wrap items-center gap-1.5 -mt-1">
      {Array.from(byAttribute.entries()).map(([attribute, list]) => (
        <div key={attribute} className="flex items-center gap-1">
          <span className="text-[10px] text-muted-foreground font-mono">{attribute}:</span>
          <BoundControlBadge
            match={{ type: "fixtureAttribute", fixtureKey, attribute }}
            preFiltered={list}
          />
        </div>
      ))}
    </div>
  )
}

/**
 * The group sheet's twin: every surface binding whose target is a `groupAttribute` on this group —
 * the badges `GroupCard` drew beside each group visualiser's name until the group sheet replaced
 * them (fixture-fx-sheets plan session 4). Silent when nothing is bound.
 */
export function GroupBoundControlsRow({ groupName }: { groupName: string }) {
  const { projectId } = useParams()
  const projectIdNum = Number(projectId)
  const { data: bindings } = useSurfaceBindingsQuery(projectIdNum, { skip: !projectIdNum })

  const byAttribute = useMemo(() => {
    const map = new Map<string, typeof bindings>()
    for (const b of bindings ?? []) {
      const eff = effectiveTarget(b.target)
      // A strip on the group drives its dimmer, as `matchesBindingTarget` reads it for the badge.
      const attribute =
        eff.type === "groupAttribute" && eff.groupName === groupName
          ? eff.attribute
          : eff.type === "strip" && eff.target.type === "group" && eff.target.key === groupName
            ? STRIP_FADER_ATTRIBUTE
            : null
      if (attribute == null) continue
      const list = map.get(attribute) ?? []
      list.push(b)
      map.set(attribute, list)
    }
    return map
  }, [bindings, groupName])

  if (byAttribute.size === 0) return null

  return (
    <div className="flex flex-wrap items-center gap-1.5 -mt-1">
      {Array.from(byAttribute.entries()).map(([attribute, list]) => (
        <div key={attribute} className="flex items-center gap-1">
          <span className="text-[10px] text-muted-foreground font-mono">{attribute}:</span>
          <BoundControlBadge match={{ type: "groupAttribute", groupName, attribute }} preFiltered={list} />
        </div>
      ))}
    </div>
  )
}
