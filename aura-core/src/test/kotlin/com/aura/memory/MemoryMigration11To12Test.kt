package com.aura.memory

import android.content.Context
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MemoryMigration11To12Test {
    @Test
    fun `migrate 11 to 12 adds scope column`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(11) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""
                            CREATE TABLE memories (
                                id TEXT NOT NULL PRIMARY KEY,
                                content TEXT NOT NULL,
                                source TEXT NOT NULL,
                                category TEXT NOT NULL,
                                importance REAL NOT NULL DEFAULT 0.5,
                                embedding BLOB,
                                createdAt INTEGER NOT NULL DEFAULT 0,
                                accessedAt INTEGER NOT NULL DEFAULT 0,
                                accessCount INTEGER NOT NULL DEFAULT 0,
                                decayScore REAL NOT NULL DEFAULT 1.0,
                                tags TEXT NOT NULL DEFAULT '',
                                metadata TEXT NOT NULL DEFAULT '',
                                sourceConversationId TEXT NOT NULL DEFAULT '',
                                sourceTurnTimestamp INTEGER NOT NULL DEFAULT 0,
                                embeddingModel TEXT,
                                embeddingVersion INTEGER NOT NULL DEFAULT 0
                            )
                        """.trimIndent())
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val db = helper.writableDatabase
        db.execSQL("INSERT INTO memories (id, content, source, category) VALUES ('m1','c','user','fact')")
        MemoryMigrations.MIGRATION_11_12.migrate(db)
        val cursor = db.query("SELECT scope FROM memories WHERE id = ?", arrayOf("m1"))
        assertTrue(cursor.moveToFirst())
        cursor.close()
        db.close()
    }
}
