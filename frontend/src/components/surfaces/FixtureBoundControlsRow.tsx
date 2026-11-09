import { useMemo } from "react"
import { useParams } from "react-router"
import { useSurfaceBindingsQuery, type ControlSurfaceBinding } from "@/store/surfaces"
import { BoundControlBadge } from "./BoundControlBadge"

/**
 * The attribute a binding drives on [subject] — a level's or a flash's, or a strip's fader, which
 * drives the dimmer — or null when it drives none there.
 */
function attributeOn(binding: ControlSurfaceBinding, type: "fixture" | "group", key: string): string | null {
  const on = binding.binding.on
  if (on?.type !== type || on.key !== key) return null
  const does = binding.binding.does
  if (does.type === "level" || does.type === "flash") return does.attribute
  if (does.type === "strip") return "dimmer"
  return null
}

function useBoundByAttribute(type: "fixture" | "group", key: string) {
  const { projectId } = useParams()
  const projectIdNum = Number(projectId)
  const { data: bindings } = useSurfaceBindingsQuery(projectIdNum, { skip: !projectIdNum })
  return useMemo(() => {
    const map = new Map<string, ControlSurfaceBinding[]>()
    for (const b of bindings ?? []) {
      const attribute = attributeOn(b, type, key)
      if (attribute == null) continue
      map.set(attribute, [...(map.get(attribute) ?? []), b])
    }
    return map
  }, [bindings, type, key])
}

function BoundRows({ byAttribute }: { byAttribute: Map<string, ControlSurfaceBinding[]> }) {
  if (byAttribute.size === 0) return null
  return (
    <div className="flex flex-wrap items-center gap-1.5 -mt-1">
      {Array.from(byAttribute.entries()).map(([attribute, list]) => (
        <div key={attribute} className="flex items-center gap-1">
          <span className="text-[10px] text-muted-foreground font-mono">{attribute}:</span>
          <BoundControlBadge bindings={list} />
        </div>
      ))}
    </div>
  )
}

/** Every surface binding that drives an attribute of this fixture. Silent when nothing is bound. */
export function FixtureBoundControlsRow({ fixtureKey }: { fixtureKey: string }) {
  return <BoundRows byAttribute={useBoundByAttribute("fixture", fixtureKey)} />
}

/** The group sheet's twin, a strip on the group counting as its dimmer. */
export function GroupBoundControlsRow({ groupName }: { groupName: string }) {
  return <BoundRows byAttribute={useBoundByAttribute("group", groupName)} />
}
