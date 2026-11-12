import type { SyncConfig } from '../store/cloudSync'

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

/**
 * How the desk names a project's repo to a person: a cloud repo by its name, never its URL;
 * any other by its shortened URL. Null with no repo attached.
 */
export function repoLabel(config: Pick<SyncConfig, 'repoUrl' | 'repoName' | 'cloud'>): string | null {
  if (config.cloud === true) return config.repoName ?? 'Cloud repo'
  return formatRepoUrl(config.repoUrl)
}
