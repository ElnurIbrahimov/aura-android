package com.aura.media

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A record that Aura made something, and where it went.
 *
 * Generated images used to be written into `cacheDir/generated_images/` and recorded
 * nowhere. Android reclaims `cacheDir` under storage pressure and Settings → Clear cache
 * empties it outright, so every image was on borrowed time — and with no row anywhere,
 * nothing could list them, nothing noticed when they vanished, and there was nothing for a
 * Library to show.
 *
 * `kind` is a string rather than an enum because audio and video will land in this table
 * next and a stored enum ordinal is the shape that breaks when a value is inserted in the
 * middle. `image` is the only value written today.
 */
@Entity(
    tableName = "generated_media",
    indices = [Index("createdAt"), Index("kind")],
)
data class GeneratedMediaEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    /** `image` today; `audio` and `video` are the reason this is not an enum. */
    val kind: String,
    /** What was asked for. The only description of an image the user will recognise. */
    val prompt: String,
    val mimeType: String = "image/png",
    /**
     * Where it is now: a `file://` under `filesDir` for anything generated as bytes, or the
     * provider's own URL when that is all the provider returned.
     *
     * The two are not equally durable and the Library says so. A provider URL is someone
     * else's uptime; a file under `filesDir` is ours and survives Clear cache.
     */
    val storageUri: String,
    /** Set when [storageUri] is remote, so a later pass can localise it. Blank otherwise. */
    val remoteUrl: String = "",
    val byteSize: Long = 0L,
    /** The conversation it was made in, so the Library can open it in context. */
    val conversationId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** True when the bytes are ours and cannot expire out from under the Library. */
    val isLocal: Boolean get() = storageUri.startsWith("file://") || storageUri.startsWith("/")
}

@Dao
interface GeneratedMediaDao {

    @Upsert
    suspend fun upsert(row: GeneratedMediaEntity)

    @Query("SELECT * FROM generated_media ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 200): List<GeneratedMediaEntity>

    @Query("SELECT * FROM generated_media ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<GeneratedMediaEntity>>

    @Query("SELECT * FROM generated_media WHERE id = :id")
    suspend fun byId(id: String): GeneratedMediaEntity?

    @Query("DELETE FROM generated_media WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM generated_media")
    suspend fun count(): Int

    /** For backup export, mirroring the other tables' `allForBackup` convention. */
    @Query("SELECT * FROM generated_media ORDER BY createdAt ASC")
    suspend fun allForBackup(): List<GeneratedMediaEntity>
}

/**
 * Records what Aura generated, and never fails the thing that generated it.
 *
 * Every write is best-effort by design: the image is the product and the row is
 * bookkeeping, so a database that cannot be written must not turn a successful generation
 * into an error the user sees. The failure is logged, not raised.
 */
@Singleton
class GeneratedMediaStore @Inject constructor(
    private val dao: GeneratedMediaDao,
) {

    /** @return the row if it was written, or null if recording failed. */
    suspend fun record(
        kind: String,
        prompt: String,
        storageUri: String,
        mimeType: String = "image/png",
        remoteUrl: String = "",
        byteSize: Long = 0L,
        conversationId: String = "",
    ): GeneratedMediaEntity? {
        val row = GeneratedMediaEntity(
            kind = kind,
            prompt = prompt,
            storageUri = storageUri,
            mimeType = mimeType,
            remoteUrl = remoteUrl,
            byteSize = byteSize,
            conversationId = conversationId,
        )
        return runCatching { dao.upsert(row); row }
            .onFailure {
                android.util.Log.w("GeneratedMediaStore", "could not record $kind: ${it.message}", it)
            }
            .getOrNull()
    }

    suspend fun recent(limit: Int = 200): List<GeneratedMediaEntity> =
        runCatching { dao.recent(limit) }
            .onFailure { android.util.Log.w("GeneratedMediaStore", "recent failed: ${it.message}", it) }
            .getOrDefault(emptyList())

    fun observeAll(): Flow<List<GeneratedMediaEntity>> = dao.observeAll()

    suspend fun delete(id: String) {
        runCatching { dao.delete(id) }
            .onFailure { android.util.Log.w("GeneratedMediaStore", "delete failed: ${it.message}", it) }
    }
}
