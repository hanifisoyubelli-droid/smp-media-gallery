package com.smpmedia.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// Sağ kenardaki şerit genişliği: ray, tutamaç ve etiket bu alanın içinde durur.
private val ScrubEdgeWidth = 150.dp

/**
 * Google Photos'un sağ kenar hızlı kaydırma göstergesi.
 *
 * Google Photos düzeni birebir kurulur:
 * - İnce RAY sağ kenarda sabit durur.
 * - Rayın üzerinde ORANTILI TUTAMAÇ (thumb) durur: yüksekliği, görünür
 *   pencerenin tüm listeye oranı kadardır (uzun listede kısa tutamaç — GP'nin
 *   "ne kadar gezdiğini" hissettiren detayı).
 * - "Ay Yılı" hapı rayın SOLUNDA, tutamacın ortasına hizalanır; GP'deki gibi
 *   hiçbir zaman rayın üstüne binmez ve yıl HER ZAMAN etikette durur.
 * - Hap parmakla kavranıp sürüklenince kronolojide hızlıca gezinilir; parmak
 *   kalkınca gösterge kısa bir süre sonra solarak kaybolur.
 *
 * IZGARAYI BOZMAMA KURALI (sahada doğrulanmış kök neden): Dokunuş işleyicisi
 * ızgarayı ÖRTEN tam boy katmana bağlanırsa (awaitEachGesture + Initial pas
 * "gözcüsü") ızgaranın tüm dokunuşları yutuluyor ve liste hiç kaymıyordu.
 * Bu yüzden tam boy Box'ta HİÇBİR pointerInput yok — düz Box dokunma
 * yoklamasına hiç katılmaz. Ray ve tutamaç da DOKUNMAZ (yalnız görsel);
 * jest yalnızca hapın kendi alanındadır:
 * - Gösterge gizliyken hap composition'ın dışındadır → temas %100 ızgaraya.
 * - Gösterge varken yalnızca hapın kendi alanındaki dikey sürükleme tüketilir;
 *   hapın dışındaki her dokunuş/kaydırma ızgaraya işler.
 *
 * Sürükleme sırasında [onScrubbing] true olur: bulut hücreleri bu sürede
 * ağdan yüklemeye başlamaz (SMB1 tek taşıma; kare kare atlarken yüzlerce
 * istek biriktirmek NAS'ı kilitler).
 */
@Composable
fun TimelineScrubber(
    gridState: LazyGridState,
    labelForIndex: (Int) -> String?,
    onScrubbing: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrolling = gridState.isScrollInProgress
    var scrubbing by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(scrubbing, scrolling) {
        if (scrubbing || scrolling) {
            visible = true
        } else {
            // Kaydırma bitince gösterge hemen söndürülmez: bu süre, etiketi
            // kavrayıp sürüklemeye devam etmek için tanınan penceredir.
            delay(1600)
            visible = false
        }
    }
    LaunchedEffect(scrubbing) { onScrubbing(scrubbing) }

    // Kaydırma ilerlemesi: ilk görünür sıranın tüm listeye oranı
    // (0f = en yeni, 1f = en eski). Hücre yükseklikleri neredeyse aynı
    // olduğu için doğrusal indeks eşlemesi Google Photos ile aynı hissi verir.
    val progress by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val total = info.totalItemsCount
            if (total <= 1) 0f else {
                val index = gridState.firstVisibleItemIndex
                val first = info.visibleItemsInfo.firstOrNull { it.index == index }
                val inItem = if (first != null && first.size.height > 0) {
                    (gridState.firstVisibleItemScrollOffset.toFloat() / first.size.height)
                        .coerceIn(0f, 1f)
                } else {
                    0f
                }
                ((index + inItem) / (total - 1f)).coerceIn(0f, 1f)
            }
        }
    }
    // Görünür pencerenin oranı → GP tutamaç yüksekliği.
    val viewportFraction by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val total = info.totalItemsCount
            if (total <= 0) 1f
            else (info.visibleItemsInfo.size.toFloat() / total).coerceIn(0.03f, 1f)
        }
    }
    // Etiket her zaman "Ay Yılı" taşır (Ay/Yıl geçişlerinde GP gibi net okunur).
    val label = labelForIndex(gridState.firstVisibleItemIndex)

    val density = LocalDensity.current
    var trackHeight by remember { mutableIntStateOf(0) }
    var pillHeight by remember { mutableIntStateOf(0) }
    var thumbHeight by remember { mutableIntStateOf(0) }
    // Parmak sürüklerken tutamacın üst ucu (0..thumbTravel).
    var thumbDragTop by remember { mutableFloatStateOf(0f) }

    val thumbTravel by remember {
        derivedStateOf { (trackHeight - thumbHeight).coerceAtLeast(0).toFloat() }
    }
    val thumbTop by remember {
        derivedStateOf {
            if (scrubbing) thumbDragTop.coerceIn(0f, thumbTravel)
            else progress * thumbTravel
        }
    }
    // Hap, tutamacın DİKEY ORTASINA hizalanır (GP); ray yüksekse hap rayın
    // dışına taşmaz.
    val pillTop by remember {
        derivedStateOf {
            (thumbTop + thumbHeight / 2f - pillHeight / 2f)
                .coerceIn(0f, (trackHeight - pillHeight).coerceAtLeast(0).toFloat())
        }
    }

    // Izgara sıçraması jest yakalayıcısı DIŞINDA yapılır: pointerInput
    // "kısıtlı" askıda kalabilen kapsam olduğundan LazyGridState'in
    // suspend metotlarını oradan çağırmak derlenmez. LaunchedEffect her yeni
    // hedefte önceki sıçramayı iptal eder → parmağın SON konumu her zaman
    // kazanır, kuyruk birikmez.
    var scrubTarget by remember { mutableIntStateOf(-1) }
    LaunchedEffect(scrubTarget) {
        if (scrubTarget >= 0) gridState.scrollToItem(scrubTarget)
    }

    // Tam boy katman: YALNIZ ölçüm alır, dokunuşa katılmaz (bkz. sınıf üstü not).
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { trackHeight = it.height }
    ) {
        // 1) Ray + tutamaç: sağ kenardaki şeridin içinde durur.
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(400)),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Box(modifier = Modifier.fillMaxHeight().width(ScrubEdgeWidth)) {
                // İnce ray: sürüklenebilir yolu hissettirir; sabit ve dokunmaz.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 14.dp)
                        .fillMaxHeight()
                        .width(2.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f),
                            RoundedCornerShape(1.dp)
                        )
                )
                // GP tutamacı: yüksekliği görünür pencereyle orantılı; kaydırma
                // sırasında ray üzerinde hareket eder. Yalnız GÖRSEL — jest
                // hapın kendisinde kalmaya devam eder (ızgara güvenliği).
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = with(density) { thumbTop.toDp() })
                        .onSizeChanged { thumbHeight = it.height }
                        .padding(end = 13.dp)
                        .width(4.dp)
                        .height(
                            with(density) {
                                (trackHeight * viewportFraction)
                                    .toDp()
                                    .coerceAtLeast(44.dp)
                            }
                        )
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            RoundedCornerShape(2.dp)
                        )
                )
            }
        }
        // 2) Ay yılı hapı: RAY ŞERİDİNİN DIŞINDA, kısıtsız bir katmanda durur.
        // (Hap şeridin içindeyken metin alanı ~84 dp'ye sıkışıyor, "Kasım 2026"
        // gibi etiketler sığmayıp maxLines=1 yüzünden YIL satıra düşüyor ve
        // sessizce kırpılıyordu — kullanıcı şikâyeti: "yıl görünmüyor". Şimdi
        // hap ekranın kalan tüm genişliğini kullanır; hiçbir ay adı kesilemez.)
        // Hap rayın SOLUNDA, tutamacın dikey ortasına hizalıdır; parmakla
        // kavranan tek tutamaç budur. Tüm jestler BU küçük alanın içindedir;
        // ızgaraya dokunan tek bir piksel bile yok.
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(400)),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = with(density) { pillTop.toDp() })
                    .onSizeChanged { pillHeight = it.height }
                    // Hap rayın üstüne binmesin: ray alanının solunda durur.
                    .padding(end = 28.dp)
                    // Parmak payı: arka planın 14 dp dışına kadar dokunuş
                    // etiketi kavramış sayılır (GP parmak toleransı).
                    .padding(14.dp)
                    .background(
                        MaterialTheme.colorScheme.inverseSurface,
                        RoundedCornerShape(percent = 50)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    // Dokunma (tık) yutulur ama eylem yapmaz: hapın
                    // altındaki hücre yanlışlıkla açılmasın.
                    .pointerInput(Unit) {
                        detectTapGestures { }
                    }
                    // Dikey sürükleme: deltasal ilerleme hapı parmağı
                    // birebir izler; hedef sıra ızgaraya LaunchedEffect ile
                    // iletilir. Sürükleme başlayınca Compose parmağı bu
                    // düğüme kilitler, parmak hapın dışına taşsa da takibi
                    // bırakmaz.
                    .pointerInput(thumbTravel) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                scrubbing = true
                                // Tutamaç şu an rayda nerede duruyorsa
                                // oradan kavranmış sayılır.
                                thumbDragTop = (progress * thumbTravel)
                            },
                            onDragEnd = { scrubbing = false },
                            onDragCancel = { scrubbing = false }
                        ) { change, dragAmountY ->
                            change.consume()
                            if (thumbTravel > 0f) {
                                thumbDragTop = (thumbDragTop + dragAmountY)
                                    .coerceIn(0f, thumbTravel)
                                val total = gridState.layoutInfo.totalItemsCount
                                if (total > 1) {
                                    scrubTarget =
                                        ((thumbDragTop / thumbTravel) * (total - 1))
                                            .roundToInt()
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    // YIL ASLA kaybolmasın: satır sarması kapalı — etiket ne
                    // kadar uzunsa hap da o kadar genişler.
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible
                )
            }
        }
    }
}
