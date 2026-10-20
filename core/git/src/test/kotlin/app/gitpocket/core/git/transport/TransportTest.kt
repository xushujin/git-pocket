package app.gitpocket.core.git.transport

import app.gitpocket.core.git.GitCli
import app.gitpocket.core.git.GitRepo
import app.gitpocket.core.git.ObjectDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

class TransportTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `pkt-line 编解码往返`() {
        val payload = "want 0123456789012345678901234567890123456789\n".toByteArray()
        val encoded = PktLine.encode(payload)
        // 长度字段包含自身 4 字节
        assertEquals(payload.size + 4, String(encoded, 0, 4, StandardCharsets.US_ASCII).toInt(16))

        val reader = PktLineReader(ByteArrayInputStream(encoded + PktLine.FLUSH))
        assertTrue(payload.contentEquals(reader.next()))
        assertEquals(null, reader.next())
        assertTrue(reader.eof.not())
    }

    @Test
    fun `解析 v0 广告中的 refs 与能力`() {
        val out = ByteArrayOutputStream()
        out.write(PktLine.encode("# service=git-upload-pack\n"))
        out.write(PktLine.FLUSH)
        out.write(
            PktLine.encode(
                "1111111111111111111111111111111111111111 HEAD\u0000" +
                    "multi_ack thin-pack side-band-64k ofs-delta symref=HEAD:refs/heads/main agent=git/2.50\n"
            )
        )
        out.write(PktLine.encode("1111111111111111111111111111111111111111 refs/heads/main\n"))
        out.write(PktLine.encode("2222222222222222222222222222222222222222 refs/tags/v1\n"))
        out.write(PktLine.FLUSH)

        val advertisement = AdvertisementParser.parse(out.toByteArray())
        assertEquals(3, advertisement.refs.size)
        assertEquals("refs/heads/main", advertisement.headTarget)
        assertTrue("side-band-64k" in advertisement.capabilities)
        assertEquals(1, advertisement.branches().size)
        assertEquals(1, advertisement.tags().size)
    }

    /**
     * 真实文件验证：用 git 生成含 delta 的 pack，拆包后逐对象与 git cat-file 比对。
     */
    @Test
    fun `把真实 packfile 拆成 loose object 且内容一致`() {
        Assume.assumeTrue("环境缺少 git，跳过", GitCli.available)

        val dir = folder.newFolder("repo")
        GitCli.initRepo(dir)
        File(dir, "a.txt").writeText((1..200).joinToString("\n") { "line$it" } + "\n")
        File(dir, "b.txt").writeText((1..200).joinToString("\n") { "other$it" } + "\n")
        GitCli.commitAll(dir, "first")
        File(dir, "a.txt").writeText((1..200).joinToString("\n") { "line$it changed" } + "\n")
        GitCli.commitAll(dir, "second")
        GitCli.run(dir, "gc", "--aggressive", "--prune=now")

        val packFile = File(dir, ".git/objects/pack").listFiles { f -> f.name.endsWith(".pack") }?.firstOrNull()
        assertTrue("应生成 packfile", packFile != null)
        val packBytes = packFile!!.readBytes()

        val target = folder.newFolder("unpacked")
        File(target, ".git/objects").mkdirs()
        val db = ObjectDatabase(File(target, ".git"))
        val count = PackIngest.ingest(packBytes, db)
        assertTrue("应拆出对象", count > 0)

        val source = GitRepo.open(dir)!!
        val head = source.headCommit()!!
        val files = source.flattenTree(head.tree)
        files.forEach { (path, entry) ->
            val ours = (db.read(entry.oid) as app.gitpocket.core.git.GitBlob).text()
            val theirs = GitCli.run(dir, "cat-file", "-p", entry.oid.hex)
            assertEquals("内容不一致：$path", theirs, ours)
        }
    }
}
