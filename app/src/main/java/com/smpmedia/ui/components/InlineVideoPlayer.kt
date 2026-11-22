package com.smpmedia.ui.components

import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.smpmedia.data.model.SmbImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Uygulama İÇİNDE video oynatıcı (Google Photos gibi).
 *
 * Önceden video, `ACTION_VIEW` ile sistem oynatıcısına devrediliyordu.
 * Artık görüntüleyicinin içinde oynatılır:
 * - Cihaz videoları: content:// doğrudan MediaPlayer'a verilir.
 * - SMP videoları: ağdan akıtılamadığı için önce önbelleğe kopyalanır
 *   ([ViewerActions.localFileFor]), sonra yerelden oynatılır.
 *
 * SurfaceView + MediaPlayer kullanılır (ExoPlayer bağımlılığı yok, APK
 * şişmez). [onTap] tek dokunuşta üst/alt bilgi çubuğunu açar/kapatır.
 * [isCurrent] false iken (komşu sayfa) ses/oynatma başlatılmaz.
 */
@Composable
fun InlineVideoPlayer(
    model: Any,
    modifier: Modifier = Modifier,
    showControls: Boolean = true,
    isCurrent: Boolean = true,
    onTap: () -> Unit = {}
) {
    val context = LocalContext.current

    var player by remember(model) { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember(model) { mutableStateOf(false) }
    var playing by remember(model) { mutableStateOf(false) }
    var preparing by remember(model) { mutableStateOf(true) }
    var failed by remember(model) { mutableStateOf(false) }
    var positionMs by remember(model) { mutableLongStateOf(0L) }
    var durationMs by remember(model) { mutableLongStateOf(0L) }

    // TextureView yüzeyi; MediaPlayer hazır olduğunda buraya bağlanır.
    var surface by remember(model) { mutableStateOf<Surface?>(null) }
    // Kareden gelen gerçek çözünürlük (buffer) + dosyadaki dönüş açısı;
    // VideoFit matrisi bunlarla oranı koruyarak yerleştirir.
    var videoSize by remember(model) { mutableStateOf<Pair<Int, Int>?>(null) }
    var rotation by remember(model) { mutableIntStateOf(0) }

    val interaction = remember { MutableInteractionSource() }

    // SMP dosyasını yerel önbelleğe al (ağdan akıtmak yerine).
    // Büyük videolarda indirme yüzdesi gösterilir — "takıldı" hissi olmaz.
    var downloadPercent by remember(model) { mutableStateOf<Int?>(null) }
    LaunchedEffect(model) {
        preparing = true
        val uri: Uri? = when (val m = model) {
            is Uri -> m
            is SmbImage -> withContext(Dispatchers.IO) {
                ViewerActions.localFileFor(context, m) { done, total ->
                    if (total > 0) {
                        downloadPercent = ((done * 100) / total).toInt().coerceIn(0, 100)
                    }
                }?.let { file ->
                    runCatching {
                        androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                    }.getOrNull()
                }
            }
            else -> null
        }
        if (uri == null) {
            preparing = false
            failed = true
            return@LaunchedEffect
        }
        // Dönüş açısı: telefon dikey çektiği videoyu sensörde yatay + 90°
        // metadata ile saklar; bunu uygulamazsak kare YAN YATAR.
        rotation = withContext(Dispatchers.IO) { VideoFit.readRotation(context, uri) }
        runCatching {
            MediaPlayer().apply {
                setDataSource(context, uri)
                setOnVideoSizeChangedListener { mp, _, _ ->
                    videoSize = mp.videoWidth to mp.videoHeight
                }
                setOnPreparedListener { mp ->
                    prepared = true
                    preparing = false
                    durationMs = runCatching { mp.duration.toLong() }.getOrDefault(0L)
                    videoSize = (mp.videoWidth to mp.videoHeight).takeIf {
                        it.first > 0 && it.second > 0
                    }
                    surface?.let { mp.setSurface(it) }
                    if (isCurrent) {
                        mp.start()
                        playing = true
                    }
                }
                setOnCompletionListener { mp ->
                    playing = false
                    mp.seekTo(0)
                }
                setOnErrorListener { _, what, extra ->
                    Log.w("SmpMedia", "Video oynatma hatası: $what/$extra ($uri)")
                    failed = true
                    preparing = false
                    true
                }
                surface?.let { setSurface(it) }
                prepareAsync()
            }
        }.onSuccess { player = it }
            .onFailure {
                Log.w("SmpMedia", "Video hazırlanamadı: $uri", it)
                failed = true
                preparing = false
            }
    }

    // Sayfa değişince: komşu sayfa çalmaya devam etmesin.
    LaunchedEffect(isCurrent, prepared) {
        val mp = player ?: return@LaunchedEffect
        if (!isCurrent && runCatching { mp.isPlaying }.getOrDefault(false)) {
            mp.pause()
            playing = false
        }
    }

    DisposableEffect(model) {
        onDispose {
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
        }
    }

    // Konum ilerlemesini yayınla (ince çubuk için).
    LaunchedEffect(model) {
        while (true) {
            val mp = player
            if (mp != null && prepared) {
                positionMs = runCatching { mp.currentPosition.toLong() }.getOrDefault(0L)
                durationMs = runCatching { mp.duration.toLong() }.getOrDefault(0L)
            }
            delay(250)
        }
    }

    // Kareyi görüş alanına oranını koruyarak yerleştirir + dönüş açısını
    // uygular. videoSize/rotation her değiştiğinde AndroidView update üzerinden
    // yeniden hesaplanır.
    fun applyTransform(tv: TextureView) {
        val size = videoSize ?: return
        val w = tv.width
        val h = tv.height
        if (w <= 0 || h <= 0) return
        tv.setTransform(VideoFit.fitMatrix(w, h, size.first, size.second, rotation, crop = false))
    }

    Box(
        modifier = modifier.clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onTap
        )
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = true
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            st: android.graphics.SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            val s = Surface(st)
                            surface = s
                            player?.setSurface(s)
                            applyTransform(this@apply)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            st: android.graphics.SurfaceTexture,
                            width: Int,
                            height: Int
                        ) = applyTransform(this@apply)

                        override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit

                        override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                            player?.setSurface(null)
                            surface?.release()
                            surface = null
                            return true
                        }
                    }
                }
            },
            update = { tv -> applyTransform(tv) }
        )

        if (preparing) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center)
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(32.dp)
                )
                val pct = downloadPercent
                if (pct != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "İndiriliyor · %$pct",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (failed) {
            Text(
                text = "Video oynatılamadı",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Ortadaki oynat/duraklat düğmesi (bilgi çubuğu açıkken).
        if (showControls && prepared && !failed) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f))
                    .clickable {
                        val mp = player ?: return@clickable
                        if (mp.isPlaying) {
                            mp.pause()
                            playing = false
                        } else {
                            mp.start()
                            playing = true
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Duraklat" else "Oynat",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        // Video ilerleme çubuğu (oynanan / toplam süre).
        if (prepared && durationMs > 0) {
            val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.24f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(Color.White)
                )
            }
        }
    }
}
