package app.gitpocket.core.git

import app.gitpocket.core.model.ObjectId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 三方合并：
 * - [Diff3] 是纯函数，直接断言文本结果；
 * - [GitRepo.merge] 用真实 git 建出分叉仓库，再用真实 git 校验合并结果与冲突状态。
 */
class MergeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val ident = GitIdent("GitPocket Test", "test@example.com", 1_700_000_000, "+0800")

    // ---------------------------------------------------------------- Diff3

    @Test
    fun `只有一侧改动时直接取改动侧`() {
        val result = Diff3.merge("a\nb\nc\n", "a\nB\nc\n", "a\nb\nc\n")
        assertEquals(0, result.conflicts)
        assertEquals("a\nB\nc\n", result.text)
    }

    @Test
    fun `两侧改不同行时都保留且不冲突`() {
        val result = Diff3.merge("a\nb\nc\nd\n", "A\nb\nc\nd\n", "a\nb\nc\nD\n")
        assertEquals(0, result.conflicts)
        assertEquals("A\nb\nc\nD\n", result.text)
    }

    @Test
    fun `两侧改同一行时产生冲突标记`() {
        val result = Diff3.merge("a\nb\nc\n", "a\nours\nc\n", "a\ntheirs\nc\n", "HEAD", "origin/main")
        assertEquals(1, result.conflicts)
        assertTrue(result.text.contains("<<<<<<< HEAD"))
        assertTrue(result.text.contains("ours"))
        assertTrue(result.text.contains("======="))
        assertTrue(result.text.contains("theirs"))
        assertTrue(result.text.contains(">>>>>>> origin/main"))
    }

    @Test
    fun `两侧插入同一位置不同内容时算冲突`() {
        val result = Diff3.merge("a\nb\n", "a\nx\nb\n", "a\ny\nb\n")
        assertEquals(1, result.conflicts)
    }

    @Test
    fun `两侧插入同一位置相同内容时取其一`() {
        val result = Diff3.merge("a\nb\n", "a\nx\nb\n", "a\nx\nb\n")
        assertEquals(0, result.conflicts)
        assertEquals("a\nx\nb\n", result.text)
    }

    @Test
    fun `紧挨着但不重叠的改动不会误报冲突`() {
        // ours 改第 1 行，theirs 改第 2 行（相邻）
        val result = Diff3.merge("a\nb\nc\n", "A\nb\nc\n", "a\nB\nc\n")
        assertEquals(0, result.conflicts)
        assertEquals("A\nB\nc\n", result.text)
    }

    @Test
    fun `一侧删除另一侧未改动时保留删除`() {
        val result = Diff3.merge("a\nb\nc\n", "a\nc\n", "a\nb\nc\n")
        assertEquals(0, result.conflicts)
        assertEquals("a\nc\n", result.text)
    }

    @Test
    fun `空 base 且两侧都新增不同内容时算冲突`() {
        val result = Diff3.merge("", "ours\n", "theirs\n")
        assertEquals(1, result.conflicts)
    }

    // ---------------------------------------------------------------- 仓库级

    /** 建一个"main 与 feature 分叉"的真实仓库，返回目录。 */
    private fun forkRepo(name: String, mainFile: String, featureFile: String): File {
        val dir = folder.newFolder(name)
        GitCli.initRepo(dir)
        File(dir, "keep.txt").writeText("keep\n")
        File(dir, "conflict.txt").writeText("line1\nline2\nline3\n")
        GitCli.commitAll(dir, "base")

        GitCli.run(dir, "checkout", "-q", "-b", "feature")
        File(dir, "conflict.txt").writeText(featureFile)
        File(dir, "feature.txt").writeText("feature\n")
        GitCli.commitAll(dir, "feature 改动")

        GitCli.run(dir, "checkout", "-q", "main")
        File(dir, "conflict.txt").writeText(mainFile)
        GitCli.commitAll(dir, "main 改动")
        return dir
    }

    @Test
    fun `无冲突分叉能自动合并并产生两个父提交`() {
        Assume.assumeTrue(GitCli.available)
        val dir = forkRepo(
            name = "merge-clean",
            mainFile = "line1 main\nline2\nline3\n",
            featureFile = "line1\nline2\nline3\nfeature\n",
        )

        GitRepo.open(dir)!!.use { repo ->
            val target = repo.refs.read("refs/heads/feature")!!
            val outcome = repo.merge(target, "feature", ident)
            assertEquals(MergeStatus.MERGED, outcome.status)
            assertNotNull(outcome.commit)
        }

        // 真实 git 认可这次合并：两个父提交 + 工作区干净 + fsck 通过
        val parents = GitCli.run(dir, "rev-list", "--parents", "-n", "1", "HEAD").trim().split(" ")
        assertEquals(3, parents.size)
        assertEquals("", GitCli.run(dir, "status", "--porcelain").trim())
        GitCli.run(dir, "fsck", "--strict")
        assertEquals("feature\n", File(dir, "feature.txt").readText())
        assertTrue(File(dir, "conflict.txt").readText().contains("main"))
        assertTrue(File(dir, "conflict.txt").readText().contains("feature"))
    }

    @Test
    fun `分叉冲突时写冲突标记且 git 认成 UU`() {
        Assume.assumeTrue(GitCli.available)
        val dir = forkRepo(
            name = "merge-conflict",
            mainFile = "line1 main\nline2\nline3\n",
            featureFile = "line1 feature\nline2\nline3\n",
        )

        GitRepo.open(dir)!!.use { repo ->
            val target = repo.refs.read("refs/heads/feature")!!
            val outcome = repo.merge(target, "feature", ident)
            assertEquals(MergeStatus.CONFLICT, outcome.status)
            assertEquals(listOf("conflict.txt"), outcome.conflicts)
            assertTrue(repo.isMerging)
            assertEquals(listOf("conflict.txt"), repo.conflicts())

            // status 把冲突单独归为 CONFLICT，不会误报"未跟踪"
            val conflict = repo.status().filter { it.type == app.gitpocket.core.model.ChangeType.CONFLICT }
            assertEquals(listOf("conflict.txt"), conflict.map { it.path })
        }

        assertTrue(File(dir, "conflict.txt").readText().contains("<<<<<<< HEAD"))
        val porcelain = GitCli.run(dir, "status", "--porcelain").trim()
        // 冲突文件是 UU；能自动合并的文件（feature.txt）应该已经被暂存，与 git 行为一致
        assertTrue(porcelain.lines().contains("UU conflict.txt"))
        assertTrue(porcelain.lines().contains("A  feature.txt"))
        // 冲突未解决时不允许提交
        GitRepo.open(dir)!!.use { repo ->
            val failed = runCatching { repo.commit("不应该成功", ident) }.isFailure
            assertTrue(failed)
        }
    }

    @Test
    fun `选我的解决冲突后 git status 干净且能提交合并`() {
        Assume.assumeTrue(GitCli.available)
        val dir = forkRepo(
            name = "merge-resolve",
            mainFile = "line1 main\nline2\nline3\n",
            featureFile = "line1 feature\nline2\nline3\n",
        )

        GitRepo.open(dir)!!.use { repo ->
            val target = repo.refs.read("refs/heads/feature")!!
            repo.merge(target, "feature", ident)
            repo.resolveConflict("conflict.txt", ConflictStrategy.OURS)
            assertTrue(repo.conflicts().isEmpty())

            val id = repo.commit("合并 feature（取我的版本）", ident)
            assertEquals(id.hex, GitCli.run(dir, "rev-parse", "HEAD").trim())
        }

        assertEquals("", GitCli.run(dir, "status", "--porcelain").trim())
        assertTrue(File(dir, "conflict.txt").readText().contains("main"))
        val parents = GitCli.run(dir, "rev-list", "--parents", "-n", "1", "HEAD").trim().split(" ")
        assertEquals(3, parents.size)
    }

    @Test
    fun `放弃合并后工作区与 HEAD 完全一致`() {
        Assume.assumeTrue(GitCli.available)
        val dir = forkRepo(
            name = "merge-abort",
            mainFile = "line1 main\nline2\nline3\n",
            featureFile = "line1 feature\nline2\nline3\n",
        )

        GitRepo.open(dir)!!.use { repo ->
            val head = repo.headCommit()!!.let { repo.refs.head().headOid }
            repo.merge(repo.refs.read("refs/heads/feature")!!, "feature", ident)
            repo.abortMerge()
            assertEquals(head, repo.refs.head().headOid)
            assertTrue(!repo.isMerging)
            assertTrue(repo.conflicts().isEmpty())
        }

        assertEquals("", GitCli.run(dir, "status", "--porcelain").trim())
        assertEquals("line1 main\nline2\nline3\n", File(dir, "conflict.txt").readText())
    }

    @Test
    fun `快进合并不产生额外提交`() {
        Assume.assumeTrue(GitCli.available)
        val dir = folder.newFolder("merge-ff")
        GitCli.initRepo(dir)
        File(dir, "a.txt").writeText("v1\n")
        GitCli.commitAll(dir, "base")
        GitCli.run(dir, "checkout", "-q", "-b", "feature")
        File(dir, "a.txt").writeText("v2\n")
        GitCli.commitAll(dir, "v2")
        GitCli.run(dir, "checkout", "-q", "main")

        GitRepo.open(dir)!!.use { repo ->
            val target = repo.refs.read("refs/heads/feature")!!
            val outcome = repo.merge(target, "feature", ident)
            assertEquals(MergeStatus.FAST_FORWARD, outcome.status)
        }

        assertEquals("v2\n", File(dir, "a.txt").readText())
        assertEquals("v2", GitCli.run(dir, "log", "-1", "--format=%s").trim())
        assertEquals("", GitCli.run(dir, "status", "--porcelain").trim())
    }

    // ---------------------------------------------------------------- 分支

    @Test
    fun `切换分支会重建工作区并保留未跟踪文件`() {
        Assume.assumeTrue(GitCli.available)
        val dir = folder.newFolder("checkout")
        GitCli.initRepo(dir)
        File(dir, "a.txt").writeText("main\n")
        GitCli.commitAll(dir, "main")
        GitCli.run(dir, "checkout", "-q", "-b", "feature")
        File(dir, "b.txt").writeText("feature\n")
        GitCli.commitAll(dir, "feature")
        GitCli.run(dir, "checkout", "-q", "main")
        File(dir, "scratch.log").writeText("未跟踪\n")

        GitRepo.open(dir)!!.use { repo ->
            repo.checkoutBranch("feature")
            assertEquals("feature", repo.refs.currentBranch())
            assertTrue(File(dir, "b.txt").exists())
            assertTrue(File(dir, "scratch.log").exists())
        }
        // 未跟踪文件不在 index 里，git 仍然认它是 untracked（不会被切换分支带走）
        assertEquals("?? scratch.log", GitCli.run(dir, "status", "--porcelain").trim())

        GitRepo.open(dir)!!.use { repo ->
            repo.checkoutBranch("main")
            assertTrue(!File(dir, "b.txt").exists())
        }
        assertEquals("main\n", File(dir, "a.txt").readText())
    }

    @Test
    fun `工作区脏时拒绝切换分支`() {
        val dir = folder.newFolder("checkout-dirty")
        GitCli.initRepo(dir)
        File(dir, "a.txt").writeText("v1\n")
        GitCli.commitAll(dir, "base")
        GitCli.run(dir, "checkout", "-q", "-b", "feature")
        GitCli.run(dir, "checkout", "-q", "main")
        File(dir, "a.txt").writeText("改了\n")

        GitRepo.open(dir)!!.use { repo ->
            val error = runCatching { repo.checkoutBranch("feature") }.exceptionOrNull()
            assertTrue(error is GitException.DirtyWorkTree)
            assertEquals("main", repo.refs.currentBranch())
        }
    }

    @Test
    fun `新建分支后能切换且分支名非法会被拒绝`() {
        val dir = folder.newFolder("branch-create")
        GitCli.initRepo(dir)
        File(dir, "a.txt").writeText("v1\n")
        GitCli.commitAll(dir, "base")

        GitRepo.open(dir)!!.use { repo ->
            repo.createBranch("feature/x", checkout = true)
            assertEquals("feature/x", repo.refs.currentBranch())

            assertTrue(runCatching { repo.createBranch("bad name", checkout = false) }.exceptionOrNull() is GitException.InvalidBranchName)
            assertTrue(runCatching { repo.createBranch("feature/x", checkout = false) }.exceptionOrNull() is GitException.BranchExists)
        }
        assertEquals("feature/x", GitCli.run(dir, "rev-parse", "--abbrev-ref", "HEAD").trim())
    }

    @Test
    fun `撤销上次提交后改动回到暂存区`() {
        Assume.assumeTrue(GitCli.available)
        val dir = folder.newFolder("undo-commit")
        GitCli.initRepo(dir)
        File(dir, "a.txt").writeText("v1\n")
        GitCli.commitAll(dir, "base")
        File(dir, "a.txt").writeText("v2\n")
        GitCli.commitAll(dir, "second")

        GitRepo.open(dir)!!.use { repo ->
            assertNotNull(repo.undoLastCommit())
        }

        assertEquals("base", GitCli.run(dir, "log", "-1", "--format=%s").trim())
        // soft reset：工作区内容保留，改动回到暂存区
        assertEquals("v2\n", File(dir, "a.txt").readText())
        assertEquals("M  a.txt", GitCli.run(dir, "status", "--porcelain").trim())
    }

    @Test
    fun `没有共同祖先时拒绝合并`() {
        val dir = folder.newFolder("unrelated")
        GitCli.initRepo(dir)
        File(dir, "a.txt").writeText("v1\n")
        GitCli.commitAll(dir, "base")
        // 造一个孤儿提交
        GitCli.run(dir, "checkout", "-q", "--orphan", "orphan")
        File(dir, "a.txt").writeText("orphan\n")
        GitCli.commitAll(dir, "orphan")
        GitCli.run(dir, "checkout", "-q", "main")

        GitRepo.open(dir)!!.use { repo ->
            val target = repo.refs.read("refs/heads/orphan")!!
            val error = runCatching { repo.merge(target, "orphan", ident) }.exceptionOrNull()
            assertTrue(error is GitException.UnsupportedFeature)
        }
    }

    @Test
    fun `合并会覆盖未跟踪文件时直接拒绝`() {
        Assume.assumeTrue(GitCli.available)
        val dir = folder.newFolder("merge-untracked")
        GitCli.initRepo(dir)
        File(dir, "a.txt").writeText("base\n")
        GitCli.commitAll(dir, "base")
        GitCli.run(dir, "checkout", "-q", "-b", "feature")
        File(dir, "new.txt").writeText("feature 版本\n")
        GitCli.commitAll(dir, "add new")
        GitCli.run(dir, "checkout", "-q", "main")
        File(dir, "a.txt").writeText("main 改动\n")
        GitCli.commitAll(dir, "main")
        // 本地有一个同名未跟踪文件，合并会覆盖它
        File(dir, "new.txt").writeText("用户自己写的\n")

        GitRepo.open(dir)!!.use { repo ->
            val error = runCatching {
                repo.merge(repo.refs.read("refs/heads/feature")!!, "feature", ident)
            }.exceptionOrNull()
            assertTrue(error is GitException.UntrackedOverwrite)
        }
        assertEquals("用户自己写的\n", File(dir, "new.txt").readText())
    }

    @Test
    fun `mergeBase 与 git merge-base 一致`() {
        Assume.assumeTrue(GitCli.available)
        val dir = forkRepo("merge-base", "line1 main\nline2\nline3\n", "line1 feature\nline2\nline3\n")
        val expected = GitCli.run(dir, "merge-base", "main", "feature").trim()

        GitRepo.open(dir)!!.use { repo ->
            val main = repo.refs.read("refs/heads/main")!!
            val feature = repo.refs.read("refs/heads/feature")!!
            assertEquals(expected, ObjectWalk.mergeBase(repo.objects, main, feature)?.hex)
        }
    }

    @Test
    fun `相同提交的 mergeBase 是它自己且无父提交时返回 null`() {
        val dir = folder.newFolder("merge-base-edge")
        GitCli.initRepo(dir)
        File(dir, "a.txt").writeText("v1\n")
        GitCli.commitAll(dir, "base")

        GitRepo.open(dir)!!.use { repo ->
            val head = repo.refs.head().headOid!!
            assertEquals(head, ObjectWalk.mergeBase(repo.objects, head, head))
            val orphan = folder.newFolder("orphan-repo")
            GitCli.initRepo(orphan)
            assertNull(GitRepo.open(orphan)!!.use { r -> r.refs.head().headOid })
        }
    }

    // ---------------------------------------------------------------- 回归

    /**
     * 回归：以前脏工作区检查放在"能否快进"之后，导致 pull 在可快进时
     * 直接 checkoutTree 把未提交的改动覆盖掉，且不报错。
     */
    @Test
    fun `工作区有未提交改动时拒绝快进合并`() {
        Assume.assumeTrue(GitCli.available)
        val dir = folder.newFolder("dirty-ff")
        GitCli.initRepo(dir)
        File(dir, "a.txt").writeText("v1\n")
        GitCli.commitAll(dir, "c1")

        GitCli.run(dir, "checkout", "-q", "-b", "ahead")
        File(dir, "a.txt").writeText("v2-remote\n")
        GitCli.commitAll(dir, "c2")
        GitCli.run(dir, "checkout", "-q", "main")

        File(dir, "a.txt").writeText("LOCAL-DIRTY\n")

        GitRepo.open(dir)!!.use { repo ->
            val target = repo.refs.read("refs/heads/ahead")!!
            val error = runCatching { repo.merge(target, "ahead", ident) }.exceptionOrNull()
            assertTrue("期望 DirtyWorkTree，实际 $error", error is GitException.DirtyWorkTree)
            // 关键：文件内容不能被改写
            assertEquals("LOCAL-DIRTY\n", File(dir, "a.txt").readText())
        }
    }

    @Test
    fun `从指定提交建分支而不是当前 HEAD`() {
        Assume.assumeTrue(GitCli.available)
        val dir = folder.newFolder("branch-start")
        GitCli.initRepo(dir)
        File(dir, "a.txt").writeText("v1\n")
        GitCli.commitAll(dir, "c1")
        val first = GitCli.run(dir, "rev-parse", "HEAD").trim()
        File(dir, "a.txt").writeText("v2\n")
        GitCli.commitAll(dir, "c2")

        GitRepo.open(dir)!!.use { repo ->
            repo.createBranch("from-first", checkout = false, startPoint = ObjectId(first))
            assertEquals(first, repo.refs.read("refs/heads/from-first")?.hex)
        }
        // 真实 git 也要认这个分支指向第一个提交
        assertEquals(first, GitCli.run(dir, "rev-parse", "from-first").trim())
    }
}
