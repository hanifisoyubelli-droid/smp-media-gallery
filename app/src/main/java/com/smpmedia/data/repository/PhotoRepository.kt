package com.smpmedia.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.smpmedia.data.model.LocalFolder
import com.smpmedia.data.model.PhotoItem
import com.smpmedia.di.IoDispatcher
import com.smpmedia.util.hasImagePermission
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaStore üzerinden telefon fotoğraflarını okur.
 * Sıralama DATE_TAKEN'e göre yapılır; yoksa DATE_MODIFIED kullanılır.
 */
@Singleton
class PhotoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contentResolver: ContentResolver,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    fun hasImagePermission(): Boolean = hasImagePermission(context)

    /**
     * MediaStore değişikliklerini dinleyen soğuk akış.
     * Galeriye fotoğraf eklendiğinde/silindiğinde otomatik yenilenir.
     */
    fun observePhotos(): Flow<List<PhotoItem>> = callbackFlow {
        if (!hasImagePermission()) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val signals = Channel<Unit>(Channel.CONFLATED)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                signals.trySend(Unit)
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
        // Video eklenince/silinince de yenilensin.
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )

        val worker = launch {
            signals.trySend(Unit)
            for (ignored in signals) {
                val photos = withContext(ioDispatcher) { queryPhotos() }
                trySend(photos)
            }
        }

        awaitClose {
            contentResolver.unregisterContentObserver(observer)
            worker.cancel()
            signals.close()
        }
    }.conflate().distinctUntilChanged()

    /** Eşleştirme ekranı için telefon klasörleri (bucket'lar, yalnızca resimler). */
    suspend fun getLocalFolders(): List<LocalFolder> = withContext(ioDispatcher) {
        queryPhotos(includeVideos = false)
            .groupBy { it.bucketId }
            .map { (bucketId, photos) ->
                LocalFolder(
                    bucketId = bucketId,
                    name = photos.first().bucketName,
                    photoCount = photos.size,
                    coverUri = photos.first().uri
                )
            }
            .sortedWith(
                compareByDescending<LocalFolder> { it.photoCount }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
    }

    /** Belirli bir bucket'taki RESİMLER (yedekleme video taşımaz). */
    suspend fun getPhotosForBucket(bucketId: String): List<PhotoItem> =
        withContext(ioDispatcher) { queryPhotos(bucketId, includeVideos = false) }

    private fun queryPhotos(bucketId: String? = null, includeVideos: Boolean = true): List<PhotoItem> {
        if (!hasImagePermission()) return emptyList()

        // RESİM + VİDEO birlikte sorgulanır (Google Photos gibi tek akış).
        // Medya türü sütunu sayesinde downstream (ızgara rozeti, hareketli
        // önizleme) türü ayırt eder; YEDEKLEME ise yalnızca resimleri alır
        // (BackupRepository'de süzülür).
        val projection = mutableListOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            // Video rozetindeki "1:23" süresi buradan gelir (fotoğraflarda 0).
            MediaStore.Files.FileColumns.DURATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Files.FileColumns.RELATIVE_PATH)
        } else {
            @Suppress("DEPRECATION")
            projection.add(MediaStore.Files.FileColumns.DATA)
        }

        val typeClause = "((${MediaStore.Files.FileColumns.MEDIA_TYPE} = " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}) OR (" +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} = " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}))"
        val selection = if (bucketId != null) {
            "$typeClause AND ${MediaStore.Files.FileColumns.BUCKET_ID} = ?"
        } else {
            typeClause
        }
        val selectionArgs = bucketId?.let { arrayOf(it) }
        // Eşit tarihlerde (aynı saniyeye yazılmış toplu fotoğraflar) kursor
        // sırası tanımsızdır; _ID kırılımı her sorguda AYNI sırayı garanti eder.
        val sortOrder = MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC, " +
            MediaStore.Files.FileColumns._ID + " DESC"

        val result = ArrayList<PhotoItem>()
        try {
            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection.toTypedArray(),
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeIndex =
                    cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val takenIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
                val modifiedIndex =
                    cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val bucketIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
                val bucketNameIndex =
                    cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val durationIndex = cursor.getColumnIndexOrThrow(
                    MediaStore.Files.FileColumns.DURATION
                )
                val pathIndex = cursor.getColumnIndexOrThrow(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Files.FileColumns.RELATIVE_PATH
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Files.FileColumns.DATA
                    }
                )

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val isVideo = cursor.getInt(typeIndex) ==
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    if (isVideo && !includeVideos) continue
                    val displayName = cursor.getString(nameIndex)
                        ?.takeIf { it.isNotBlank() }
                        ?: if (isVideo) "VID_$id.mp4" else "IMG_$id.jpg"

                    val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getString(pathIndex) ?: ""
                    } else {
                        @Suppress("DEPRECATION")
                        val absolute = cursor.getString(pathIndex) ?: ""
                        absolute.substringBeforeLast('/', "")
                    }

                    result.add(
                        PhotoItem(
                            id = id,
                            uri = ContentUris.withAppendedId(
                                // Eski anahtar biçimi korunur (yedekleme kayıtları
                                // ve küçük resim önbellekleri URI metnine bağlıdır):
                                // resimler images/media, videolar video/media altında.
                                if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id
                            ),
                            displayName = displayName,
                            dateTaken = normalizeMillis(cursor.getLong(takenIndex)),
                            dateModified = normalizeMillis(cursor.getLong(modifiedIndex)),
                            sizeBytes = cursor.getLong(sizeIndex),
                            bucketId = cursor.getLong(bucketIndex).toString(),
                            bucketName = cursor.getString(bucketNameIndex)
                                ?.takeIf { it.isNotBlank() } ?: "Diğer",
                            mimeType = cursor.getString(mimeIndex)
                                ?: if (isVideo) "video/mp4" else "image/*",
                            relativePath = relativePath,
                            durationMs = if (isVideo) cursor.getLong(durationIndex) else 0L
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        }

        result.sortWith(compareByDescending { it.sortDate })
        return result
    }

    /**
     * DATE_MODIFIED saniye, DATE_TAKEN ise milisaniye olarak gelebilir.
     * Tüm değerleri milisaniyeye normalize ediyoruz.
     */
    private fun normalizeMillis(value: Long): Long = when {
        value <= 0L -> 0L
        value < 10_000_000_000L -> value * 1000L
        else -> value
    }
}
