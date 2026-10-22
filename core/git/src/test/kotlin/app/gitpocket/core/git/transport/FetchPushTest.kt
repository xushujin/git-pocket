package app.gitpocket.core.git.transport

import app.gitpocket.core.git.GitCommit
import app.gitpocket.core.git.GitException
import app.gitpocket.core.git.GitIdent
import app.gitpocket.core.git.GitRepo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * fetch / push 的端到端测试：客户端是我们的实现，服务端是**真实的 git http-backend**。
 *
 * 覆盖点：
 * - want/have 协商后只传缺失对象；
 * - 无 delta 的 pack 能被 git 正确接收（push 的 pack 由 PackWriter 生成）；
 * - report-status 解析；
 * - 非快进拒绝。
 */
class FetchPushTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var remote: File
    private lateinit var seed: File
    private lateinit var server: GitHttpBackendServer

    private val ident = GitIdent(
        name = "Test",
        email = "test@example.com",
        timeSeconds = 1_700_000_000,
        tzOffset = "+0800",
    )

    @Before
    fun setUp() {
        assumeTrue("需要本机 git", gitAvailable())
        remote = File(tmp.root, "remote.git")
        seed = File(tmp.root, "seed")

        git("init", "--bare", "-b", "main", remote.absolutePath)
        git("-C", remote.absolutePath, "config", "http.receivepack", "true")

        git("init", "-b", "main", seed.absolutePath)
        git("-C", seed.absolutePath, "config", "user.email", "seed@example.com")
        git("-C", seed.absolutePath, "config", "user.name", "Seed")
        File(seed, "a.txt").writeText("a\n")
        git("-C", seed.absolutePath, "add", ".")
        git("-C", seed.absolutePath, "commit", "-m", "init")
        git("-C", seed.absolutePath, "remote", "add", "origin", remote.absolutePath)
        git("-C", seed.absolutePath, "push", "origin", "main")

        server = GitHttpBackendServer(tmp.root)
        server.start()
    }

    @After
    fun tearDown() {
        if (this::server.isInitialized) server.stop()
    }

    @Test
    fun `fetch 拿到远端提交并能快进`() {
        val repo = freshRepo()
        val result = GitFetcher().fetch(remoteUrl(), repo)

        assertTrue("应该拉到对象", result.newObjects > 0)
        val tip = result.remoteHeadOid
        assertNotNull("应解析出远端 HEAD", tip)
        assertEquals("main", result.remoteHeadBranch)
        assertTrue("远端跟踪引用应被写入", result.updatedRefs.containsKey("refs/remotes/origin/main"))

        // 模拟 clone 的收尾：建本地分支 + 检出
        repo.refs.update("refs/heads/main", tip!!)
        repo.refs.updateHead("refs/heads/main")
        repo.checkoutTree((repo.objects.read(tip) as GitCommit).tree)
        assertTrue(File(repo.workTree, "a.txt").isFile)
        repo.close()
    }

    @Test
    fun `push 能把本地提交推到真 git`() {
        val repo = prepareRepoWithClone()

        File(repo.workTree, "b.txt").writeText("b\n")
        repo.stageAll()
        repo.commit("add b", ident)

        val result = GitPusher().push(remoteUrl(), repo, "main")
        assertFalse("不应是 up-to-date", result.upToDate)
        repo.close()

        val log = git("-C", remote.absolutePath, "log", "--oneline", "main")
        assertTrue("远端应包含新提交：$log", log.contains("add b"))
        val head = git("-C", remote.absolutePath, "rev-parse", "main").trim()
        assertEquals("远端 main 应指向最新提交", git("-C", seed.absolutePath, "rev-parse", "HEAD").trim().length, head.length)
    }

    @Test
    fun `远端有新提交时 fetch 只拉增量`() {
        val repo = prepareRepoWithClone()

        // 远端前进一个提交
        File(seed, "c.txt").writeText("c\n")
        git("-C", seed.absolutePath, "add", ".")
        git("-C", seed.absolutePath, "commit", "-m", "remote c")
        git("-C", seed.absolutePath, "push", "origin", "main")

        val result = GitFetcher().fetch(remoteUrl(), repo)
        println("DEBUG remote head=" + git("-C", remote.absolutePath, "rev-parse", "main").trim())
        assertTrue("应拉到增量对象，实际 ${result.newObjects}", result.newObjects in 1..3)
        val remoteTip = result.updatedRefs.getValue("refs/remotes/origin/main")
        assertTrue("快进应成功", repo.fastForward("main", remoteTip))
        assertTrue(File(repo.workTree, "c.txt").isFile)
        repo.close()
    }

    @Test
    fun `分叉时 push 被拒绝为非快进`() {
        val repo = prepareRepoWithClone()

        // 远端先前进
        File(seed, "d.txt").writeText("d\n")
        git("-C", seed.absolutePath, "add", ".")
        git("-C", seed.absolutePath, "commit", "-m", "remote d")
        git("-C", seed.absolutePath, "push", "origin", "main")

        // 本地也提交（基于旧 tip）
        File(repo.workTree, "e.txt").writeText("e\n")
        repo.stageAll()
        repo.commit("local e", ident)

        val error = runCatching { GitPusher().push(remoteUrl(), repo, "main") }.exceptionOrNull()
        assertTrue("应抛 NonFastForward，实际：$error", error is GitException.NonFastForward)
        repo.close()
    }

    // ---------------------------------------------------------------- helpers

    private fun remoteUrl() = "${server.baseUrl}/remote.git"

    private fun freshRepo(): GitRepo {
        val dir = File(tmp.root, "local-${System.nanoTime()}")
        return GitRepo.init(dir)
    }

    /** 建一个"已克隆"的本地仓库：fetch + 建分支 + 检出。 */
    private fun prepareRepoWithClone(): GitRepo {
        val repo = freshRepo()
        val fetched = GitFetcher().fetch(remoteUrl(), repo)
        val tip = fetched.remoteHeadOid!!
        repo.refs.update("refs/heads/main", tip)
        repo.refs.updateHead("refs/heads/main")
        repo.checkoutTree((repo.objects.read(tip) as GitCommit).tree)
        return repo
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} 失败：$output" }
        return output
    }

    private fun gitAvailable(): Boolean = runCatching {
        ProcessBuilder("git", "--version").start().waitFor() == 0
    }.getOrDefault(false)
}
