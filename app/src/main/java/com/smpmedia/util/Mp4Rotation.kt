package com.smpmedia.util

import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * MP4/MOV dosyalarının GERÇEK dönüş açısını (0/90/180/270) okur.
 *
 * Neden ayrı bir yardımcı?
 * - Video oynatıcı (TextureView) de, ızgara kare üretimi de, tam boy
 *   önizleme de AYNI açıyı bilmek zorunda. Açı bilgisi tek yerde
 *   hesaplanmazsa bir yüzey düz, diğeri yan yatar (sahada görülen
 *   "ızgarada yan, açınca düz" sınıfı hatalar).
 * - Bu yardımcı, veri katmanından (UI'a bağlı olmadan) çağrılabilir.
 *
 * İki kaynak, sırayla:
 * 1. [MediaMetadataRetriever] — çoğu dosyada doğru.
 * 2. MP4 `tkhd` (track header) gösterim matrisi — Pixel 9/10 kamera
 *    videolarında ve Motion Photo kuyruklarında dönüş YALNIZCA buradadır;
 *    MMR bu durumda 0 raporlar. Matristen açı: `atan2(m[1], m[0])`
 *    (FFmpeg `mov_read_tkhd` ve ExoPlayer `MP4Util` ile aynı işaret).
 */
object Mp4Rotation {

    /** Doğrudan dosya yoluyla dönüş açısı. */
    fun fromFile(file: File): Int {
        val viaMmr = runCatching {
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(file.absolutePath)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
            }
        }.getOrDefault(0)
        val tkhd = tkhdRotation { length ->
            FileInputStream(file).channel.use { readHeadTail(it, length) }
        }
        val result = if (viaMmr != 0) normalize(viaMmr) else tkhd
        android.util.Log.d("SmpMedia", "Mp4Rotation: ${file.name} mmr=$viaMmr tkhd=$tkhd -> $result")
        return result
    }

    /** MediaMetadataRetriever'a dosya tanıtılamıyorsa (bozuk/trailer) null. */
    fun fromFileOrNull(file: File): Int? = runCatching { fromFile(file) }.getOrNull()

    /**
     * content:// adresi (MediaStore, FileProvider) için dönüş açısı.
     * Dosya yolu elde edilemediğinde kanal üzerinden tkhd taranır.
     */
    fun fromUri(context: android.content.Context, uri: android.net.Uri): Int {
        val viaMmr = runCatching {
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(context, uri)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
            }
        }.getOrDefault(0)
        val tkhd = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    tkhdRotation { len -> readHeadTail(ch, len) }
                }
            } ?: 0
        }.getOrDefault(0)
        val result = if (viaMmr != 0) normalize(viaMmr) else tkhd
        android.util.Log.d("SmpMedia", "Mp4Rotation: $uri mmr=$viaMmr tkhd=$tkhd -> $result")
        return result
    }

    private fun normalize(deg: Int): Int {
        val d = ((deg % 360) + 360) % 360
        return if (d == 90 || d == 180 || d == 270) d else 0
    }

    // ── tkhd gösterim matrisinden dönüş okuma (MMR yedeği) ──────────────
    //
    // MP4'te "tkhd" (track header) kutusunun sonundaki 3x3 matris gösterim
    // dönüşünü taşır: dik (0,±1,∓1,0) → 90/270. Kutu dosyanın başında (yeni
    // kayıtlar) ya da SONUNDA (moov sonda) olabilir; iki uç taranır.

    private const val TKHD_SCAN_BYTES = 4 * 1024 * 1024

    /** Kanalın başını ve sonunu okur; [maxLength] dosyadan küçükse iki parça döner. */
    private fun readHeadTail(ch: FileChannel, maxLength: Int): Pair<ByteArray, ByteArray?> {
        val size = ch.size().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (size <= 0) return ByteArray(0) to null
        if (size <= maxLength) return readAt(ch, 0L, size) to null
        return readAt(ch, 0L, maxLength) to readAt(ch, (size - maxLength).toLong(), maxLength)
    }

    private fun readAt(ch: FileChannel, pos: Long, len: Int): ByteArray {
        val buf = ByteBuffer.allocate(len)
        var p = pos
        while (buf.hasRemaining()) {
            val n = ch.read(buf, p)
            if (n <= 0) break
            p += n
        }
        return buf.array()
    }

    /** tkhd kutularını tarar; ilk sıfır-olmayan dönüş açısını döndürür. */
    private fun tkhdRotation(reader: (Int) -> Pair<ByteArray, ByteArray?>): Int = runCatching {
        val (head, tail) = reader(TKHD_SCAN_BYTES)
        scanTkhd(head) ?: tail?.let { scanTkhd(it) } ?: 0
    }.getOrDefault(0)

    private fun scanTkhd(data: ByteArray): Int? {
        val needle = byteArrayOf(0x74, 0x6B, 0x68, 0x64) // "tkhd"
        var from = 0
        while (true) {
            val i = indexOfBytes(data, needle, from)
            if (i < 0) return null
            from = i + 4
            // "tkhd" tip alanının hemen sonrası payload'ın başıdır:
            // version/flags(4) + zamanlar/trackId/rezerve/süre + rezerve(16) + matris(36).
            val version = data.getOrNull(i + 4)?.toInt() ?: continue
            if (version > 1) continue
            val fixed = if (version == 1) 8 + 8 + 4 + 4 + 8 else 4 + 4 + 4 + 4 + 4
            val m = i + 4 + 4 + fixed + 16
            if (m + 36 > data.size) continue
            val a = short16(data, m)
            val b = short16(data, m + 4)
            // Saçmalama koruması: gerçek bir gösterim matrisinde ilk sütun
            // birim uzunluktadır ((±1,0) veya (0,±1)); gürültü elenir.
            val unitFirstColumn =
                (kotlin.math.abs(kotlin.math.abs(a) - 1.0) < 0.1 && kotlin.math.abs(b) < 0.1) ||
                    (kotlin.math.abs(kotlin.math.abs(b) - 1.0) < 0.1 && kotlin.math.abs(a) < 0.1)
            if (!unitFirstColumn) continue
            val deg = Math.toDegrees(kotlin.math.atan2(b, a))
            val snapped = (Math.round(deg / 90.0).toInt() * 90).let { if (it < 0) it + 360 else it } % 360
            if (snapped != 0) return snapped
        }
    }

    private fun indexOfBytes(hay: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** 16.16 sabit nokta tam sayının tam kısmı (işaretli). */
    private fun short16(data: ByteArray, at: Int): Double {
        val v = ((data[at].toInt() and 0xFF shl 24) or
            (data[at + 1].toInt() and 0xFF shl 16) or
            (data[at + 2].toInt() and 0xFF shl 8) or
            (data[at + 3].toInt() and 0xFF))
        return v / 65536.0
    }
}

/** MediaMetadataRetriever.close() runCatching ile rahat kullanılsın diye. */
private fun <R> MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> R): R =
    try {
        block(this)
    } finally {
        runCatching { close() }
    }
