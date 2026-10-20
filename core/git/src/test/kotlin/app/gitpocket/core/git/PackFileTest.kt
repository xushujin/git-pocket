package app.gitpocket.core.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * packfile 真实文件验证：用 git 生成真实 pack（含 delta），
 * 再用 git cat-file 的输出逐对象比对。
 */
class PackFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `读取真实 packfile 的对象与 git cat-file 一致`() {
        Assume.assumeTrue("环境缺少 git，跳过真实文件验证", GitCli.available)

        val dir = folder.newFolder("repo")
        GitCli.initRepo(dir)
        File(dir, "a.txt").writeText("line1\nline2\n")
        File(dir, "b.txt").writeText("b\n")
        GitCli.commitAll(dir, "first")

        // 制造相似内容，让 git 在 repack 时产生 delta
        File(dir, "a.txt").writeText((1..300).joinToString("\n") { "line$it" } + "\n")
        File(dir, "b.txt").writeText((1..300).joinToString("\n") { "b-line$it" } + "\n")
        GitCli.commitAll(dir, "second")

        GitCli.run(dir, "gc", "--aggressive", "--prune=now")

        val repo = GitRepo.open(dir)!!
        val packs = repo.objects.listPacks()
        assertTrue("gc 之后应存在 packfile", packs.isNotEmpty())
        packs.forEach { assertTrue("pack 校验和必须正确", it.verifyChecksum()) }

        val head = repo.headCommit()!!
        val files = repo.flattenTree(head.tree)
        assertEquals(2, files.size)

        files.forEach { (path, entry) ->
            val ours = (repo.objects.read(entry.oid) as GitBlob).text()
            val theirs = GitCli.run(dir, "cat-file", "-p", entry.oid.hex)
            assertEquals("blob 内容不一致：$path", theirs, ours)
        }
    }

    @Test
    fun `解析 commit 对象与 git cat-file 输出一致`() {
        Assume.assumeTrue(GitCli.available)

        val dir = folder.newFolder("repo2")
        GitCli.initRepo(dir)
        File(dir, "x.txt").writeText("x\n")
        GitCli.commitAll(dir, "feat: 初始提交")

        val repo = GitRepo.open(dir)!!
        val head = repo.headCommit()!!
        val raw = GitCli.run(dir, "cat-file", "-p", "HEAD")

        assertTrue("提交信息应被解析", raw.contains("feat: 初始提交"))
        assertEquals("feat: 初始提交", head.subject)
        assertEquals(0, head.parents.size)
    }
}
