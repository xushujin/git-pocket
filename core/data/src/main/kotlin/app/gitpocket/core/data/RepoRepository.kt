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
import app.gitpocket.core.git.transport.FetchResult
import app.gitpocket.core.git.transport.GitCredentials
import app.gitpocket.core.git.transport.GitFetcher
import app.gitpocket.core.git.transport.GitPusher
import app.gitpocket.core.git.transport.PushResult
import app.gitpocket.core.git.transport.SyncProgress
import app.gitpocket.core.git.transport.CloneProgress
import app.gitpocket.core.git.transport.GitCloner
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

    /**
     * 删除旧版本（≤ 1.0.0）自动预置的示例仓库。
     *
     * 新版本不再预置任何仓库：列表为空时直接引导用户克隆。
     * 老版本装过的用户升级后，local-demo 没有删除入口会一直占着列表，这里顺手清掉。
     */
    suspend fun removeLegacyDemo() = withContext(dispatchers.io) {
        val entity = repoDao.findById(SEED_REPO_ID) ?: return@withContext
        repoDao.delete(SEED_REPO_ID)

        // 只删自己创建在 repoRoot 下的目录，避免误删用户自己的数据
        runCatching {
            val directory = File(entity.localPath).canonicalFile
            val root = repoRoot.canonicalFile
            if (directory.path.startsWith(root.path + File.separator)) {
                directory.deleteRecursively()
            }
        }
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

        // 超时取自设置页，默认 10s / 60s
        val prefs = settings.snapshot.first()
        val result = GitCloner(
            credentials = credentials,
            connectTimeoutMs = prefs.connectTimeout * 1_000,
            readTimeoutMs = prefs.readTimeout * 1_000,
        ).clone(normalized, directory, onProgress)

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

    /** 拉取结果：区分"远端没变"和"快进成功"，UI 文案不一样。 */
    data class PullResult(
        val branch: String,
        val upToDate: Boolean,
        val fastForwarded: Boolean,
        val newObjects: Int,
    )

    /** fetch：只更新远端跟踪引用，不动本地分支与工作区。 */
    suspend fun fetch(
        repoId: RepoId,
        onProgress: (SyncProgress) -> Unit = {},
    ): FetchResult = withContext(dispatchers.io) {
        locks.withLock(repoId.value) {
            val entity = repoDao.findById(repoId.value)
                ?: throw IllegalStateException("仓库不存在：${repoId.value}")
            val repo = GitRepo.open(File(entity.localPath))
                ?: throw IllegalStateException("无法打开仓库：${entity.localPath}")
            repo.use {
                GitFetcher(credentialsFor(entity), timeouts().first, timeouts().second)
                    .fetch(entity.remoteUrl, it, onProgress = onProgress)
            }
        }
    }

    /** pull = fetch + 快进当前分支（不产生 merge commit）。 */
    suspend fun pull(
        repoId: RepoId,
        onProgress: (SyncProgress) -> Unit = {},
    ): PullResult = withContext(dispatchers.io) {
        locks.withLock(repoId.value) {
            val entity = repoDao.findById(repoId.value)
                ?: throw IllegalStateException("仓库不存在：${repoId.value}")
            val repo = GitRepo.open(File(entity.localPath))
                ?: throw IllegalStateException("无法打开仓库：${entity.localPath}")
            repo.use {
                val branch = it.refs.currentBranch() ?: entity.defaultBranch ?: "main"
                val (connectMs, readMs) = timeouts()
                val fetched = GitFetcher(credentialsFor(entity), connectMs, readMs)
                    .fetch(entity.remoteUrl, it, onProgress = onProgress)

                val remoteTip = fetched.updatedRefs["refs/remotes/origin/$branch"]
                    ?: return@use PullResult(branch, upToDate = true, fastForwarded = false, newObjects = 0)

                val moved = it.fastForward(branch, remoteTip)
                PullResult(
                    branch = branch,
                    upToDate = !moved && it.refs.read("refs/heads/$branch") == remoteTip,
                    fastForwarded = moved,
                    newObjects = fetched.newObjects,
                )
            }
        }
    }

    /** push：推送当前分支；非快进会抛 [app.gitpocket.core.git.GitException.NonFastForward]。 */
    suspend fun push(
        repoId: RepoId,
        onProgress: (SyncProgress) -> Unit = {},
    ): PushResult = withContext(dispatchers.io) {
        locks.withLock(repoId.value) {
            val entity = repoDao.findById(repoId.value)
                ?: throw IllegalStateException("仓库不存在：${repoId.value}")
            val repo = GitRepo.open(File(entity.localPath))
                ?: throw IllegalStateException("无法打开仓库：${entity.localPath}")
            repo.use {
                val branch = it.refs.currentBranch() ?: entity.defaultBranch ?: "main"
                val (connectMs, readMs) = timeouts()
                GitPusher(credentialsFor(entity), connectMs, readMs)
                    .push(entity.remoteUrl, it, branch, onProgress)
            }
        }
    }

    /** 打开仓库时是否自动 fetch（设置项，默认开）。 */
    suspend fun autoFetchEnabled(): Boolean = settings.snapshot.first().autoFetch

    private suspend fun credentialsFor(entity: RepoEntity): GitCredentials? {
        val account = accountDao.findById(entity.accountId) ?: return null
        if (account.tokenRef.isBlank()) return null
        val token = tokenStore.load(account.tokenRef) ?: return null
        return GitCredentials(account.username.ifBlank { "x-access-token" }, token)
    }

    private suspend fun timeouts(): Pair<Int, Int> {
        val prefs = settings.snapshot.first()
        return prefs.connectTimeout * 1_000 to prefs.readTimeout * 1_000
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

    /**
     * 撤销单个文件的修改（恢复到 HEAD，未跟踪文件则删除）。
     * 同时清掉该文件的草稿，否则编辑器下次打开还会显示被撤销的内容。
     */
    suspend fun discard(repoId: RepoId, path: String): Boolean {
        val discarded = write(repoId) { it.discardWorkFile(path) }
        if (discarded) {
            withContext(dispatchers.io) { draftDao.delete(repoId.value, path) }
        }
        return discarded
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
    }

}
