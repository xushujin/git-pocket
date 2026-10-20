package app.gitpocket.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 迁移注册表。新增迁移时：
 * 1. 在 migration/ 下新建 MigrationNToM.kt；
 * 2. 加入 ALL（顺序无关，但必须覆盖所有跨版本路径）；
 * 3. 在 androidTest 里补 MigrationTest；
 * 4. 同步更新 docs/数据迁移手册.md。
 */
object Migrations {

    /**
     * v1 -> v2：repo 表新增 defaultBranch（可空）。
     * 注意：SQLite 的 ALTER TABLE 不能直接加 NOT NULL 且无默认值的列，
     * 因此这里用可空列 + 回填，避免迁移失败。
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE repo ADD COLUMN defaultBranch TEXT")
            db.execSQL("UPDATE repo SET defaultBranch = 'main' WHERE defaultBranch IS NULL")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
