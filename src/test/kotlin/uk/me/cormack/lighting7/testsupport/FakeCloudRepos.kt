package uk.me.cormack.lighting7.testsupport

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.PacketLineOut
import org.eclipse.jgit.transport.ReceiveCommand
import org.eclipse.jgit.transport.ReceivePack
import org.eclipse.jgit.transport.RefAdvertiser
import org.eclipse.jgit.transport.UploadPack
import org.eclipse.jgit.treewalk.TreeWalk
import uk.me.cormack.lighting7.cloud.protocol.ApiError
import uk.me.cormack.lighting7.cloud.protocol.CloudJson
import uk.me.cormack.lighting7.cloud.protocol.CloudPaths
import uk.me.cormack.lighting7.cloud.protocol.CreateRepoRequest
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.GitTokenCheck
import uk.me.cormack.lighting7.cloud.protocol.InstallGitTokens
import uk.me.cormack.lighting7.cloud.protocol.InstallKeys
import uk.me.cormack.lighting7.cloud.protocol.PushRefusals
import uk.me.cormack.lighting7.cloud.protocol.RepoApiPayload
import uk.me.cormack.lighting7.cloud.protocol.RepoDto
import uk.me.cormack.lighting7.cloud.protocol.RepoExistsError
import uk.me.cormack.lighting7.cloud.protocol.RepoList
import uk.me.cormack.lighting7.cloud.protocol.SignatureHeaders
import uk.me.cormack.lighting7.cloud.protocol.Timestamps
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/**
 * [FakeCloud]'s repos (cloud-p2 plan §3): the repo API under [CloudPaths.REPOS], signed as the protocol
 * says or read with a developer token, and git's smart HTTP over in-memory bare repos, JGit's own
 * `UploadPack` and `ReceivePack` in their stateless form, as the cloud serves them. Its push rules are
 * the protocol's — `main` fast-forward only and never deleted, no ref but `main` and tags, a tag never
 * moved, `formatVersion.json` on `main` — each refused with its `CODE: words` reason.
 */
class FakeCloudRepos(private val cloud: FakeCloud) {
    class Repo(val dto: RepoDto, val git: InMemoryRepository)

    /** Repos by id, deleted ones removed. */
    val repos = ConcurrentHashMap<String, Repo>()

    /** Developer tokens the cloud takes, and whether each may push. */
    val devTokens = ConcurrentHashMap<String, Boolean>()

    /** The org has no cloud sync: every repo and git route answers 403 `SYNC_OFF`. */
    @Volatile var syncOff: Boolean = false

    /** Refuse every repo API call with this status and error code. */
    @Volatile var refuseApi: Pair<HttpStatusCode, String>? = null

    /** Refuse every pushed ref with this push refusal code, whatever it is. */
    @Volatile var refusePushes: String? = null

    /** Refuse the next push's refs with this code, then take pushes again. */
    @Volatile var refuseNextPush: String? = null

    /** How many receive-pack exchanges ran. */
    val pushes = AtomicInteger()

    /** Each git request as `METHOD path`, and the user it authenticated as. */
    val gitRequests = CopyOnWriteArrayList<Pair<String, String?>>()

    private val ids = AtomicInteger()

    fun urlOf(repoId: String): String = "${cloud.baseUrl}/git/${cloud.org.id}/$repoId.git"

    /** Make a repo as a create would. */
    fun create(projectUuid: String, kind: String, name: String): RepoDto {
        val id = "repo${ids.incrementAndGet()}"
        val dto = RepoDto(id, urlOf(id), projectUuid, kind, name, sizeBytes = 0)
        repos[id] = Repo(dto, InMemoryRepository(DfsRepositoryDescription(id)))
        return dto
    }

    /** Delete a repo in the portal: every desk's next request for it is 404. */
    fun delete(repoId: String) {
        repos.remove(repoId)
    }

    /** `main`'s commit in [repoId], or null before the first push. */
    fun head(repoId: String): String? = repos[repoId]?.git?.exactRef("refs/heads/main")?.objectId?.name

    /** [repoId]'s tags, name → the commit each names. */
    fun tags(repoId: String): Map<String, String> {
        val git = repos[repoId]?.git ?: return emptyMap()
        return git.refDatabase.getRefsByPrefix(Constants.R_TAGS).associate { ref ->
            ref.name.removePrefix(Constants.R_TAGS) to (git.refDatabase.peel(ref).peeledObjectId ?: ref.objectId).name
        }
    }

    fun routes(route: Route) = with(route) {
        post(CloudPaths.REPOS) {
            val body = call.receive<ByteArray>()
            val installId = repoCaller(call, body, mayDevToken = false) ?: return@post
            val request = runCatching { CloudJson.decodeFromString(CreateRepoRequest.serializer(), body.decodeToString()) }.getOrNull()
                ?: return@post call.apiError(HttpStatusCode.BadRequest, ErrorCode.MALFORMED)
            check(installId.isNotEmpty())
            val existing = repos.values.firstOrNull { it.dto.projectUuid == request.projectUuid }
            if (existing != null) {
                return@post call.respondText(
                    CloudJson.encodeToString(RepoExistsError.serializer(), RepoExistsError(ErrorCode.REPO_EXISTS, "fake cloud: the project has a repo", existing.dto)),
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict,
                )
            }
            val made = create(request.projectUuid, request.kind, request.name)
            call.respondText(CloudJson.encodeToString(RepoDto.serializer(), made), ContentType.Application.Json, HttpStatusCode.Created)
        }
        get(CloudPaths.REPOS) {
            repoCaller(call, ByteArray(0), mayDevToken = true) ?: return@get
            val list = RepoList(repos.values.map { it.dto.copy(headSha = head(it.dto.repoId)) })
            call.respondText(CloudJson.encodeToString(RepoList.serializer(), list), ContentType.Application.Json)
        }
        get("${CloudPaths.REPOS}/{repoId}") {
            repoCaller(call, ByteArray(0), mayDevToken = true) ?: return@get
            val repo = repos[call.parameters["repoId"].orEmpty()]
                ?: return@get call.apiError(HttpStatusCode.NotFound, ErrorCode.REPO_NOT_FOUND)
            call.respondText(CloudJson.encodeToString(RepoDto.serializer(), repo.dto.copy(headSha = head(repo.dto.repoId))), ContentType.Application.Json)
        }
        route("/git/{orgId}/{repoFile}") {
            get("info/refs") { infoRefs(call) }
            post("git-upload-pack") { rpc(call, "git-upload-pack") }
            post("git-receive-pack") { rpc(call, "git-receive-pack") }
        }
    }

    /** The install that signed this repo API call (or `""` for a developer token), or null once refused. */
    private suspend fun repoCaller(call: ApplicationCall, body: ByteArray, mayDevToken: Boolean): String? {
        if (syncOff) return null.also { call.apiError(HttpStatusCode.Forbidden, ErrorCode.SYNC_OFF) }
        refuseApi?.let { (status, code) -> return null.also { call.apiError(status, code) } }
        val bearer = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
        if (bearer != null && mayDevToken && devTokens.containsKey(bearer)) return ""
        val installId = call.request.headers[SignatureHeaders.INSTALL]
        val timestamp = call.request.headers[SignatureHeaders.TIMESTAMP]
        val signature = call.request.headers[SignatureHeaders.SIGNATURE]
        val install = installId?.let { cloud.installs[it] }
        if (install == null || timestamp == null || signature == null) {
            return null.also { call.apiError(HttpStatusCode.Unauthorized, ErrorCode.MALFORMED) }
        }
        if (!Timestamps.withinSkew(timestamp, Instant.now())) return null.also { call.apiError(HttpStatusCode.Unauthorized, ErrorCode.CLOCK_SKEW) }
        val payload = RepoApiPayload.of(installId, call.request.httpMethod.value, call.request.path(), timestamp, body)
        if (!InstallKeys.verify(install.key, payload, signature)) return null.also { call.apiError(HttpStatusCode.Unauthorized, ErrorCode.BAD_SIGNATURE) }
        return installId
    }

    private sealed interface GitCaller {
        val user: String

        data class Install(val installId: String) : GitCaller {
            override val user: String get() = InstallGitTokens.BASIC_USER
        }

        data class Developer(val mayPush: Boolean) : GitCaller {
            override val user: String get() = "developer"
        }
    }

    /** The caller, or null once refused: 401 asking for Basic auth, as the cloud's git does. */
    private suspend fun gitCaller(call: ApplicationCall): GitCaller? {
        val basic = call.request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Basic ") }
            ?.let { runCatching { Base64.getDecoder().decode(it.removePrefix("Basic ")).decodeToString() }.getOrNull() }
        val user = basic?.substringBefore(':')
        val password = basic?.substringAfter(':', "")
        val caller = when {
            password == null -> null
            user == InstallGitTokens.BASIC_USER -> {
                val check = InstallGitTokens.verify(password, InstallGitTokens.audience(cloud.baseUrl), Instant.now()) { cloud.installs[it]?.key }
                (check as? GitTokenCheck.Valid)?.let { GitCaller.Install(it.claims.iss) }
            }
            else -> devTokens[password]?.let { GitCaller.Developer(it) }
        }
        if (caller == null) {
            call.response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"Lighting7 Cloud\"")
            call.respondText("", status = HttpStatusCode.Unauthorized)
        }
        return caller
    }

    /** The repo this git path names, or null once answered: 403 for an org without sync, 404 for any repo it lacks. */
    private suspend fun gitRepo(call: ApplicationCall): Repo? {
        if (syncOff) return null.also { call.respondText("SYNC_OFF", status = HttpStatusCode.Forbidden) }
        val repoId = call.parameters["repoFile"].orEmpty().removeSuffix(".git")
        val repo = repos[repoId]?.takeIf { call.parameters["orgId"] == cloud.org.id }
        if (repo == null) call.respondText("", status = HttpStatusCode.NotFound)
        return repo
    }

    private suspend fun infoRefs(call: ApplicationCall) {
        val service = call.request.queryParameters["service"]
        val caller = gitCaller(call) ?: return
        gitRequests += "GET info/refs?service=$service" to caller.user
        val repo = gitRepo(call) ?: return
        if (service != "git-upload-pack" && service != "git-receive-pack") return call.respondText("dumb protocol refused", status = HttpStatusCode.Forbidden)
        if (service == "git-receive-pack" && caller is GitCaller.Developer && !caller.mayPush) {
            return call.respondText(ErrorCode.SYNC_READ_ONLY, status = HttpStatusCode.Forbidden)
        }
        val out = ByteArrayOutputStream()
        val lines = PacketLineOut(out)
        lines.writeString("# service=$service\n")
        lines.end()
        val advertiser = RefAdvertiser.PacketLineOutRefAdvertiser(lines)
        if (service == "git-upload-pack") uploadPack(repo.git).sendAdvertisedRefs(advertiser) else receivePack(repo.git).sendAdvertisedRefs(advertiser)
        call.respondBytes(out.toByteArray(), ContentType.parse("application/x-$service-advertisement"))
    }

    private suspend fun rpc(call: ApplicationCall, service: String) {
        val caller = gitCaller(call) ?: return
        gitRequests += "POST $service" to caller.user
        val repo = gitRepo(call) ?: return
        if (service == "git-receive-pack" && caller is GitCaller.Developer && !caller.mayPush) {
            return call.respondText(ErrorCode.SYNC_READ_ONLY, status = HttpStatusCode.Forbidden)
        }
        val body = call.receive<ByteArray>()
        // JGit's client gzips a large request body.
        val input = if (call.request.headers[HttpHeaders.ContentEncoding] == "gzip") GZIPInputStream(ByteArrayInputStream(body)) else ByteArrayInputStream(body)
        val out = ByteArrayOutputStream()
        if (service == "git-upload-pack") {
            uploadPack(repo.git).upload(input, out, null)
        } else {
            val once = refuseNextPush.also { refuseNextPush = null }
            pushes.incrementAndGet()
            receivePack(repo.git, once).receive(input, out, null)
        }
        call.respondBytes(out.toByteArray(), ContentType.parse("application/x-$service-result"))
    }

    private fun uploadPack(git: Repository) = UploadPack(git).apply { isBiDirectionalPipe = false }

    private fun receivePack(git: Repository, refuseWith: String? = null) = ReceivePack(git).apply {
        isBiDirectionalPipe = false
        // Judged by the hook, so each refusal carries the protocol's reason rather than JGit's.
        isAllowNonFastForwards = true
        isAllowDeletes = true
        setPreReceiveHook { pack, commands ->
            for (command in commands) {
                if (command.result != ReceiveCommand.Result.NOT_ATTEMPTED) continue
                (refuseWith?.let { it to "refused once by the fake cloud" } ?: refusalOf(pack.repository, command))?.let { (code, words) ->
                    command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, PushRefusals.reason(code, words))
                }
            }
        }
    }

    private fun refusalOf(git: Repository, command: ReceiveCommand): Pair<String, String>? {
        refusePushes?.let { return it to "the fake cloud refuses every push" }
        val name = command.refName
        return when {
            name == "refs/heads/main" && command.type == ReceiveCommand.Type.DELETE -> ErrorCode.SYNC_REF_PROTECTED to "main is never deleted"
            name == "refs/heads/main" && command.type == ReceiveCommand.Type.UPDATE_NONFASTFORWARD -> ErrorCode.SYNC_NON_FAST_FORWARD to "main would not fast-forward"
            name == "refs/heads/main" && !hasFormatVersion(git, command.newId) -> ErrorCode.SYNC_FORMAT_INVALID to "main has no formatVersion.json"
            name == "refs/heads/main" -> null
            name.startsWith(Constants.R_TAGS) && command.type != ReceiveCommand.Type.CREATE -> ErrorCode.SYNC_TAG_IMMUTABLE to "${name.removePrefix(Constants.R_TAGS)} never moves"
            name.startsWith(Constants.R_TAGS) -> null
            else -> ErrorCode.SYNC_REF_PROTECTED to "only main and tags are pushed"
        }
    }

    private fun hasFormatVersion(git: Repository, commit: ObjectId): Boolean = RevWalk(git).use { walk ->
        TreeWalk.forPath(git, "formatVersion.json", walk.parseCommit(commit).tree)?.use { true } ?: false
    }

    private suspend fun ApplicationCall.apiError(status: HttpStatusCode, code: String) =
        respondText(CloudJson.encodeToString(ApiError.serializer(), ApiError(code, "fake cloud: $code")), ContentType.Application.Json, status)
}
