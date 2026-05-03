package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.sync.auth.MissingCredentialsException
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthReauthRequiredException
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.exists

/**
 * Clone a remote repository as a brand-new local project. The route handler at
 * `/api/rest/cloud-sync/import` is a thin wrapper around [import] — keeping the
 * orchestration logic out of the route lets tests build their own [AuthResolver]
 * (typically with [uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore]) and
 * drive the full clone → validate → persist pipeline without HTTP plumbing.
 *
 * Errors are surfaced as typed exceptions:
 *  * [SyncException] for credential resolution issues — mapped to 4xx by the route.
 *  * [GitAuthException] when GitHub rejects the clone over HTTPS.
 *  * [ImportError] for archive validation issues (raised from [ProjectImporter]).
 */
class CloudSyncImporter(
    private val state: State,
    private val authResolver: AuthResolver,
) {

    /**
     * Performs the import end-to-end. Caller is responsible for emitting any WS event
     * after a successful return — that's a route-layer concern.
     *
     * @param repoUrl Canonical repo clone URL (e.g. `https://github.com/owner/repo.git`).
     * @param branch Defaults to [JGitClient.DEFAULT_BRANCH] when null/blank.
     * @param projectName Override for the imported `project.json`'s name; null/blank uses the embedded name.
     */
    suspend fun import(
        repoUrl: String,
        branch: String?,
        projectName: String?,
    ): ProjectImporter.Result {
        val resolvedBranch = branch?.trim()?.takeIf { it.isNotBlank() } ?: JGitClient.DEFAULT_BRANCH
        val nameOverride = projectName?.trim()?.takeIf { it.isNotBlank() }

        val credentials = try {
            authResolver.resolveFor(repoUrl)
        } catch (e: MissingCredentialsException) {
            throw SyncException(
                SyncErrorCode.MISSING_CREDENTIALS,
                e.message ?: "No GitHub credentials configured.",
            )
        } catch (e: OAuthReauthRequiredException) {
            throw SyncException(
                SyncErrorCode.OAUTH_REAUTH_REQUIRED,
                e.message ?: "GitHub OAuth re-authentication required.",
            )
        }

        Files.createDirectories(state.syncWorkingTreeRoot)
        val pendingDir = state.syncWorkingTreeRoot.resolve("_import-${System.nanoTime()}")
        val cloneTarget = pendingDir.resolve("repo")
        var movedToFinal = false

        try {
            val headSha = withContext(Dispatchers.IO) {
                JGitClient.clone(repoUrl, cloneTarget, resolvedBranch, credentials).use { repo ->
                    JGitClient.head(repo)?.sha
                        ?: throw ImportError.invalidArchive("Cloned remote has no commits on $resolvedBranch")
                }
            }

            val result = withContext(Dispatchers.IO) {
                ProjectImporter(state).import(cloneTarget, nameOverride)
            }

            val targetUuid = UUID.fromString(result.projectUuid)
            val finalRepoPath = SyncWorkingTree(state).pathFor(targetUuid)
            Files.createDirectories(finalRepoPath.parent)
            withContext(Dispatchers.IO) {
                try {
                    // ProjectImporter.import already 409s on UUID collision, so the only
                    // way `finalRepoPath` exists is a leftover from a previous failed
                    // import. `Files.move` (no REPLACE_EXISTING) atomically rejects that
                    // case for us — translate to the same conflict the importer raises.
                    Files.move(cloneTarget, finalRepoPath)
                } catch (e: java.nio.file.FileAlreadyExistsException) {
                    throw ImportError.conflict(
                        "Working tree already exists at $finalRepoPath for project UUID $targetUuid.",
                    )
                }
                movedToFinal = true
                Files.deleteIfExists(pendingDir)
                SyncWorkingTree(state).ensureInitialised(finalRepoPath).close()

                transaction(state.database) {
                    val project = DaoProject.findById(result.projectId)
                        ?: error("Project ${result.projectId} vanished mid-import")
                    DaoSyncConfig.new {
                        this.project = project
                        this.repoUrl = repoUrl
                        this.branch = resolvedBranch
                        this.enabled = true
                        this.lastSyncedSha = headSha
                        this.lastSyncedAtMs = System.currentTimeMillis()
                    }
                }
            }
            state.autoSyncScheduler.reschedule(result.projectId)

            return result
        } finally {
            if (!movedToFinal && pendingDir.exists()) {
                runCatching { withContext(Dispatchers.IO) { pendingDir.toFile().deleteRecursively() } }
                    .onFailure { logger.warn("Failed to clean pending clone dir $pendingDir: ${it.message}") }
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CloudSyncImporter::class.java)
    }
}
