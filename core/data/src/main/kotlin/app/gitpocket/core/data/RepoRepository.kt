package app.gitpocket.core.data

import app.gitpocket.core.common.AppDispatchers
import app.gitpocket.core.common.RepoLockRegistry
import app.gitpocket.core.database.AccountDao
import app.gitpocket.core.database.AccountEntity
import app.gitpocket.core.database.DraftEntity
import app.gitpocket.core.database.RepoDao
import app.gitpocket.core.database.RepoEntity
import app.gitpocket.core.database.DraftDao
import app.gitpocket.core.datastore.SettingsRepository
import app.gitpocket.core.security.KeystoreTokenStore
import app.gitpocket.core.git.GitIdent
import app.gitpocket.core.git.GitException
import app.gitpocket.core.git.GitRepo
import app.gitpocket.core.git.transport.CloneProgress
import app.gitpocket.core.git.transport.GitCloner
import app.gitpocket.core.git.transport.GitCredentials
import app.gitpocket.core.model.Branch
import app.gitpocket.core.model.CommitSummary
import app.gitpocket.core.model.FileChange
import app.gitpocket.core.model.FileDiff
import app.gitpocket.core.model.FileEntry
import app.gitpocket.core.model.Repo
import app.gitpocket.core.model.RepoId
import java.io.File
import java.net.URI
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
    private val accountDao: AccountDao,
    private val draftDao: DraftDao,
    private val settings: SettingsRepository,
    private val tokenStore: KeystoreTokenStore,
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

        // repo.accountId 有外键约束，必须先落一条本地账号，否则会抛
        // SQLITE_CONSTRAINT_FOREIGNKEY(787)。本地仓库不需要真实账号。
        val now = System.currentTimeMillis()
        accountDao.upsert(
            AccountEntity(
                id = LOCAL_ACCOUNT_ID,
                host = "local",
                username = "local",
                tokenRef = "",
                createdAt = now,
                lastUsedAt = now,
            )
        )

        val directory = File(repoRoot, SEED_REPO_DIR)
        // 上一次启动可能已经建过仓库（例如后续插入失败），这里只补数据不重复提交
        val alreadyInitialized = File(directory, ".git").isDirectory
        if (!alreadyInitialized) {
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
        }

        repoDao.upsert(
            RepoEntity(
                id = SEED_REPO_ID,
                accountId = LOCAL_ACCOUNT_ID,
                owner = "local",
                name = "demo",
                remoteUrl = "",
                localPath = directory.absolutePath,
                defaultBranch = "main",
                lastOpenedAt = now,
            )
        )
    }

    /**
     * 通过 HTTPS 克隆远端仓库到应用私有目录，并登记到数据库。
     * token 为空表示公开仓库；非空时以 PAT 方式认证并加密保存。
     */
    suspend fun clone(
        url: String,
        username: String,
        token: String,
        onProgress: (CloneProgress) -> Unit,
    ): Repo = withContext(dispatchers.io) {
        val normalized = url.trim().trimEnd('/')
        if (!normalized.startsWith("https://")) {
            throw GitException.UnsupportedFeature("仅支持 https:// 地址")
        }
        val uri = URI(normalized)
        val host = uri.host ?: throw GitException.UnsupportedFeature("无法解析仓库地址")
        val segments = uri.path.trim('/').split('/').filter { it.isNotBlank() }
        val name = segments.lastOrNull()?.removeSuffix(".git")?.ifBlank { "repo" } ?: "repo"
        val owner = segments.getOrNull(segments.size - 2) ?: host

        val repoId = UUID.randomUUID().toString()
        val directory = File(repoRoot, "$owner-$name-${repoId.take(8)}")
        val credentials = token.takeIf { it.isNotBlank() }
            ?.let { GitCredentials(username.ifBlank { "x-access-token" }, it) }

        val result = GitCloner(credentials).clone(normalized, directory, onProgress)

        val accountId = "acct-${host}-${username.ifBlank { "anonymous" }}"
        val now = System.currentTimeMillis()
        accountDao.upsert(
            AccountEntity(
                id = accountId,
                host = host,
                username = username.ifBlank { "anonymous" },
                tokenRef = if (credentials != null) accountId else "",
                createdAt = now,
                lastUsedAt = now,
            )
        )
        if (credentials != null) {
            tokenStore.save(accountId, token)
        }

        repoDao.upsert(
            RepoEntity(
                id = repoId,
                accountId = accountId,
                owner = owner,
                name = name,
                remoteUrl = normalized,
                localPath = result.workTree.absolutePath,
                defaultBranch = result.defaultBranch,
                lastOpenedAt = now,
                sizeBytes = directorySize(directory),
            )
        )
        repoDao.findById(repoId)!!.toModel()
    }

    private fun directorySize(directory: File): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

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

    private companion object {
        const val LOCAL_ACCOUNT_ID = "local"
        const val SEED_REPO_ID = "local-demo"
        const val SEED_REPO_DIR = "demo"
    }

}
