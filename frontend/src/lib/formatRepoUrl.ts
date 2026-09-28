/**
 * Shorten a repository URL for display: GitHub clone/SSH URLs collapse to `owner/repo`,
 * anything else is shown as-is. Returns null for a project with no repo attached.
 */
export function formatRepoUrl(url: string | null): string | null {
  if (!url) return null
  // GitHub URLs are the dominant case; show just owner/repo for brevity.
  const m = url.match(/github\.com[/:]([^/]+\/[^/.]+)/i)
  return m ? m[1] : url
}
