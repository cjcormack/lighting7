import { useCloudStateQuery } from "@/store/cloud"

/** Whether this desk is adopted by Lighting7 Cloud, and so can make and list cloud repos; null while unknown. */
export function useCloudAdopted(): { adopted: boolean | null } {
  const { data, isError } = useCloudStateQuery()
  if (isError) return { adopted: false }
  if (data == null) return { adopted: null }
  return { adopted: data.configured && data.enabled && data.adopted != null }
}
