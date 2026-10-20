package app.gitpocket.core.git

import java.io.File

/** 测试辅助：直接调用真实 git 命令，用于"真实文件验证"。 */
object GitCli {

    val available: Boolean by lazy {
        runCatching { run(File(System.getProperty("java.io.tmpdir")), "--version") }.isSuccess
    }

    fun run(dir: File, vararg args: String): String {
        val command = listOf("git") + args
        val process = ProcessBuilder(command)
            .directory(dir)
            .redirectErrorStream(true)
            .apply {
                environment()["GIT_AUTHOR_NAME"] = "Test"
                environment()["GIT_AUTHOR_EMAIL"] = "test@example.com"
                environment()["GIT_COMMITTER_NAME"] = "Test"
                environment()["GIT_COMMITTER_EMAIL"] = "test@example.com"
                environment()["GIT_CONFIG_GLOBAL"] = File(dir, ".gitconfig-none").absolutePath
                environment()["GIT_CONFIG_SYSTEM"] = File(dir, ".gitconfig-none").absolutePath
            }
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) {
            throw IllegalStateException("git ${args.joinToString(" ")} 失败($code):\n$output")
        }
        return output
    }

    fun initRepo(dir: File, branch: String = "main") {
        run(dir, "init", "-q", "-b", branch)
    }

    fun commitAll(dir: File, message: String) {
        run(dir, "add", "-A")
        run(dir, "commit", "-q", "-m", message)
    }
}
