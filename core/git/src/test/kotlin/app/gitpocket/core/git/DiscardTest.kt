package app.gitpocket.core.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiscardTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `撤销已跟踪文件的修改后与 HEAD 一致`() {
        Assume.assumeTrue("环境缺少 git，跳过", GitCli.available)

        val dir = folder.newFolder("repo")
        GitCli.initRepo(dir)
        File(dir, "a.txt").writeText("v1\n")
        GitCli.commitAll(dir, "init")

        val repo = GitRepo.open(dir)!!
        repo.use {
            File(dir, "a.txt").writeText("v2 modified\n")
            assertEquals(1, it.status().size)

            assertTrue(it.discardWorkFile("a.txt"))
            assertEquals("v1\n", File(dir, "a.txt").readText())
            assertTrue("撤销后工作区应为干净", it.status().isEmpty())
        }
        assertEquals("", GitCli.run(dir, "status", "--porcelain").trim())
    }

    @Test
    fun `撤销未跟踪文件会删除它`() {
        Assume.assumeTrue("环境缺少 git，跳过", GitCli.available)

        val dir = folder.newFolder("repo2")
        GitCli.initRepo(dir)
        File(dir, "a.txt").writeText("v1\n")
        GitCli.commitAll(dir, "init")

        val repo = GitRepo.open(dir)!!
        repo.use {
            File(dir, "new.txt").writeText("untracked\n")
            assertTrue(it.status().any { change -> change.path == "new.txt" })

            assertTrue(it.discardWorkFile("new.txt"))
            assertFalse(File(dir, "new.txt").exists())
            assertTrue(it.status().isEmpty())
        }
    }

    @Test
    fun `撤销不存在的文件返回 false`() {
        Assume.assumeTrue("环境缺少 git，跳过", GitCli.available)

        val dir = folder.newFolder("repo3")
        GitCli.initRepo(dir)
        File(dir, "a.txt").writeText("v1\n")
        GitCli.commitAll(dir, "init")

        GitRepo.open(dir)!!.use {
            assertFalse(it.discardWorkFile("nope.txt"))
        }
    }
}
