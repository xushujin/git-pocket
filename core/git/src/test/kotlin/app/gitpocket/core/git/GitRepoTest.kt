package app.gitpocket.core.git

import app.gitpocket.core.model.ChangeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 端到端验证：用本引擎 init/写文件/stage/commit，
 * 再用真实 git 检查 HEAD、目录树、工作区状态与 fsck 结果。
 */
class GitRepoTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val ident = GitIdent("GitPocket Test", "test@example.com", 1_700_000_000, "+0800")

    @Test
    fun `本引擎提交后 git 能正确读取且 fsck 通过`() {
        Assume.assumeTrue(GitCli.available)

        val dir = folder.newFolder("repo")
        val repo = GitRepo.init(dir)
        File(dir, "README.md").writeText("# GitPocket\n")
        File(dir, "src").mkdirs()
        File(dir, "src/Main.kt").writeText("fun main() {}\n")

        repo.stageAll()
        val id = repo.commit("feat: 初始化仓库", ident)

        assertEquals(id.hex, GitCli.run(dir, "rev-parse", "HEAD").trim())
        assertEquals("feat: 初始化仓库", GitCli.run(dir, "log", "-1", "--format=%s").trim())

        val files = GitCli.run(dir, "ls-tree", "-r", "--name-only", "HEAD")
            .lines().filter { it.isNotBlank() }.sorted()
        assertEquals(listOf("README.md", "src/Main.kt"), files)

        // 真实 git 认为工作区干净
        assertEquals("", GitCli.run(dir, "status", "--porcelain").trim())

        // 结构完整、无损坏对象
        GitCli.run(dir, "fsck", "--strict")
    }

    @Test
    fun `提交内容与文件内容逐字节一致`() {
        Assume.assumeTrue(GitCli.available)

        val dir = folder.newFolder("repo2")
        val repo = GitRepo.init(dir)
        val content = "中文内容\nemoji 🎉\n"
        File(dir, "note.md").writeText(content)
        repo.stageAll()
        repo.commit("docs: 中文", ident)

        assertEquals(content, GitCli.run(dir, "cat-file", "-p", "HEAD:note.md"))
    }

    @Test
    fun `status 能区分未跟踪 修改 已暂存`() {
        val dir = folder.newFolder("repo3")
        val repo = GitRepo.init(dir)
        File(dir, "tracked.txt").writeText("v1\n")
        repo.stageAll()
        repo.commit("init", ident)

        File(dir, "untracked.txt").writeText("u\n")
        File(dir, "tracked.txt").writeText("v2\n")

        val status = repo.status()
        assertTrue(status.any { it.path == "untracked.txt" && it.type == ChangeType.UNTRACKED })
        assertTrue(status.any { it.path == "tracked.txt" && it.type == ChangeType.MODIFIED && !it.staged })

        repo.stage("tracked.txt")
        val afterStage = repo.status()
        assertTrue(afterStage.any { it.path == "tracked.txt" && it.staged })
        assertTrue(afterStage.none { it.path == "tracked.txt" && !it.staged })
    }

    @Test
    fun `log 按时间倒序返回且包含父提交`() {
        val dir = folder.newFolder("repo4")
        val repo = GitRepo.init(dir)
        File(dir, "a.txt").writeText("1\n")
        repo.stageAll()
        repo.commit("first", ident)
        File(dir, "a.txt").writeText("2\n")
        repo.stageAll()
        repo.commit("second", ident)

        val log = repo.log(10)
        assertEquals(2, log.size)
        assertEquals("second", log[0].subject)
        assertEquals("first", log[1].subject)
        assertEquals(1, log[0].parents.size)
    }

    @Test
    fun `空提交会被拒绝`() {
        val dir = folder.newFolder("repo5")
        val repo = GitRepo.init(dir)
        val failure = runCatching { repo.commit("empty", ident) }.exceptionOrNull()
        assertTrue(failure is GitException.NothingToCommit)
    }

    @Test
    fun `diff 能反映工作区改动`() {
        val dir = folder.newFolder("repo6")
        val repo = GitRepo.init(dir)
        File(dir, "a.txt").writeText("one\ntwo\n")
        repo.stageAll()
        repo.commit("init", ident)
        File(dir, "a.txt").writeText("one\ntwo-changed\n")

        val diff = repo.diffAgainstHead("a.txt")
        assertEquals(1, diff.additions)
        assertEquals(1, diff.deletions)
    }
}
