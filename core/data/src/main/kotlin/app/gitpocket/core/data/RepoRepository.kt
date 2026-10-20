package app.gitpocket.core.data

import app.gitpocket.core.common.AppDispatchers
import app.gitpocket.core.common.RepoLockRegistry
import app.gitpocket.core.database.DraftEntity
import app.gitpocket.core.database.RepoDao
import app.gitpocket.core.database.RepoEntity
import app.gitpocket.core.database.DraftDao
import app.gitpocket.core.datastore.SettingsRepository
import app.gitpocket.core.git.GitIdent
import app.gitpocket.core.git.GitRepo
import app.gitpocket.core.model.Branch
import app.gitpocket.core.model.CommitSummary
import app.gitpocket.core.model.FileChange
import app.gitpocket.core.model.FileDiff
import app.gitpocket.core.model.FileEntry
import app.gitpocket.core.model.Repo
import app.gitpocket.core.model.RepoId
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 仓库数据门面：Git 引擎 + Room + 设置。
 * 所有写操作经 [RepoLockRegistry] 串行化（同一仓库并发写会写坏 index）。
 */
class RepoRepository(
    private val repoRoot: File,
    private val repoDao: RepoDao,
    private val draftDao: DraftDao,
    private val settings: SettingsRepository,
    private val locks: RepoLockRegistry,
    private val dispatchers: AppDispatchers,
) {

    fun observeRepos(): Flow<List<Repo>> =
        repoDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun findRepo(id: RepoId): Repo? = withContext(dispatchers.io) {
        repoDao.findById(id.value)?.toModel()
    }

    /** 首次启动时创建一个真实的本机仓库，保证 UI 有内容且不依赖网络。 */
    suspend fun ensureSeedRepo() = withContext(dispatchers.io) {
        if (repoDao.observeAll().first().isNotEmpty()) return@withContext
        val directory = File(repoRoot, "demo")
        directory.mkdirs()

        val git = GitRepo.init(directory)
        try {
            File(directory, "README.md").writeText(
                "# GitPocket Demo\n\n" +
                    "这是一个由 GitPocket 自研 Git 引擎创建的真实仓库，不是 mock 数据。\n"
            )
            File(directory, "notes.md").writeText(
                "- 在手机上改一行\n- 提交\n- 推走\n"
            )
            val ident = ident()
            git.stageAll()
            git.commit("chore: 初始化示例仓库", ident)

            File(directory, "notes.md").appendText("- 支持 diff 查看\n")
            git.stageAll()
            git.commit("feat: 补充一条说明", ident)

            File(directory, "src").mkdirs()
            File(directory, "src/main.kt").writeText("fun main() {\n    println(\"hello gitpocket\")\n}\n")
            git.stageAll()
            git.commit("feat: 添加示例源码", ident)
        } finally {
            git.close()
        }

        repoDao.upsert(
            RepoEntity(
                id = UUID.randomUUID().toString(),
                accountId = "local",
                owner = "local",
                name = "demo",
                remoteUrl = "",
                localPath = directory.absolutePath,
                defaultBranch = "main",
                lastOpenedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun status(repoId: RepoId): List<FileChange> = read(repoId) { it.status() }

    suspend fun log(repoId: RepoId, limit: Int = 50): List<CommitSummary> =
        read(repoId) { it.log(limit) }

    suspend fun branches(repoId: RepoId): List<Branch> = read(repoId) { it.branches() }

    suspend fun entries(repoId: RepoId, directory: String): List<FileEntry> =
        read(repoId) { it.workTreeEntries(directory) }

    suspend fun readFile(repoId: RepoId, path: String): String? =
        read(repoId) { it.readWorkFile(path) }

    suspend fun diff(repoId: RepoId, path: String): FileDiff =
        read(repoId) { it.diffAgainstHead(path) }

    suspend fun draft(repoId: RepoId, path: String): String? = withContext(dispatchers.io) {
        draftDao.find(repoId.value, path)?.content
    }

    suspend fun saveFile(repoId: RepoId, path: String, content: String) = withContext(dispatchers.io) {
        locks.withLock(repoId.value) {
            val repo = openRepo(repoId) ?: return@withLock
            repo.use { it.writeWorkFile(path, content) }
            draftDao.upsert(
                DraftEntity(
                    repoId = repoId.value,
                    path = path,
                    baseOid = null,
                    content = content,
                    updatedAt = System.currentTimeMillis(),
                    status = "CLEAN",
                )
            )
        }
    }

    suspend fun stageAll(repoId: RepoId) = write(repoId) { it.stageAll() }

    suspend fun commit(repoId: RepoId, message: String): String = withContext(dispatchers.io) {
        locks.withLock(repoId.value) {
            val repo = openRepo(repoId) ?: throw IllegalStateException("仓库不存在：${repoId.value}")
            repo.use {
                val id = it.commit(message, ident())
                draftDao.deleteByRepo(repoId.value)
                id.hex
            }
        }
    }

    suspend fun touch(repoId: RepoId) = withContext(dispatchers.io) {
        repoDao.touch(repoId.value, System.currentTimeMillis())
    }

    private suspend fun <T> read(repoId: RepoId, block: (GitRepo) -> T): T =
        withContext(dispatchers.io) {
            val repo = openRepo(repoId) ?: throw IllegalStateException("仓库不存在：${repoId.value}")
            repo.use { block(it) }
        }

    private suspend fun <T> write(repoId: RepoId, block: (GitRepo) -> T): T =
        withContext(dispatchers.io) {
            locks.withLock(repoId.value) {
                val repo = openRepo(repoId) ?: throw IllegalStateException("仓库不存在：${repoId.value}")
                repo.use { block(it) }
            }
        }

    private suspend fun openRepo(repoId: RepoId): GitRepo? {
        val entity = repoDao.findById(repoId.value) ?: return null
        return GitRepo.open(File(entity.localPath))
    }

    private suspend fun ident(): GitIdent {
        val (name, email) = settings.authorIdentity()
        return GitIdent(
            name = name.ifBlank { "GitPocket User" },
            email = email.ifBlank { "user@gitpocket.app" },
            timeSeconds = System.currentTimeMillis() / 1000,
            tzOffset = "+0800",
        )
    }

    private fun RepoEntity.toModel(): Repo = Repo(
        id = RepoId(id),
        owner = owner,
        name = name,
        remoteUrl = remoteUrl.ifBlank { null },
        localPath = localPath,
        defaultBranch = defaultBranch,
        lastOpenedAt = lastOpenedAt,
        pinned = pinned,
        sizeBytes = sizeBytes,
    )
}
