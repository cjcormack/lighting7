import { useMemo } from "react"
import { useParams } from "react-router"
import { useSurfaceBindingsQuery } from "@/store/surfaces"
import { BoundControlBadge } from "./BoundControlBadge"
import { effectiveTarget } from "./targetUtils"

/**
 * Renders a small chip row listing every surface-binding whose target is a
 * `fixtureProperty` on this fixture. Silent when nothing is bound.
 */
export function FixtureBoundControlsRow({ fixtureKey }: { fixtureKey: string }) {
  const { projectId } = useParams()
  const projectIdNum = Number(projectId)
  const { data: bindings } = useSurfaceBindingsQuery(projectIdNum, { skip: !projectIdNum })

  const byProperty = useMemo(() => {
    const map = new Map<string, typeof bindings>()
    for (const b of bindings ?? []) {
      const eff = effectiveTarget(b.target)
      if (eff.type !== "fixtureProperty" || eff.fixtureKey !== fixtureKey) continue
      const list = map.get(eff.propertyName) ?? []
      list.push(b)
      map.set(eff.propertyName, list)
    }
    return map
  }, [bindings, fixtureKey])

  if (byProperty.size === 0) return null

  return (
    <div className="flex flex-wrap items-center gap-1.5 -mt-1">
      {Array.from(byProperty.entries()).map(([propertyName, list]) => (
        <div key={propertyName} className="flex items-center gap-1">
          <span className="text-[10px] text-muted-foreground font-mono">{propertyName}:</span>
          <BoundControlBadge
            match={{ type: "fixtureProperty", fixtureKey, propertyName }}
            preFiltered={list}
          />
        </div>
      ))}
    </div>
  )
}
