package com.smpmedia.ui.components

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.smpmedia.data.model.SmbImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Yerleşik fotoğraf düzenleyici (kullanıcı isteği): "Düzenle" artık dış
 * uygulamaya gitmiyor; **çevir (90° adımlar) + kırp** doğrudan burada.
 *
 * Kaynak davranışı:
 * - Cihaz fotoğrafı: kendi content URI'sinden çözülür.
 * - SMP fotoğrafı: ÖZGÜN dosya bir kez shared/ önbelleğine indirilir
 *   (ilerlemesi gösterilir). Sonuç HER ZAMAN telefona kopya olarak yazılır:
 *   Galeri → Pictures/SMP Düzenlenen. Buluttaki özgün dosyaya dokunulmaz.
 *
 * Bellek güvenliği: önizleme ≤1440 px, kayıt çözümü ≤4096 px — 48 MP
 * telefon fotoğrafları tam boy çözülüp OOM üretemez.
 */
private const val PREVIEW_MAX_DIM = 1440
private const val SAVE_MAX_DIM = 4096

/** Köşe/kaydırma sürükleme modu (kırpma çerçevesi etkileşimi). */
private enum class CropDrag { None, Move, TopLeft, TopRight, BottomLeft, BottomRight }

@Composable
fun PhotoEditorScreen(
    photo: ViewerPhoto,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── Kaynak çözümleme: cihaz uri'si veya SMP kopyası ──
    var source by remember { mutableStateOf<Any?>(null) } // File | Uri
    var downloadPct by remember { mutableStateOf(-1) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(photo) {
        when (val model = photo.model) {
            is Uri -> source = model
            is SmbImage -> {
                source = ViewerActions.localFileFor(context, model) { written, total ->
                    if (total > 0L) downloadPct = ((written * 100) / total).toInt().coerceIn(0, 99)
                }
                if (source == null) failed = true
            }
            else -> failed = true
        }
    }

    // ── Düzenleme durumu ──
    var rotation by remember { mutableStateOf(0) } // 0/90/180/270 saat yönü
    var crop by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) } // normalize
    var aspect by remember { mutableStateOf<Float?>(null) } // null = serbest
    var baseBitmap by remember { mutableStateOf<Bitmap?>(null) } // EXIF döndürülmüş
    var shownBitmap by remember { mutableStateOf<Bitmap?>(null) } // + kullanıcı dönüşü
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(source) {
        val src = source ?: return@LaunchedEffect
        baseBitmap = withContext(Dispatchers.IO) { decodeSampled(context, src, PREVIEW_MAX_DIM) }
        if (baseBitmap == null) failed = true
    }
    LaunchedEffect(baseBitmap, rotation) {
        val base = baseBitmap ?: return@LaunchedEffect
        val rotated = if (rotation == 0) base
        else withContext(Dispatchers.IO) { rotateBitmap(base, rotation.toFloat()) }
        val old = shownBitmap
        shownBitmap = rotated
        // Kullanıcı dönüşünden türeyen eski kare serbest bırakılır (temel
        // bitmap LaunchedEffect'in elinde duruyor, ona dokunulmaz).
        if (old != null && old !== base && old !== rotated) old.recycle()
    }

    // ── Eylemler ──
    fun rotateBy90() {
        rotation = (rotation + 90) % 360
        val b = shownBitmap
        if (b != null) {
            val a = aspect
            if (a != null) {
                // 90° adımda kadrajın en-boyu de ters döner; kadraj yeniden
                // ortalanır (boyutlar yer değiştirir).
                aspect = 1f / a
                crop = centeredCrop(aspect, b.height, b.width)
            } else {
                // Serbest kadraj görüntüyle birlikte döner (köşe eşlemesi).
                crop = Rect(1f - crop.bottom, crop.left, 1f - crop.top, crop.right)
            }
        }
    }

    fun pickAspect(a: Float?) {
        aspect = a
        val b = shownBitmap ?: return
        crop = centeredCrop(a, b.width, b.height)
    }

    fun resetEdits() {
        rotation = 0
        aspect = null
        crop = Rect(0f, 0f, 1f, 1f)
    }

    fun saveEdited() {
        val src = source ?: return
        if (saving) return
        saving = true
        scope.launch {
            val savedUri = withContext(Dispatchers.IO) {
                runCatching {
                    val full = decodeSampled(context, src, SAVE_MAX_DIM) ?: return@runCatching null
                    val rotated =
                        if (rotation == 0) full else rotateBitmap(full, rotation.toFloat())
                    val out = applyCrop(rotated, crop)
                    val uri = insertToGallery(context, photo.title, out)
                    if (out !== rotated) out.recycle()
                    if (rotated !== full) rotated.recycle()
                    full.recycle()
                    uri
                }.getOrNull()
            }
            saving = false
            Toast.makeText(
                context,
                if (savedUri != null) "Kaydedildi: Galeri › SMP Düzenlenen"
                else "Kaydedilemedi — tekrar deneyin",
                Toast.LENGTH_SHORT
            ).show()
            if (savedUri != null) onDismiss()
        }
    }

    // ── Arayüz ──
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val bmp = shownBitmap
    val imgRect = remember(bmp, boxSize) {
        if (bmp == null || boxSize.width == 0 || boxSize.height == 0) Rect.Zero
        else {
            val scale = min(
                boxSize.width.toFloat() / bmp.width,
                boxSize.height.toFloat() / bmp.height
            )
            val w = bmp.width * scale
            val h = bmp.height * scale
            Rect(
                (boxSize.width - w) / 2f,
                (boxSize.height - h) / 2f,
                (boxSize.width + w) / 2f,
                (boxSize.height + h) / 2f
            )
        }
    }
    val cornerTouch = with(LocalDensity.current) { 44.dp.toPx() }
    val minCropPx = with(LocalDensity.current) { 48.dp.toPx() }
    val currentCrop by rememberUpdatedState(crop)
    val currentAspect by rememberUpdatedState(aspect)
    val saveEnabled = bmp != null && !saving

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Editör açıkken alttaki görüntüleyici/pager olay almasın: çocuk
            // (buton, kırpma sürükleme) olayı önce tüketir; boş alanlarda
            // kalan dokunuş ve sürüklemeler burada yutulur. (İki ayrı
            // pointerInput = iki yutucu düğüm; Compose'un eski sürümlerinde
            // de güvenle bulunabilen stabilize edicilerle çalışır.)
            .pointerInput(Unit) { detectTapGestures { } }
            .pointerInput(Unit) { detectDragGestures { _, _ -> } }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Üst çubuk: Vazgeç · başlık · Kaydet
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, "Vazgeç", tint = Color.White)
                }
                Text(
                    "Düzenle",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (saveEnabled) MaterialTheme.colorScheme.primary else Color.DarkGray
                        )
                        .clickable(enabled = saveEnabled) { saveEdited() }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Kaydediliyor…", color = Color.White)
                    } else {
                        Icon(
                            Icons.Filled.Check,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Kaydet", color = Color.White)
                    }
                }
            }

            // Görsel alanı: resim + kırpma kadrajı
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .onSizeChanged { boxSize = it },
                contentAlignment = Alignment.Center
            ) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.matchParentSize()
                    )
                    Canvas(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(bmp, aspect) {
                                var mode = CropDrag.None
                                detectDragGestures(
                                    onDragStart = { start ->
                                        mode = classifyCropDrag(
                                            start, currentCrop, imgRect, cornerTouch
                                        )
                                    },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        val next = dragCrop(
                                            currentCrop, currentAspect, imgRect,
                                            mode, drag, minCropPx
                                        )
                                        if (next != null) crop = next
                                    },
                                    onDragEnd = { mode = CropDrag.None },
                                    onDragCancel = { mode = CropDrag.None }
                                )
                            }
                    ) {
                        if (imgRect != Rect.Zero) drawCropOverlay(crop, imgRect)
                    }
                } else if (!failed) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (downloadPct >= 0) "Orijinal indiriliyor %$downloadPct"
                            else "Fotoğraf hazırlanıyor…",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                } else {
                    Text(
                        "Fotoğraf açılamadı",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            // Alt araç çubuğu: çevir + en-boy kadrajları + sıfırla
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(onClick = { rotateBy90() }, enabled = bmp != null) {
                    Icon(Icons.Filled.Rotate90DegreesCw, "Çevir", tint = Color.White)
                }
                listOf<Pair<String, Float?>>(
                    "Serbest" to null,
                    "Kare" to 1f,
                    "4:3" to 4f / 3f,
                    "16:9" to 16f / 9f,
                    "9:16" to 9f / 16f
                ).forEach { (label, value) ->
                    val a = aspect
                    val selected = when {
                        value == null && a == null -> true
                        value != null && a != null -> abs(a - value) < 0.01f
                        else -> false
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (selected) Color.White.copy(alpha = 0.92f)
                                else Color.White.copy(alpha = 0.14f)
                            )
                            .clickable { pickAspect(value) }
                            .padding(horizontal = 6.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) Color.Black else Color.White
                        )
                    }
                }
                IconButton(onClick = { resetEdits() }, enabled = bmp != null) {
                    Icon(
                        Icons.Filled.Restore,
                        "Sıfırla",
                        tint = if (rotation == 0 && aspect == null &&
                            crop.left == 0f && crop.top == 0f &&
                            crop.right == 1f && crop.bottom == 1f
                        ) Color.Gray else Color.White
                    )
                }
            }
        }
    }
}

// ─── Kadraj çizimi ve sürükleme matematiği ─────────────────────────────

/** Kadrajın dışını karartır; beyaz çerçeve + üçlü kılavuz + köşe tutamaçları. */
private fun DrawScope.drawCropOverlay(crop: Rect, img: Rect) {
    val px = Rect(
        img.left + crop.left * img.width,
        img.top + crop.top * img.height,
        img.left + crop.right * img.width,
        img.top + crop.bottom * img.height
    )
    val shade = Color.Black.copy(alpha = 0.55f)
    drawRect(shade, Offset(img.left, img.top), Size(img.width, px.top - img.top))
    drawRect(shade, Offset(img.left, px.bottom), Size(img.width, img.bottom - px.bottom))
    drawRect(shade, Offset(img.left, px.top), Size(px.left - img.left, px.height))
    drawRect(shade, Offset(px.right, px.top), Size(img.right - px.right, px.height))
    // Çerçeve + ince üçlü kılavuz.
    drawRect(Color.White.copy(alpha = 0.9f), px.topLeft, px.size,
        style = Stroke(width = 2.dp.toPx()))
    val guide = Color.White.copy(alpha = 0.35f)
    for (k in 1..2) {
        val x = px.left + px.width * k / 3f
        val y = px.top + px.height * k / 3f
        drawLine(guide, Offset(x, px.top), Offset(x, px.bottom), 1.dp.toPx())
        drawLine(guide, Offset(px.left, y), Offset(px.right, y), 1.dp.toPx())
    }
    // Köşe L-tutamaçları.
    val arm = min(px.width, px.height) * 0.12f
    val handle = 7.dp.toPx()
    val corners = listOf(
        Triple(px.topLeft, Offset(1f, 0f), Offset(0f, 1f)),
        Triple(Offset(px.right, px.top), Offset(-1f, 0f), Offset(0f, 1f)),
        Triple(Offset(px.left, px.bottom), Offset(1f, 0f), Offset(0f, -1f)),
        Triple(px.bottomRight, Offset(-1f, 0f), Offset(0f, -1f))
    )
    corners.forEach { (p, dx, dy) ->
        drawLine(Color.White, p, Offset(p.x + dx.x * arm, p.y + dx.y * arm), handle)
        drawLine(Color.White, p, Offset(p.x + dy.x * arm, p.y + dy.y * arm), handle)
    }
}

/** Sürükleme başlangıç noktasına göre mod seçer (köşe mi, taşıma mı). */
private fun classifyCropDrag(
    start: Offset,
    crop: Rect,
    img: Rect,
    touch: Float
): CropDrag {
    val l = img.left + crop.left * img.width
    val t = img.top + crop.top * img.height
    val r = img.left + crop.right * img.width
    val b = img.top + crop.bottom * img.height
    fun near(x: Float, y: Float) = abs(start.x - x) <= touch && abs(start.y - y) <= touch
    return when {
        near(l, t) -> CropDrag.TopLeft
        near(r, t) -> CropDrag.TopRight
        near(l, b) -> CropDrag.BottomLeft
        near(r, b) -> CropDrag.BottomRight
        start.x in l..r && start.y in t..b -> CropDrag.Move
        else -> CropDrag.None
    }
}

/** Sürükleme adımıyla yeni normalize kırpma dikdörtgeni (null = değişmedi). */
private fun dragCrop(
    crop: Rect,
    aspect: Float?,
    img: Rect,
    mode: CropDrag,
    delta: Offset,
    minPx: Float
): Rect? {
    if (mode == CropDrag.None) return null
    // Piksel uzayında hesapla — normalize uzayda en-boy oranı bozulur.
    var l = img.left + crop.left * img.width
    var t = img.top + crop.top * img.height
    var r = img.left + crop.right * img.width
    var b = img.top + crop.bottom * img.height
    when (mode) {
        CropDrag.Move -> {
            val dx = delta.x.coerceIn(img.left - l, img.right - r)
            val dy = delta.y.coerceIn(img.top - t, img.bottom - b)
            l += dx; r += dx; t += dy; b += dy
        }
        CropDrag.TopLeft -> { l += delta.x; t += delta.y }
        CropDrag.TopRight -> { r += delta.x; t += delta.y }
        CropDrag.BottomLeft -> { l += delta.x; b += delta.y }
        CropDrag.BottomRight -> { r += delta.x; b += delta.y }
        CropDrag.None -> Unit
    }
    // Görüntü sınırları + minimum kadraj boyutu.
    l = l.coerceIn(img.left, max(img.left, r - minPx))
    r = r.coerceIn(min(img.right, l + minPx), img.right).coerceAtLeast(l + minPx)
    t = t.coerceIn(img.top, max(img.top, b - minPx))
    b = b.coerceIn(min(img.bottom, t + minPx), img.bottom).coerceAtLeast(t + minPx)
    if (aspect != null && aspect > 0f) {
        // En-boyu koru: genişliği ölç al, boyu türet; sığmıyorsa boyu ölç al.
        var w = r - l
        var h = w / aspect
        val anchorLeft = mode == CropDrag.TopRight || mode == CropDrag.BottomRight
        val anchorTop = mode == CropDrag.BottomLeft || mode == CropDrag.BottomRight
        if (t + h > img.bottom) {
            h = if (anchorTop) b - img.top else img.bottom - t
            w = h * aspect
        }
        if (w > img.width) {
            w = img.width
            h = w / aspect
        }
        l = if (anchorLeft) r - w else l
        t = if (anchorTop) b - h else t
        l = l.coerceIn(img.left, img.right - w)
        t = t.coerceIn(img.top, img.bottom - h)
        r = l + w
        b = t + h
    }
    return Rect(
        (l - img.left) / img.width,
        (t - img.top) / img.height,
        (r - img.left) / img.width,
        (b - img.top) / img.height
    )
}

/** Verilen en-boy için ortalanmış başlangıç kadrajı (normalize uzay). */
private fun centeredCrop(aspect: Float?, bmpW: Int, bmpH: Int): Rect {
    if (aspect == null || bmpW <= 0 || bmpH <= 0) return Rect(0f, 0f, 1f, 1f)
    var w = 1f
    var h = bmpW.toFloat() / (aspect * bmpH)
    if (h > 1f) {
        h = 1f
        w = aspect * bmpH / bmpW
    }
    val x = (1f - w) / 2f
    val y = (1f - h) / 2f
    return Rect(x, y, x + w, y + h)
}

// ─── Çözme / kaydetme ──────────────────────────────────────────────────

/** Kaynak (File | Uri) akışını her çağrıda taze açar. */
private fun openStream(context: Context, source: Any) = when (source) {
    is java.io.File -> java.io.FileInputStream(source)
    is Uri -> context.contentResolver.openInputStream(source)
    else -> null
}

/**
 * Kaynağı en fazla [maxDim] px olacak şekilde indirgeyerek çözer ve EXIF
 * oryantasyonunu uygular (küçük resillerdeki SmbImageFetcher'ın yolu).
 */
private fun decodeSampled(context: Context, source: Any, maxDim: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openStream(context, source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = openStream(context, source)
        ?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

    // EXIF oryantasyonu (yalnız JPEG'de anlamlı; okunamazsa kare olduğu gibi).
    var oriented = decoded
    runCatching {
        val exif = openStream(context, source)?.use { ExifInterface(it) }
        if (exif == null) return@runCatching
        val m = Matrix()
        when (exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            else -> Unit
        }
        if (!m.isIdentity) {
            val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            if (out !== decoded) {
                decoded.recycle()
                oriented = out
            }
        }
    }

    val over = max(oriented.width, oriented.height)
    if (over <= maxDim) return oriented
    val f = maxDim.toFloat() / over
    val scaled = Bitmap.createScaledBitmap(
        oriented,
        max(2, (oriented.width * f).roundToInt()),
        max(2, (oriented.height * f).roundToInt()),
        true
    )
    if (scaled !== oriented) oriented.recycle()
    scaled
}.getOrNull()

/** Bitmap'i [degrees] saat yönünde döndürür (yeni bitmap döndürür). */
private fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
    val m = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
}

/** Normalize kırpma dikdörtgenini piksele çevirip uygular (tam kadrajda no-op). */
private fun applyCrop(src: Bitmap, crop: Rect): Bitmap {
    if (crop.left < 0.001f && crop.top < 0.001f &&
        crop.right > 0.999f && crop.bottom > 0.999f
    ) return src
    val x = (crop.left * src.width).roundToInt().coerceIn(0, src.width - 2)
    val y = (crop.top * src.height).roundToInt().coerceIn(0, src.height - 2)
    val w = ((crop.right - crop.left) * src.width).roundToInt().coerceIn(2, src.width - x)
    val h = ((crop.bottom - crop.top) * src.height).roundToInt().coerceIn(2, src.height - y)
    return Bitmap.createBitmap(src, x, y, w, h)
}

/** Kopyayı MediaStore galerisine JPEG olarak yazar; URI döndürür. */
private fun insertToGallery(context: Context, originalTitle: String, bmp: Bitmap): Uri? {
    val base = originalTitle.substringBeforeLast('.')
        .ifBlank { "foto" }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "${base}_duzenlenmis.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SMP Düzenlenen")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    var uri: Uri? = null
    return runCatching {
        uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching null
        val ok = resolver.openOutputStream(uri!!)
            ?.use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, out) } == true
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri!!, values, null, null)
        if (ok) {
            uri
        } else {
            resolver.delete(uri, null, null)
            null
        }
    }.onFailure {
        uri?.let { runCatching { resolver.delete(it, null, null) } }
    }.getOrNull()
}
