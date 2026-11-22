package com.smpmedia.ui.components

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.smpmedia.data.image.MediaCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Hücredeki içerik video mu? */
fun isVideoMime(mimeType: String?): Boolean = mimeType?.startsWith("video/") == true

/**
 * Video süresini Google Photos biçiminde yazar: "1:23" veya saatliyse
 * "1:02:45". Bilinmeyen süre (0) boş dize döner — rozet simgesiz kalır.
 */
fun formatMediaDuration(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSec = durationMs / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Google Photos tarzı tür rozeti: sol altta siyahımsı kapsül içinde beyaz
 * simge; videoysa yanına süre eklenir (süre bilinmiyorsa simge yeter —
 * SMP videolarında süre dosya inmeden öğrenilemez).
 */
@Composable
fun GpMotionBadge(
    isVideo: Boolean,
    durationMs: Long,
    modifier: Modifier = Modifier
) {
    val label = if (isVideo) formatMediaDuration(durationMs) else ""
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isVideo) Icons.Filled.PlayArrow else Icons.Filled.MotionPhotosOn,
            contentDescription = if (isVideo) "Video" else "Canlı fotoğraf",
            tint = Color.White,
            modifier = Modifier.size(12.dp)
        )
        if (label.isNotEmpty()) {
            Spacer(Modifier.width(3.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 10.sp,
                maxLines = 1
            )
        }
    }
}

/**
 * Google/Pixel hareketli fotoğraf (Motion / "Live Photo") dosya adı deseni:
 * `PXL_20261025_173708410.MP.jpg` gibi. Kesin kanıt XMP içindeki
 * MicroVideoOffset'tur; isim yalnızca ucuz ön elemedir.
 */
fun isLivePhotoName(displayName: String): Boolean =
    displayName.contains(".mp.", ignoreCase = true)

/**
 * Motion Photo dosyalarının SONUNA ekli MP4 trailer'ını dosyadan ayıklar.
 *
 * Google Motion Photo = JPEG + MicroVideoOffset baytı. XMP içindeki
 * `MicroVideoOffset="N"` değerine göre dosyanın son N baytı kendi kendine
 * yeten bir MP4'tür; onu ayrı dosyaya yazıp MediaPlayer ile oynatabiliriz.
 * Sonuç diskte önbelleklenir (aynı fotoğraf için bir daha çıkarılmaz).
 */
object MotionPhotoExtractor {

    private const val XMP_WINDOW_BYTES = 128 * 1024
    // NOT: ConcurrentHashMap null DEĞER kabul etmez; başarısız çıkarmalarda
    // haritaya hiçbir şey yazılmaz (null put NPE'si uygulamayı çökertiyordu).
    private val memoryCache = java.util.concurrent.ConcurrentHashMap<String, File>()

    /** JPEG başlangıcındaki XMP bloğundan video offset'ini bulur. */
    private val offsetRegex = Regex("MicroVideoOffset=\"(\\d+)\"")

    /**
     * Video uzunluğunu EITHER eski EITHER yeni Google Motion Photo XMP
     * şemasından okur:
     * - Eski (Pixel 8'e kadar): MicroVideoOffset="N" — dosyanın SON N baytı.
     * - Yeni (Pixel 9/10, Android 15/16): MicroVideoOffset YOK; bunun yerine
     *   Container:Directory içindeki video/mp4 öğesinin Item:Length değeri
     *   video uzunluğunu (Item:Padding dahil) taşır. Ekim 2026'dan kalma
     *   PXL dosyalarında bu format görüldü — eski regex bu dosyalarda
     *   hiçbir şey bulamıyor, hareket hiç oynatılamıyordu.
     */
    private fun videoLengthFromXmp(xmp: String): Long? {
        offsetRegex.find(xmp)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
        val itemTag = Regex("<Container:Item\\b[^>]*>")
        val lengthAttr = Regex("Item:Length=\"(\\d+)\"")
        val paddingAttr = Regex("Item:Padding=\"(\\d+)\"")
        for (tag in itemTag.findAll(xmp)) {
            val text = tag.value
            if (!text.contains("video/mp4")) continue
            val length = lengthAttr.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            if (length <= 0) continue
            val padding = paddingAttr.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            return (length - padding).takeIf { it > 0 } ?: length
        }
        return null
    }

    suspend fun videoFor(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        val key = uri.toString()
        memoryCache[key]?.let { cached -> if (cached.exists()) return@withContext cached }
        runCatching {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: return@runCatching null
            // FileInputStream fd'yi kapanışta serbest bırakır; aynı fd'yi
            // ikinci kez kapatmak yanlış bir tanımı kapatabilir — PFD ayrıca
            // kapatılmaz.
            FileInputStream(pfd.fileDescriptor).use { input ->
                val total = pfd.statSize
                if (total <= 0) return@use null
                // XMP genelde JPEG başındadır; ilk 128 KB yeter.
                val buffer = ByteArray(minOf(XMP_WINDOW_BYTES, total.toInt()))
                DataInputStream(input).readFully(buffer)
                val xmpText = String(buffer, Charsets.ISO_8859_1)
                // Hem eski (MicroVideoOffset) hem yeni (Container Item:Length)
                // motion foto formatlarını tanıyan tek okuyucu.
                val offset = videoLengthFromXmp(xmpText)
                if (offset == null || offset <= 0 || offset > total - 1024) return@use null
                val videoStart = total - offset

                val dir = MediaCache.motion(context)
                val hash = MessageDigest.getInstance("MD5")
                    .digest(key.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val dest = File(dir, "$hash.mp4")
                if (!dest.exists() || dest.length() != offset) {
                    val tmp = File(dir, "$hash.tmp")
                    // InputStream.skip garanti vermez; döngüyle ilerle.
                    var remaining = videoStart
                    while (remaining > 0) {
                        val skipped = input.skip(remaining)
                        if (skipped <= 0) break
                        remaining -= skipped
                    }
                    tmp.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                    tmp.renameTo(dest)
                }
                dest.takeIf { it.length() > 0 }
            }
        }.onFailure {
            Log.d("SmpMedia", "Motion video çıkarılamadı: $uri: ${it.message}")
        }.getOrNull().also { result ->
            // Yalnızca başarı önbelleklenir; hücresinin bir daha denemesi için
            // başarısız anahtar haritadan düşürülür.
            if (result != null) memoryCache[key] = result else memoryCache.remove(key)
        }
    }
}

/**
 * Izgara hücresi içinde sessiz-döngülü hareketli önizleme (Google Photos
 * uzun basma önizlemesi gibi). [videoFile] doluysa ondan (Motion Photo),
 * değilse [uri] üzerinden (video dosyası) oynatır.
 *
 * SurfaceView yüzeyi hücreyi doldurur; kadraj farkı küçük önizlemede
 * sorun değildir (Google Photos önizlemesi de kırpılmıştır).
 */
@Composable
fun VideoLivePreview(
    uri: Uri?,
    videoFile: File?,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val textureView = remember { TextureView(context).apply { isOpaque = true } }
    var videoSize by remember(uri, videoFile) { mutableStateOf<Pair<Int, Int>?>(null) }
    var rotation by remember(uri, videoFile) { mutableIntStateOf(0) }

    fun applyTransform(tv: TextureView) {
        val size = videoSize ?: return
        if (tv.width <= 0 || tv.height <= 0) return
        // Hücre önizlemesi: Google Photos gibi DOLDURUR (kenarlardan kırpar),
        // germez/döverek yaymaz.
        tv.setTransform(VideoFit.fitMatrix(tv.width, tv.height, size.first, size.second, rotation, crop = true))
    }

    LaunchedEffect(uri, videoFile) {
        rotation = withContext(Dispatchers.IO) {
            when {
                videoFile != null -> VideoFit.readRotation(videoFile)
                uri != null -> VideoFit.readRotation(context, uri)
                else -> 0
            }
        }
        applyTransform(textureView)
    }

    DisposableEffect(textureView, uri, videoFile) {
        var player: MediaPlayer? = null
        var started = false

        fun releasePlayer() {
            runCatching { player?.release() }
            player = null
        }

        fun startPlayback(surface: Surface) {
            releasePlayer()
            player = MediaPlayer().apply {
                isLooping = true
                setVolume(0f, 0f) // önizleme sessizdir
                setScreenOnWhilePlaying(false)
                setSurface(surface)
                setOnVideoSizeChangedListener { mp, _, _ ->
                    videoSize = mp.videoWidth to mp.videoHeight
                    applyTransform(textureView)
                }
                setOnPreparedListener { mp ->
                    videoSize = (mp.videoWidth to mp.videoHeight).takeIf {
                        it.first > 0 && it.second > 0
                    }
                    applyTransform(textureView)
                    runCatching { mp.start() }
                }
                setOnErrorListener { _, _, _ -> true } // hatalar yutulur
                runCatching {
                    when {
                        videoFile != null -> setDataSource(videoFile.absolutePath)
                        uri != null -> setDataSource(context, uri)
                        else -> return
                    }
                    prepareAsync()
                }.onFailure { releasePlayer() }
            }
        }

        val listener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                st: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                if (!started) {
                    started = true
                    startPlayback(Surface(st))
                }
            }

            override fun onSurfaceTextureSizeChanged(
                st: SurfaceTexture,
                width: Int,
                height: Int
            ) = applyTransform(textureView)

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releasePlayer()
                started = false
                return true
            }
        }
        textureView.surfaceTextureListener = listener
        textureView.surfaceTexture?.let { tex ->
            if (!started) {
                started = true
                startPlayback(Surface(tex))
            }
        }

        onDispose {
            if (textureView.surfaceTextureListener === listener) {
                textureView.surfaceTextureListener = null
            }
            releasePlayer()
        }
    }

    AndroidView(
        factory = { textureView },
        modifier = modifier,
        update = { applyTransform(it) }
    )
}

/**
 * Görüntüleyicide tam ekran canlı fotoğraf oynatması (Google Photos davranışı):
 *
 * - Açılınca hareket SESSİZCE BİR KEZ oynar; klip bitince [onFinished] çağrılır
 *   ve alttaki fotoğrafa dönülür.
 * - Parmak basılı kaldıkça [loop]=true ile döngüde çalar; bırakınca kliğin
 *   sonuna yetişir ve kapanır.
 *
 * Altyapı InlineVideoPlayer ile aynı (TextureView + MediaPlayer + VideoFit
 * matrisi): kare ORANINI KORUYARAK sığdırılır ve dosyadaki dönüş açısı
 * uygulanır — "uzun/gerik oynatma" geri dönüşü olmayan biçimde giderilir.
 * Yüzey, alttaki fotoğrafın tam çerçevesini kaplar; oynatma bittiğinde yüzey
 * kaldırılır ve son kare neredeyse fotoğrafın kendisi olduğu için sıçrama
 * fark edilmez.
 */
@Composable
fun LivePhotoViewer(
    videoFile: File,
    loop: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val textureView = remember(videoFile) { TextureView(context).apply { isOpaque = true } }
    // Oynatıcı yüzey doğduğunda el değiştirebilir; effect kapanışının erişmesi
    // için referans DisposableEffect dışında tutulur.
    val playerRef = remember(videoFile) {
        java.util.concurrent.atomic.AtomicReference<MediaPlayer?>(null)
    }
    var videoSize by remember(videoFile) { mutableStateOf<Pair<Int, Int>?>(null) }
    var rotation by remember(videoFile) { mutableIntStateOf(0) }

    fun applyTransform(tv: TextureView) {
        val size = videoSize ?: return
        if (tv.width <= 0 || tv.height <= 0) return
        tv.setTransform(VideoFit.fitMatrix(tv.width, tv.height, size.first, size.second, rotation, crop = false))
    }

    fun releasePlayer() {
        runCatching { playerRef.getAndSet(null)?.release() }
    }

    // Basma/bırakma oynatmayı YENİDEN BAŞLATMADAN döngü modunu çevirir.
    LaunchedEffect(loop) { runCatching { playerRef.get()?.isLooping = loop } }
    // Dönüş açısı dosyadan bir kez (IO) okunur; gelince kare düzeltilir.
    LaunchedEffect(videoFile) {
        rotation = withContext(Dispatchers.IO) { VideoFit.readRotation(videoFile) }
        applyTransform(textureView)
    }

    DisposableEffect(textureView, videoFile) {
        var started = false
        var playSurface: Surface? = null

        fun startPlayback(surface: Surface) {
            playSurface = surface
            releasePlayer()
            val player = MediaPlayer()
            playerRef.set(player)
            player.apply {
                runCatching {
                    setDataSource(videoFile.absolutePath)
                    isLooping = loop
                    setVolume(0f, 0f) // GP hareketi sessizdir
                    setScreenOnWhilePlaying(false)
                    setSurface(surface)
                    setOnVideoSizeChangedListener { mp, _, _ ->
                        videoSize = mp.videoWidth to mp.videoHeight
                        applyTransform(textureView)
                    }
                    setOnPreparedListener { mp ->
                        videoSize = (mp.videoWidth to mp.videoHeight).takeIf {
                            it.first > 0 && it.second > 0
                        }
                        applyTransform(textureView)
                        runCatching { mp.start() }
                    }
                    // Döngüde değilse klip bitince fotoğrafa dön.
                    setOnCompletionListener { if (!isLooping) onFinished() }
                    setOnErrorListener { _, _, _ -> onFinished(); true }
                    prepareAsync()
                }.onFailure {
                    releasePlayer()
                    onFinished()
                }
            }
        }

        val listener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                st: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                if (!started) {
                    started = true
                    startPlayback(Surface(st))
                }
            }

            override fun onSurfaceTextureSizeChanged(
                st: SurfaceTexture,
                width: Int,
                height: Int
            ) = applyTransform(textureView)

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releasePlayer()
                playSurface?.release()
                playSurface = null
                started = false
                return true
            }
        }
        textureView.surfaceTextureListener = listener
        // Yüzey zaten hazırsa (yeniden kompozisyon) hemen başlat.
        textureView.surfaceTexture?.let { tex ->
            if (!started) {
                started = true
                startPlayback(Surface(tex))
            }
        }

        onDispose {
            if (textureView.surfaceTextureListener === listener) {
                textureView.surfaceTextureListener = null
            }
            releasePlayer()
            playSurface?.release()
            playSurface = null
        }
    }

    AndroidView(factory = { textureView }, modifier = modifier, update = { applyTransform(it) })
}
