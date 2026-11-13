package uk.me.cormack.lighting7.sync.auth

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthResolverTest {

    private val baseUrl = "https://cloud.example"
    private val cloudRepo = "$baseUrl/git/org_01ABC/repo_01SHOW.git"

    private fun source(devToken: String? = null) = CloudCredentialSource(
        baseUrl = baseUrl,
        enabled = true,
        signer = { null },
        devToken = devToken,
        api = { null },
    )

    @Test
    fun `a cloud repo is the cloud source's`() {
        val resolver = AuthResolver(source(devToken = "l7_dev_01TEST_abc"))
        assertTrue(resolver.isCloudRepo(cloudRepo))
        val credentials = resolver.resolveFor(cloudRepo)
        assertEquals(CloudCredentialSource.DEV_TOKEN_USER, credentials.username)
        assertEquals("l7_dev_01TEST_abc", credentials.secret)
    }

    @Test
    fun `a cloud repo on an unadopted desk with no developer token is not adopted`() {
        assertFailsWith<CloudNotAdoptedException> { AuthResolver(source()).resolveFor(cloudRepo) }
    }

    @Test
    fun `a GitHub repo is retired, with or without a cloud`() {
        val github = "https://github.com/me/lighting7-show.git"
        assertFalse(AuthResolver(source()).isCloudRepo(github))
        assertFailsWith<GitHubRetiredException> { AuthResolver(source()).resolveFor(github) }
        assertFailsWith<GitHubRetiredException> { AuthResolver(cloud = null).resolveFor(github) }
    }

    @Test
    fun `GitHub is told by its host, and any other repo is not the cloud's`() {
        val resolver = AuthResolver(source())
        listOf(
            "https://github.com/me/show.git",
            "https://GitHub.com/me/show",
            "git@github.com:me/show.git",
            "ssh://git@github.com/me/show.git",
        ).forEach { assertTrue(resolver.isGitHubRepo(it), it) }

        val otherCloud = "https://other-cloud.example/git/org_01ABC/repo_01SHOW.git"
        val random = "https://git.example.org/me/show.git"
        listOf(otherCloud, random, cloudRepo).forEach { url ->
            assertFalse(resolver.isGitHubRepo(url), url)
        }
        listOf(otherCloud, random).forEach { url ->
            assertFalse(resolver.canSyncWith(url), url)
            val refused = assertFailsWith<NotACloudRepoException> { resolver.resolveFor(url) }
            assertTrue(baseUrl in refused.message.orEmpty(), refused.message)
            assertFalse("move" in refused.message.orEmpty().lowercase(), refused.message)
        }
        assertTrue(resolver.canSyncWith(cloudRepo))
        assertFailsWith<NotACloudRepoException> { AuthResolver(cloud = null).resolveFor(random) }
    }

    @Test
    fun `a local repo needs no credentials only where local repos are allowed`() {
        val local = "file:///tmp/remote.git"
        AuthResolver(cloud = null, localRepos = true).resolveFor(local)
        assertTrue(AuthResolver(cloud = null, localRepos = true).canSyncWith(local))
        assertTrue(AuthResolver(cloud = null, localRepos = true).isGitHubRepo(local))
        assertFalse(AuthResolver(cloud = null).canSyncWith(local))
        assertFailsWith<NotACloudRepoException> { AuthResolver(cloud = null).resolveFor(local) }
        assertFailsWith<GitHubRetiredException> {
            AuthResolver(cloud = null, localRepos = true).resolveFor("https://github.com/me/show.git")
        }
    }

}
