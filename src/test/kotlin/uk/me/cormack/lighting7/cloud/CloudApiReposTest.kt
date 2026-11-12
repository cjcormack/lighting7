package uk.me.cormack.lighting7.cloud

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import uk.me.cormack.lighting7.cloud.protocol.CreateRepoRequest
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.InstallKeys
import uk.me.cormack.lighting7.cloud.protocol.RepoKind
import uk.me.cormack.lighting7.testsupport.FakeCloud
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [CloudApi]'s repo calls (cloud-p2 plan D1) against [FakeCloud], which checks every signature. */
class CloudApiReposTest {
    private val fake = FakeCloud()
    private val api = CloudApi(fake.baseUrl, Duration.ofSeconds(5))
    private val pair = InstallKeys.generate()
    private val signer = InstallSigner("inst-1", pair.private).also { fake.installs[it.installId] = FakeCloud.Install(it.installId, "adp-1", pair.public) }

    @After
    fun tearDown() {
        api.close()
        fake.close()
    }

    @Test
    fun `a create makes the repo, and a second for the project answers the one it has`() = runBlocking {
        val made = assertIs<CloudCall.Ok<RepoCreated>>(api.createRepo(signer, CreateRepoRequest("p-1", RepoKind.SHOW, "Panto"))).value
        assertFalse(made.existed)
        assertEquals("${fake.baseUrl}/git/${fake.org.id}/${made.repo.repoId}.git", made.repo.url)

        val again = assertIs<CloudCall.Ok<RepoCreated>>(api.createRepo(signer, CreateRepoRequest("p-1", RepoKind.SHOW, "Panto again"))).value
        assertTrue(again.existed)
        assertEquals(made.repo.repoId, again.repo.repoId)
    }

    @Test
    fun `a list and a get are signed by the install or read with a developer token`() = runBlocking {
        val repo = fake.repos.create("p-2", RepoKind.VENUE, "Hall")
        assertEquals(listOf(repo.repoId), assertIs<CloudCall.Ok<*>>(api.listRepos(RepoReader.Install(signer))).let { (it.value as uk.me.cormack.lighting7.cloud.protocol.RepoList).repos.map { r -> r.repoId } })
        fake.repos.devTokens["l7_dev_t"] = false
        assertEquals(repo, assertIs<CloudCall.Ok<*>>(api.getRepo(RepoReader.Developer("l7_dev_t"), repo.repoId)).value)

        val missing = assertIs<CloudCall.Refused>(api.getRepo(RepoReader.Install(signer), "nope"))
        assertEquals(404 to ErrorCode.REPO_NOT_FOUND, missing.status to missing.error?.code)
        val unsigned = assertIs<CloudCall.Refused>(api.listRepos(RepoReader.Install(InstallSigner("inst-1", InstallKeys.generate().private))))
        assertEquals(ErrorCode.BAD_SIGNATURE, unsigned.error?.code)

        fake.repos.syncOff = true
        assertEquals(ErrorCode.SYNC_OFF, assertIs<CloudCall.Refused>(api.listRepos(RepoReader.Install(signer))).error?.code)
    }
}
