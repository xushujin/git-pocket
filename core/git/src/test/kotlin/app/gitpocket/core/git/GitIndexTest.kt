package app.gitpocket.core.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * index 读写验证：读要跟 `git ls-files --stage` 对齐，
 * 写要能让真实 git 认为工作区干净。
 */
class GitIndexTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun prepareRepo(): File {
        Assume.assumeTrue(GitCli.available)
        val dir = folder.newFolder("repo")
        GitCli.initRepo(dir)
        File(dir, "a.txt").writeText("a\n")
        File(dir, "b.txt").writeText("b\n")
        File(dir, "sub").mkdirs()
        File(dir, "sub/c.txt").writeText("c\n")
        GitCli.commitAll(dir, "init")
        return dir
    }

    @Test
    fun `读取真实 index 与 git ls-files --stage 一致`() {
        val dir = prepareRepo()
        val repo = GitRepo.open(dir)!!
        val index = repo.readIndex()

        val expected = GitCli.run(dir, "ls-files", "--stage")
            .lines()
            .filter { it.isNotBlank() }

        assertEquals(expected.size, index.entries.size)
        expected.forEach { line ->
            val meta = line.substringBefore('\t')
            val path = line.substringAfter('\t')
            val parts = meta.split(" ")
            val entry = index.entry(path)
            assertNotNull("缺少条目：$path", entry)
            assertEquals(parts[0].toInt(8), entry!!.mode)
            assertEquals(parts[1], entry.oid.hex)
            assertEquals(parts[2].toInt(), entry.stage)
        }
    }

    @Test
    fun `写回 index 后 git status 仍然干净`() {
        val dir = prepareRepo()
        val repo = GitRepo.open(dir)!!
        repo.readIndex().writeTo(File(dir, ".git/index"))

        val status = GitCli.run(dir, "status", "--porcelain").trim()
        assertEquals("写回 index 后工作区应保持干净", "", status)
    }

    @Test
    fun `stage 新文件后 git 能看到已暂存状态`() {
        val dir = prepareRepo()
        val repo = GitRepo.open(dir)!!
        File(dir, "c.txt").writeText("new file\n")

        repo.stage("c.txt")

        val status = GitCli.run(dir, "status", "--porcelain").trim()
        assertEquals("A  c.txt", status)

        // 再用 git 校验暂存内容与工作区一致
        assertEquals("new file\n", GitCli.run(dir, "cat-file", "-p", ":c.txt"))
    }

    @Test
    fun `stageAll 能处理修改与删除`() {
        val dir = prepareRepo()
        val repo = GitRepo.open(dir)!!
        File(dir, "a.txt").writeText("a modified\n")
        File(dir, "b.txt").delete()

        repo.stageAll()

        val status = GitCli.run(dir, "status", "--porcelain").lines()
            .filter { it.isNotBlank() }
            .sorted()
        assertEquals(listOf("D  b.txt", "M  a.txt"), status)
    }
}
