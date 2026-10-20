package app.gitpocket.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        RepoEntity::class,
        RefSnapshotEntity::class,
        CommitCacheEntity::class,
        DraftEntity::class,
        OpLogEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class GitPocketDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun repoDao(): RepoDao
    abstract fun draftDao(): DraftDao
    abstract fun commitCacheDao(): CommitCacheDao
    abstract fun opLogDao(): OpLogDao
}

object DatabaseFactory {

    const val NAME = "gitpocket.db"

    /**
     * 注意：绝不调用 fallbackToDestructiveMigration。
     * 迁移缺失时宁可抛错（由上层转成"安全模式"），也不能删用户数据。
     */
    fun create(context: Context): GitPocketDatabase =
        Room.databaseBuilder(context, GitPocketDatabase::class.java, NAME)
            .addMigrations(*Migrations.ALL)
            .build()
}
