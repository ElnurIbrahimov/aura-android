package com.aura.proactive

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProactiveMigration4To5Test {
    @Test
    fun `migration 4 to 5 adds correlationTag`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback = object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(4) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE proactive_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, eventType TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, timestamp INTEGER NOT NULL, payload TEXT NOT NULL DEFAULT '')")
            }
            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("proactive-migrate-4-5")
                .callback(callback)
                .build()
        )
        openHelper.writableDatabase.use { db ->
            db.execSQL("INSERT INTO proactive_events (eventType,title,body,timestamp) VALUES ('x','t','b',1)")
        }
        ProactiveEventModule.MIGRATION_4_5.migrate(openHelper.writableDatabase)
        openHelper.writableDatabase.use { db ->
            val cursor = db.query("SELECT correlationTag FROM proactive_events")
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("", cursor.getString(0))
            cursor.close()
        }
        openHelper.close()
    }
}
