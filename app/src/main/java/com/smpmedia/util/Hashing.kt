package com.smpmedia.util

import com.smpmedia.data.model.PhotoItem
import java.security.MessageDigest

/**
 * Bir fotoğrafın "içerik anahtarı".
 * Dosya adı + boyut + son değiştirilme tarihi birlikte hash'lenir; böylece
 * aynı isimli ama değişmiş bir fotoğraf yeniden yedeklenir.
 */
fun photoContentKey(photo: PhotoItem): String {
    val raw = "${photo.displayName}|${photo.sizeBytes}|${photo.dateModified}"
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}
