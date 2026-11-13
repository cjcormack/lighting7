import { useCloudStateQuery } from "@/store/cloud"

/**
 * Whether this desk is adopted by Lighting7 Cloud, and so can make and list cloud repos; null while
 * unknown. [remote] is whether this window reaches the desk over remote access.
 */
export function useCloudAdopted(): { adopted: boolean | null; remote: boolean } {
  const { data, isError } = useCloudStateQuery()
  if (isError) return { adopted: false, remote: false }
  if (data == null) return { adopted: null, remote: false }
  return { adopted: data.configured && data.enabled && data.adopted != null, remote: data.remote === true }
}
