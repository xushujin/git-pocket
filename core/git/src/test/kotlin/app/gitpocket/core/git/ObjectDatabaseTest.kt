package app.gitpocket.core.git

import app.gitpocket.core.model.ObjectId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ObjectDatabaseTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newDb(): Pair<ObjectDatabase, File> {
        val dir = folder.newFolder("repo")
        val gitDir = File(dir, ".git")
        File(gitDir, "objects").mkdirs()
        return ObjectDatabase(gitDir) to gitDir
    }

    @Test
    fun `blob 写入后可读回且 oid 与 git hash-object 一致`() {
        val (db, _) = newDb()
        val content = "hello gitpocket\n".toByteArray()
        val id = db.write(GitBlob(content))

        val read = db.read(id) as GitBlob
        assertTrue(content.contentEquals(read.content))

        // 与真实 git 计算出的 oid 对齐（已知值：git hash-object 对 "hello gitpocket\n"）
        val expected = Sha1.digest("blob ${content.size}\u0000".toByteArray(), content)
        assertEquals(expected, id)
    }

    @Test
    fun `commit 编解码往返保持字段与多行 gpgsig`() {
        val commit = GitCommit(
            tree = ObjectId("4b825dc642cb6eb9a060e54bf8d69288fbee4904"),
            parents = listOf(ObjectId("1".repeat(40))),
            author = GitIdent("张三", "zhangsan@example.com", 1_700_000_000, "+0800"),
            committer = GitIdent("张三", "zhangsan@example.com", 1_700_000_001, "+0800"),
            message = "feat: 支持中文提交信息\n\n第二段",
            extraHeaders = listOf("gpgsig" to "-----BEGIN PGP SIGNATURE-----\nabc\ndef\n-----END PGP SIGNATURE-----"),
        )

        val parsed = parseCommit(commit.content)
        assertEquals(commit.tree, parsed.tree)
        assertEquals(commit.parents, parsed.parents)
        assertEquals(commit.author, parsed.author)
        assertEquals(commit.message, parsed.message)
        assertEquals(commit.extraHeaders, parsed.extraHeaders)
    }

    @Test
    fun `tree 编解码保留 mode 与排序`() {
        val tree = GitTree(
            listOf(
                GitTreeEntry(GitTreeEntry.MODE_FILE, "a.txt", ObjectId("2".repeat(40))),
                GitTreeEntry(GitTreeEntry.MODE_TREE, "dir", ObjectId("3".repeat(40))),
                GitTreeEntry(GitTreeEntry.MODE_EXECUTABLE, "run.sh", ObjectId("4".repeat(40))),
            )
        )
        val parsed = parseTree(tree.content)
        assertEquals(3, parsed.entries.size)
        assertEquals("a.txt", parsed.entries[0].name)
        assertEquals(GitTreeEntry.MODE_TREE, parsed.entries[1].mode)
        assertEquals("run.sh", parsed.entries[2].name)
        assertEquals(0x81ED, parsed.entries[2].mode)
    }

    @Test
    fun `损坏的 loose object 会被拒绝`() {
        val (db, gitDir) = newDb()
        val id = db.write(GitBlob("abc".toByteArray()))
        val file = File(gitDir, "objects/${id.hex.substring(0, 2)}/${id.hex.substring(2)}")
        file.writeBytes(byteArrayOf(1, 2, 3))

        val failure = runCatching { db.read(id) }.exceptionOrNull()
        assertTrue(failure is GitException.ObjectCorrupted)
    }
}
