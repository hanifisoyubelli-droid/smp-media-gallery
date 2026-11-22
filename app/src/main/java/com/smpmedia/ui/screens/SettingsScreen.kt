package com.smpmedia.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smpmedia.data.model.MediaSourceKind
import com.smpmedia.data.model.SmbEntry
import com.smpmedia.data.sync.BgGuard
import com.smpmedia.ui.viewmodel.FolderBrowserState
import com.smpmedia.ui.viewmodel.SettingsViewModel

/** SMB bağlantı bilgileri ve otomatik yedekleme tercihleri. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenMappings: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val browser by viewModel.browser.collectAsStateWithLifecycle()
    val ncState by viewModel.ncState.collectAsStateWithLifecycle()
    val warm by viewModel.gridWarm.collectAsStateWithLifecycle()
    // Anahtar başlangıç değeri kaynak bazlı tercihten okunur (GridWarmer).
    var warmAuto by remember { mutableStateOf(viewModel.isWarmAutoEnabled()) }
    val snackbarHostState = remember { SnackbarHostState() }
    var passwordVisible by remember { mutableStateOf(false) }
    var ncPasswordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }
    LaunchedEffect(ncState.message) {
        val message = ncState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeNcMessage()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Ayarlar") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("SMB Sunucusu", style = MaterialTheme.typography.titleMedium)
            }
            item {
                OutlinedTextField(
                    value = state.host,
                    onValueChange = viewModel::updateHost,
                    label = { Text("Sunucu IP (ör. 192.168.1.10)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = state.share,
                    onValueChange = viewModel::updateShare,
                    label = { Text("Paylaşım adı (ör. USB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = state.domain,
                    onValueChange = viewModel::updateDomain,
                    label = { Text("Etki alanı (isteğe bağlı)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::updateUsername,
                    label = { Text("Kullanıcı adı") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::updatePassword,
                    label = { Text("Şifre") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (passwordVisible) {
                                    "Şifreyi gizle"
                                } else {
                                    "Şifreyi göster"
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = !state.testing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.testing) "Test ediliyor..." else "Bağlantıyı Test Et")
                }
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Kullanıcı Klasörü", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Text(
                    text = "Bu telefonun fotoğrafları SMP üzerinde hangi klasörde " +
                        "tutulacak? Alt klasörler genelde kişi adlarıdır " +
                        "(ör. usb1_1/Ayse). Fotoğraflar ve yedekleme hedefleri " +
                        "bu klasöre bağlanır.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "/" + state.userFolder.ifBlank { "(paylaşım kökü)" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = viewModel::openFolderBrowser,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Klasör Seç")
                }
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Nextcloud", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Text(
                    text = "Aynı arşiv Nextcloud'da da duruyorsa zaman tünelini " +
                        "oradan açabilirsiniz. Şifre olarak HESAP şifrenizi değil, " +
                        "Nextcloud → Ayarlar → Güvenlik → \"Uygulama şifresi " +
                        "oluştur\" ile üretilen anahtarı kullanın; tek tuşla " +
                        "iptal edilebilir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Nextcloud kullan")
                        Text(
                            text = "Kapatınca zaman tüneli SMP'de kalır.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = ncState.enabled,
                        onCheckedChange = viewModel::updateNcEnabled
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = ncState.baseUrl,
                    onValueChange = viewModel::updateNcBaseUrl,
                    label = { Text("Sunucu adresi (ör. https://nextcloud.ev:8443)") },
                    singleLine = true,
                    enabled = ncState.enabled,
                    supportingText = { Text("http:// kendiliğinden eklenir.") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = ncState.username,
                    onValueChange = viewModel::updateNcUsername,
                    label = { Text("Kullanıcı adı") },
                    singleLine = true,
                    enabled = ncState.enabled,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = ncState.appPassword,
                    onValueChange = viewModel::updateNcAppPassword,
                    label = { Text("Uygulama şifresi") },
                    singleLine = true,
                    enabled = ncState.enabled,
                    visualTransformation = if (ncPasswordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { ncPasswordVisible = !ncPasswordVisible }) {
                            Icon(
                                imageVector = if (ncPasswordVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (ncPasswordVisible) {
                                    "Şifreyi gizle"
                                } else {
                                    "Şifreyi göster"
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = ncState.rootFolder,
                    onValueChange = viewModel::updateNcRootFolder,
                    label = { Text("Taranacak klasör (ör. Fotos)") },
                    singleLine = true,
                    enabled = ncState.enabled,
                    supportingText = {
                        Text("Boş bırakılırsa hesabın tüm klasörleri taranır.")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                // Kendinden imzalı sertifika: yalnızca HTTPS'te anlamlı.
                // Ne yaptığını bilmeyen kullanıcı açmasın diye kısa ama açık
                // uyarı; ayrıntılı davranış NextcloudTls'te belgelenmiştir.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Kendi imzalı sertifikaya güven")
                        Text(
                            text = "Yalnızca kendi sunucunun kendinden imzalı " +
                                "sertifikası/özel CA'sı için. Sunucu adı doğrulaması " +
                                "yine açık kalır. Ne yaptığını bilmiyorsan KAPALI bırak.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = ncState.allowSelfSigned,
                        onCheckedChange = viewModel::updateNcAllowSelfSigned,
                        enabled = ncState.enabled
                    )
                }
            }
            item {
                Text(
                    text = "Zaman tünelinde hangisi görünsün? (Aynı arşiv iki " +
                        "kaynaktan gelse tünelde iki kez listelenirdi; bu " +
                        "yüzden yalnızca biri seçilir.)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = ncState.primarySource == MediaSourceKind.SMB,
                        onClick = {
                            viewModel.updateNcPrimarySource(MediaSourceKind.SMB)
                        },
                        label = { Text("SMP (modem USB)") }
                    )
                    FilterChip(
                        selected = ncState.primarySource == MediaSourceKind.NEXTCLOUD,
                        onClick = {
                            // Kaynak seçimi etkinliği de içerir; kapalı
                            // ayarla "Nextcloud" seçilirse hiçbir şey
                            // olmayacağını sanmasın.
                            viewModel.updateNcPrimarySource(MediaSourceKind.NEXTCLOUD)
                            viewModel.updateNcEnabled(true)
                        },
                        label = { Text("Nextcloud") }
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = viewModel::testNextcloud,
                    enabled = ncState.enabled && !ncState.testing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (ncState.testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (ncState.testing) {
                            "Test ediliyor... (Nextcloud yavaş yanıt verebilir)"
                        } else {
                            "Nextcloud'u Test Et"
                        }
                    )
                }
            }
            item {
                Button(
                    onClick = viewModel::saveNextcloud,
                    enabled = ncState.enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Nextcloud'u Kaydet")
                }
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Izgara Isıtma", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Text(
                    text = "Fotoğrafların küçük resimleri siz kaydırmadan önce " +
                        "arkada sırayla indirilir; ızgarada hiçbir kare " +
                        "\"geç açılmaz\". Sunucuya tek tek, yavaşça sorar — " +
                        "isterse saatlerce sürebilir, yarıda kalsa bile kaldığı " +
                        "yerden devam eder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Otomatik ısıt")
                        Text(
                            text = "Uygulama her açıldığında kaldığı yerden sürer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = warmAuto,
                        onCheckedChange = { on ->
                            warmAuto = on
                            viewModel.setWarmAutoEnabled(on)
                        }
                    )
                }
            }
            warm?.let { p ->
                if (p.total > 0) {
                    item {
                        Column {
                            LinearProgressIndicator(
                                progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = buildString {
                                    append("${p.done} / ${p.total}")
                                    if (p.downloaded > 0) append(" • ${p.downloaded} indirildi")
                                    if (p.currentName.isNotBlank()) {
                                        append(" • ${p.currentName}")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                p.message?.let { message ->
                    item {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        if (warm?.running == true) viewModel.stopGridWarm() else viewModel.startGridWarm()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (warm?.running == true) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Isıtmayı Durdur")
                    } else {
                        Text("Şimdi Isıt")
                    }
                }
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Otomatik Yedekleme", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Arka planda yedekle")
                        Text(
                            text = "Belirtilen aralıkta WorkManager ile çalışır.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.autoBackup,
                        onCheckedChange = viewModel::updateAutoBackup
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1, 3, 6, 12, 24).forEach { hours ->
                        FilterChip(
                            selected = state.intervalHours == hours,
                            onClick = { viewModel.updateInterval(hours) },
                            label = { Text("${hours} saat") },
                            enabled = state.autoBackup
                        )
                    }
                }
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Arka Plan Koruması", style = MaterialTheme.typography.titleMedium)
            }
            item {
                BackgroundGuardCard()
            }
            item {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Kaydet")
                }
            }
            item {
                OutlinedButton(
                    onClick = onOpenMappings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Klasör Eşleştirme")
                }
            }
            item {
                Text(
                    text = "Şifre, Android Keystore ile korunan EncryptedSharedPreferences " +
                        "içinde saklanır. Sunucu bilgileri yalnızca yerel ağda kullanılır.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Kullanıcı klasörü seçici: paylaşım kökünden itibaren klasörlerle gezin,
    // hedef klasördeyken "Bu klasörü seç" → Kaydet ile devreye girer.
    if (browser.visible) {
        FolderPickerDialog(
            browser = browser,
            onEnter = viewModel::browseInto,
            onUp = viewModel::browseUp,
            onChoose = viewModel::chooseCurrentFolder,
            onDismiss = viewModel::closeFolderBrowser
        )
    }
}

/**
 * "Arka plan koruması" kartı (kullanıcı isteği: uygulama arka planda da
 * çalışsın, pil optimizasyonu v.s.).
 *
 * Anahtar, [com.smpmedia.data.sync.MediaSyncService] hizmetini açar/kapatır;
 * ikinci buton sistem pil optimizasyonu muafiyetini ister. Muafiyet olmadan
 * Doze, 15 dakikalık tazelemeleri saatlerce geciktirebilir — ikisi birlikte
 * anlam kazanır.
 */
@Composable
private fun BackgroundGuardCard() {
    val context = LocalContext.current
    var guardOn by remember { mutableStateOf(BgGuard.isEnabled(context)) }
    var batteryExempt by remember { mutableStateOf(BgGuard.isIgnoringBatteryOptimizations(context)) }
    // Kullanıcı sistem penceresinde "İzin ver / Engelle" dedikten sonra bu
    // ekrana döndüğünde durum tazelenir (aksi hâlde buton yalan söyler).
    LifecycleResumeEffect(Unit) {
        batteryExempt = BgGuard.isIgnoringBatteryOptimizations(context)
        guardOn = BgGuard.isEnabled(context)
        onPauseOrDispose { }
    }
    // Android 13+: ön plan hizmetinin bildirimi için bildirim izni gerekir.
    // Reddedilirse yine de açılır — hizmet çalışır, yalnızca bildirim sessiz
    // kalır; kullanıcıyı korumasız bırakmamak için izin şart koşulmaz.
    var pendingEnable by remember { mutableStateOf(false) }
    val requestNotification = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        if (pendingEnable) {
            pendingEnable = false
            BgGuard.enable(context)
            guardOn = true
        }
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Telefon cebindeyken SMP canlı kalsın")
                Text(
                    text = "SMB bağlantısı arka planda canlı tutulur, bayatlayan " +
                        "liste kendiliğinden tazelenir; uygulama açıldığında " +
                        "bekletmez. Yedekleme gibi veri indirmez.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = guardOn,
                onCheckedChange = { on ->
                    when {
                        !on -> {
                            BgGuard.disable(context)
                            guardOn = false
                        }
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED -> {
                            pendingEnable = true
                            requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        else -> {
                            BgGuard.enable(context)
                            guardOn = true
                        }
                    }
                }
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                if (batteryExempt) return@OutlinedButton
                // Önce doğrudan bu uygulama için özel onay penceresi; cihaz
                // sunmuyorsa tüm liste açılır.
                val direct = runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:" + context.packageName))
                    )
                }.isSuccess
                if (!direct) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        )
                    }
                }
            },
            enabled = !batteryExempt,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (batteryExempt) "Pil kısıtı kaldırıldı" else "Pil optimizasyonunu kaldır")
        }
    }
}

/** SMB klasör gezgini diyaloğu (kullanıcı klasörü seçimi). */
@Composable
private fun FolderPickerDialog(
    browser: FolderBrowserState,
    onEnter: (SmbEntry) -> Unit,
    onUp: () -> Unit,
    onChoose: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kullanıcı Klasörü Seç") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Gezinen yol + üst klasöre çıkış.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "/" + browser.path.ifBlank { "(paylaşım kökü)" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onUp, enabled = browser.path.isNotBlank()) {
                        Text("Üst")
                    }
                }
                Spacer(Modifier.height(4.dp))
                when {
                    browser.loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }

                    browser.error != null -> Text(
                        text = browser.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )

                    browser.folders.isEmpty() -> Text(
                        text = "Bu klasörde alt klasör yok. Bu klasörü seçebilirsiniz.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )

                    else -> LazyColumn(modifier = Modifier.height(280.dp)) {
                        items(
                            items = browser.folders,
                            key = { entry -> entry.path }
                        ) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onEnter(entry) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onChoose,
                enabled = !browser.loading && browser.error == null
            ) {
                Text("Bu klasörü seç")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Vazgeç") }
        }
    )
}
