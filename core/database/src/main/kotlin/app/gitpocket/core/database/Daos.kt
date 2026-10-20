package app.gitpocket.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    @Query("SELECT * FROM account ORDER BY lastUsedAt DESC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM account WHERE host = :host AND username = :username LIMIT 1")
    suspend fun findByHostAndUser(host: String, username: String): AccountEntity?

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun findById(id: String): AccountEntity?

    @Query("DELETE FROM account WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface RepoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(repo: RepoEntity)

    @Query("SELECT * FROM repo ORDER BY pinned DESC, lastOpenedAt DESC")
    fun observeAll(): Flow<List<RepoEntity>>

    @Query("SELECT * FROM repo WHERE id = :id")
    suspend fun findById(id: String): RepoEntity?

    @Query("UPDATE repo SET lastOpenedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: String, timestamp: Long)

    @Query("UPDATE repo SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("DELETE FROM repo WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface DraftDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: DraftEntity)

    @Query("SELECT * FROM draft WHERE repoId = :repoId AND path = :path LIMIT 1")
    suspend fun find(repoId: String, path: String): DraftEntity?

    @Query("SELECT * FROM draft WHERE repoId = :repoId ORDER BY updatedAt DESC")
    suspend fun listByRepo(repoId: String): List<DraftEntity>

    @Query("SELECT COUNT(*) FROM draft WHERE repoId = :repoId")
    fun observeCount(repoId: String): Flow<Int>

    @Query("DELETE FROM draft WHERE repoId = :repoId AND path = :path")
    suspend fun delete(repoId: String, path: String)

    @Query("DELETE FROM draft WHERE repoId = :repoId")
    suspend fun deleteByRepo(repoId: String)
}

@Dao
interface CommitCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(commits: List<CommitCacheEntity>)

    @Query("SELECT * FROM commit_cache WHERE repoId = :repoId ORDER BY authoredAt DESC LIMIT :limit")
    suspend fun recent(repoId: String, limit: Int): List<CommitCacheEntity>

    @Query("DELETE FROM commit_cache WHERE repoId = :repoId")
    suspend fun clear(repoId: String)
}

@Dao
interface OpLogDao {

    @Insert
    suspend fun insert(log: OpLogEntity)

    @Query("SELECT * FROM op_log ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<OpLogEntity>

    @Query("DELETE FROM op_log WHERE startedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}
