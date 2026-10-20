package app.gitpocket.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "account",
    indices = [Index(value = ["host", "username"], unique = true)],
)
data class AccountEntity(
    @PrimaryKey val id: String,
    val host: String,
    val username: String,
    /** 指向 Keystore 密文文件，绝不存明文 token。 */
    val tokenRef: String,
    val createdAt: Long,
    val lastUsedAt: Long?,
)

@Entity(
    tableName = "repo",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("accountId"), Index("lastOpenedAt")],
)
data class RepoEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val owner: String,
    val name: String,
    val remoteUrl: String,
    val localPath: String,
    /** v2 新增：远端默认分支，迁移 1→2 加入。 */
    val defaultBranch: String? = null,
    val lastOpenedAt: Long,
    val sizeBytes: Long = 0,
    val pinned: Boolean = false,
)

@Entity(
    tableName = "ref_snapshot",
    primaryKeys = ["repoId", "name"],
    foreignKeys = [
        ForeignKey(
            entity = RepoEntity::class,
            parentColumns = ["id"],
            childColumns = ["repoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RefSnapshotEntity(
    val repoId: String,
    val name: String,
    val oid: String,
    val isRemote: Boolean,
    val updatedAt: Long,
)

@Entity(
    tableName = "commit_cache",
    primaryKeys = ["repoId", "oid"],
    foreignKeys = [
        ForeignKey(
            entity = RepoEntity::class,
            parentColumns = ["id"],
            childColumns = ["repoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["repoId", "authoredAt"])],
)
data class CommitCacheEntity(
    val repoId: String,
    val oid: String,
    val parents: String,
    val authorName: String,
    val authoredAt: Long,
    val subject: String,
)

@Entity(
    tableName = "draft",
    primaryKeys = ["repoId", "path"],
    foreignKeys = [
        ForeignKey(
            entity = RepoEntity::class,
            parentColumns = ["id"],
            childColumns = ["repoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("updatedAt")],
)
data class DraftEntity(
    val repoId: String,
    val path: String,
    /** 草稿基于的 blob；新建文件为 null。 */
    val baseOid: String?,
    val content: String,
    val updatedAt: Long,
    /** CLEAN | DIRTY | CONFLICT | STALE */
    val status: String,
)

@Entity(tableName = "op_log", indices = [Index("startedAt")])
data class OpLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "repoId") val repoId: String?,
    val type: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val result: String?,
    val message: String?,
)
