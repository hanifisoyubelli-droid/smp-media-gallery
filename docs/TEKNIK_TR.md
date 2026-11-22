# SMP Media

Google Photos benzeri arayüze sahip, fotoğrafları **SMB** paylaşımına (ör. modem USB
paylaşımı) yedekleyen Android uygulaması.

## Teknik yığın

| Katman | Teknoloji |
|---|---|
| Dil | Kotlin 2.0.21 |
| UI | Jetpack Compose + Material 3 |
| Mimari | MVVM + Repository + Hilt |
| Yerel veritabanı | Room (eşleştirmeler + yedekleme geçmişi) |
| SMB | jcifs-ng 2.1.10 |
| Arka plan | WorkManager (periyodik + tek seferlik) |
| Gizli ayarlar | EncryptedSharedPreferences (Android Keystore) |
| Görsel yükleme | Coil |

## Ekranlar

1. **Fotoğraflar** – İki kaynak seçeneği:
   - **Cihaz**: Gün başlıklarına ayrılmış 3 sütunlu ızgara. Her fotoğrafın
     sağ alt köşesinde yedekleme durumu (bulut tik / yükleniyor / bekliyor / hata).
   - **SMP**: SMB sunucusundaki yedeklerin klasörlere göre listesi. Fotoğraf
     cihazdan silinmiş olsa bile burada görünür.
2. **Koleksiyonlar** – Telefondaki klasörler; etkin eşleştirmesi olanlarda
   bulut-tik, olmayan veya kapalı olanlarda bulut-slash. Klasöre dokununca
   içindeki fotoğraflar açılır; üstteki düğmeden eşleştirme ekranına gidilir.
3. **Yedekleme** – Durum kartı, ilerleme çubuğu, "Şimdi Yedekle", eşleştirme
   listesi (aç/kapat/sil) ve son 100 işlem kaydı.
4. **Ayarlar** – SMB IP/paylaşım/kullanıcı/şifre, "Bağlantıyı Test Et",
   otomatik yedekleme anahtarı ve 1/3/6/12/24 saat aralık seçimi.
5. **Klasör Eşleştirme** – Yerel MediaStore bucket'larından birini ve SMB
   üzerindeki hedef klasörü seçip eşleştirme oluşturur.

## Fotoğraf görüntüleyici

Fotoğrafa dokununca tam ekran `HorizontalPager` açılır:
- Sağa/sola kaydırarak önceki/sonraki fotoğrafa geçilir.
- `beyondViewportPageCount = 1` ile komşu sayfalar önceden hazırlanır.
- Çift dokunma veya iki parmakla yakınlaştırma (özel `awaitEachGesture` motoru);
  iki parmak pinch, tek parmak pan, zoom=1'de tek parmak sayfa kaydırma yapar.
- Görsel açılırken dev spinner yerine 256 px küçük resim bulanık arka plan
  (`Modifier.blur`) + köşede minik ilerleme halkası gösterilir.
- Üstte geri butonu ve dosya adı, altta `3 / 5000` sayacı gösterilir.
- Cihaz, SMP ve Koleksiyon detayındaki listelerin tamamı görüntüleyiciye
  `List<ViewerPhoto>` olarak verilir.

### Google Photos tarzı özellikler

- **Alt aksiyon çubuğu:** Oynat (yalnız video) / Paylaş / Düzenle / Detaylar / Sil.
  - Paylaş: yerel `content://` URI doğrudan verilir; SMB dosyası önce
    `cache/shared` altına kopyalanıp **FileProvider** ile yetkilendirilir.
  - Düzenle: `ACTION_EDIT` ile sistem/üçüncü parti düzenleyici açılır.
  - Sil: yerelde `MediaStore.createDeleteRequest` (sistem onayı), SMP'de
    sunucuda `SmbFile` silme + listenin yerinde güncellenmesi.
- **Detay paneli:** Resmi yukarı kaydırınca (veya Detaylar ile) açılır:
  çekim tarihi, boyut, EXIF kamerası, GPS koordinatı + `Geocoder` adresi ve
  "Google Haritalar'da aç" (`geo:` intent — API anahtarı gerekmez).
- **Hareketli küçük resimler:** Izgarada videolar (film rozeti) ve Google
  Motion Photo'lar (LIVE rozeti) kaydırma duraklayınca veya uzun basınca
  sessiz-döngülü önizleme oynatır. Motion Photo'nun sonuna gömülü MP4
  trailer'ı XMP `MicroVideoOffset` değeriyle `cache/motion` altına çıkarılır
  (`MotionThumbnails.kt`). Coil'e `coil-video` (VideoFrameDecoder) eklendi;
  ızgara artık MediaStore'dan videoları da ilk kareleriyle gösterir.
- **Anılar:** Izgaranın en üstünde "N yıl önce bugün" kartları; her gün
  tarihi otomatik değişir. Karta dokununca anı 3,5 sn'de bir ilerleyen
  slayt gösterisi olarak açılır (`util/Memories.kt`).

## Performans ve önbellek

Coil için önbellek ve eşzamanlılık ayarları `ImageLoaderModule.kt` içinde yapılır.
1 TB disk için agresif ama güvenli değerler seçilmiştir:

- **Bellek önbelleği:** Uygulamaya ayrılan toplam belleğin `1/4`'ü (%25) kullanılır.
  - 256 MB heap → 64 MB → ~250 küçük resim (256×256 ARGB_8888)
  - 512 MB heap → 128 MB → ~500 küçük resim
  - 1 GB heap → 256 MB → ~1.000 küçük resim
  - Kalan %75; Compose, Room, SMB tamponları için yeterli yaşam alanı bırakır.
- **Coil disk önbelleği:** `cacheDir/image_cache` altında **30 GB** yer ayrılır.
  ~6.000 tam boy fotoğraf (viewer) yerel diskten açılabilir.
- **Yerel thumbnail önbelleği:** `cacheDir/thumbs` altında **10 GB** (`ThumbnailCache.kt`).
  SMB'den bir kez indirilen 256×256 WebP küçük resimler (~12 KB) burada saklanır.
  Sonraki kaydırmalarda SMB'ye hiç gidilmez; milisaniyelerde gösterilir.
  10 GB ≈ 800.000 küçük resim kapasitesi.
- **Paylaşılan SMB oturumu:** NTLM el sıkışması sadece bir kez yapılır; tüm görsel
  istekleri aynı TCP bağlantısını kullanır. Bağlantı koparsa oturum otomatik yenilenir.
- **Çift indirme yok:** SMB görsellerinde 20×20 progressive placeholder atlanır;
  aynı dosya iki kez indirilmez. Yerel görsellerde progressive placeholder kullanılır.
- **Paralel indirme:** OkHttp `Dispatcher` üzerinden `maxRequests = 16`,
  `maxRequestsPerHost = 8` sınırları uygulanır. Coil fetcher/decoder işleri de
  sınırlı paralellikte çalışır.
- **SMB okuma tamponu (agresif):** jcifs-ng için `rcv_buf_size = snd_buf_size = 262144`,
  `tcpNoDelay = true`, `responseTimeout = 30000`. SMB3 çoklama (multiplexing)
  sınırları yükseltilir: `maxMultiplexed = 80`, `initialCredits = 512`,
  `maxCredits = 8192`; böylece tek TCP bağlantısı üzerinden 80 SMB isteği
  aynı anda uçabilir. Performans için `signingPreferred = false` (yerel ağ).
- **Küçük resim boyutu:** Izgarada her görsel `256×256` istenir. Tam boyutta
  ~160 KB+ yerine ~65 KB civarı veri işlenir.
- **Adaptif grid sütunları:** `GridCells.Adaptive(minSize = 104.dp)` ile ekrana
  göre otomatik sütun sayısı. Düşeyde ~4, yatayda ~9 sütun (Google Photos tarzı).
- **Dinamik read-ahead:** `PreloadHelper.kt` kaydırma hızını ölçerek pencereyi
  0.6–4 ekran arasında otomatik ayarlar; gidilen yönde genişler, ters yönde
  daralır. Pencere dışı bekleyen istekler `dispose()` ile iptal edilir. Ayrıntı
  "NAS benzeri veri motoru" bölümünde.
- **Önbellek anahtarı:** SMB dosyaları için `yol + son değiştirilme tarihi`, yerel
  dosyalar için `uri + tarih + boyut` kullanılır. Dosya değişirse anahtar değişir
  ve bayat görsel gösterilmez.
- **Boyut bazlı bellek anahtarları:** `@placeholder20`, `@thumb200` ve `@full`
  ayrı bellek girişleridir; aynı dosyanın farklı çözünürlükleri karışmaz.

### 1 TB disk hesaplaması

| Öğe | Boyut | Kapasite |
|---|---|---|
| ThumbnailCache (256×256 WebP) | 10 GB | ~800.000 küçük resim |
| Coil disk önbelleği (tam boy) | 30 GB | ~6.000 fotoğraf (viewer) |
| Bellek önbelleği (1/4 heap) | 64-256 MB | ~250-1.000 küçük resim |
| **Toplam disk kullanımı** | **~40 GB** | **1 TB'ın %4'ü** |

### Akıcılık: 120 Hz ve kaydırma jank'i düzeltmeleri

Google Photos seviyesinde akıcılık için iki koldan çalışıldı:

**1. 120 Hz ekran (MainActivity.kt)**
- Android'in varsayılan "uyarlanabilir" yenileme davranışı, statik bir ızgarada
  sistemi çoğu zaman 60 Hz'de tutar. `MainActivity.enableMaxRefreshRate()`:
  - Native çözünürlüğe sahip display modları arasından en yüksek `refreshRate`'i
    seçer ve `WindowManager.LayoutParams.preferredDisplayModeId`'yi ona sabitler
    (API 23+).
  - API 30+ üzerinde `Window.setFrameRate(FIXED_SOURCE)` ile sistemi bu kare
    hızında kalmaya yönlendirir (bazı SDK derlemelerinde bu metod görünmediği
    için reflection ile çağrılır; yoksa sessizce atlanır).
- Donanım 120 Hz'i desteklemiyorsa uygulama sessizce 60 Hz'de kalır, çökmez.
- Doğrulandı (Pixel 10 Pro Fold): `dumpsys display` çıktısında uygulamamızın
  uid'si için `frameRateHz=120.00001` kilitlenmiş durumda.

**2. Kaydırma jank'i düzeltmeleri (kaynak → çözüm)**

| Kaynak | Eski davranış | Yeni davranış |
|---|---|---|
| `imageStates` | `StateFlow<Map>` her thumbnail yüklenince yeni map yayınlar, 1065 hücrenin tamamı yeniden çizilirdi | `MainScreen`'de `mutableStateMapOf<String, ImageLoadState>` (SnapshotStateMap) ile anahtar-bazlı gözlem; sadece değişen hücre yeniden çizilir |
| `GridImage` ImageRequest | Her recomposition'da yeni `ImageRequest.Builder().build()` → allocation churn | `remember(model, baseCacheKey)` ile yalnızca anahtar değişince üretilir |
| Crossfade (ızgara) | `crossfade(true)` her yüklenen thumbnail'da alpha animasyonu = ekstra GPU işi | Izgarada `crossfade(false)`; viewer'da animasyon korunur |
| Fling sırası ön yükleme | 2 ekran önden yük, bağlantıyı görünür alandan çalardı | Hız algısına göre dinamik pencere + pencere dışı istekler `dispose()` ile iptal |

Böylece tek bir küçük resim yüklenince 1065 hücre değil, yalnızca **1 hücre**
yeniden çizilir; `ImageRequest` her karede yeniden oluşturulmaz; crossfade
kaybolur. Bu üçünün toplamı, 120 Hz'in gerçekten 120 fps'de çalışmasını sağlar.

### NAS benzeri veri motoru (read-ahead + QoS)

Uygulamanın depolama katmanı, bir NAS'ın disk denetleyicisi gibi tasarlandı:

**1. Hıza duyarlı read-ahead (`PreloadHelper`)**
- Kaydırma hızı (saniyede kaydırılan hücre) ~100 ms'de bir ölçülür:

| Durum | Hız | Pencere (yön tarafı) | Karşı yön |
|---|---|---|---|
| Duraklama | 0 | **4 ekran** (×1.5) | 0.5× |
| Yavaş sürükleme | < 15 hücre/sn | 2 ekran | 0.5× |
| Normal fling | 15-40 hücre/sn | 1 ekran | 0.5× |
| Hızlı fling | > 40 hücre/sn | 0.6 ekran | 0.5× |

- **Yön biasesı:** gidilen yöndeki pencere 1.5 kat, arkadaki 0.5 kat. NAS'lar
  sequential okumada sürüklenme yönünü önceliklendirir.
- **Throttle:** kararlar `sample(100ms)` ile alınır; her karede kuyruk yeniden
  düzenlenmez.
- Pencere dışına düşen bekleyen indirmeler `dispose()` ile anında iptal edilir.

**2. QoS zamanlayıcı (`SmbImageFetcher`)**
- İki ayrı semafor: `visibleGate` (8 izin) kullanıcı bekleyen istekler,
  `prefetchGate` (3 izin) `LowPriorityTag` etiketli arka plan istekleri.
- Ön yükleme ne kadar yoğursa olsun **görünür bir kare asla arka plan
  trafiğinin arkasında beklemez** — NAS'ların foreground/background I/O
  ayrımı (cgroup ioclass) bunun aynısıdır.
- Toplam SMB eşzamanlılığı 11 okumada sabitlenir: sunucuyu boğmadan tatlı nokta.

**3. İstek birleştirme / coalescing (`SmbImageFetcher.inFlight`)**
- Aynı dosyayı aynı anda isteyen prefetch + görünür istek (ör. kullanıcı
  tam prefetch anında kutuya tıklar) için dosya **tek kez** indirilir; ikinci
  istek çözümlenmiş bitmap'i bellekten devralır (`DataSource.MEMORY_CACHE`).
- SMB'de çift bant genişliği tüketimi sıfıra iner.

**4. Görüntüleyici sıralı read-ahead (`ViewerScreen`)**
- N. fotoğrafa bakarken N+1, N+2 (ileri) ve N-1 (geri) sayfaların TAM boyutu
  prefetchGate arkasında bellek önbelleğine indirilir. Zaten bellekte olan
  sayfa için ağa hiç çıkılmaz (`memoryCache.get` kontrolü).
- Kullanıcı kaydırdığı anda tam fotoğraf bellekten gelir; disk kafası komşu
  sektörde hazır bekler gibi.

**Bant genişliği bütçesi (SMP, 30 fotoğraf):**

| Katman | Veri | Sonuç |
|---|---|---|
| EXIF-only thumbnail (yerine tam dosya) | 256 KB yerine 30 KB ortalama | ~%90 daha az trafik |
| İstek birleştirme | Ortalama 1.0 yerine 1.0 indirme (önceden 1.4) | ~%30 tasarruf |
| ThumbnailCache (10 GB WebP) | 0 KB (disk hit) | ms seviyesinde yükleme |
| QoS | — | Görünür kare gecikmesi prefetch'ten bağımsız |

## Eksik fotoğrafların otomatik tamamlanması

`MissingPhotoRefresher` izgaradaki küçük resimlerin yükleme durumunu takip eder:
`PENDING`, `LOADED`, `FAILED`.

- Başarısız görseller anahtar + kaynak + deneme sayısı ile Room tablosuna yazılır.
- Izgara hücresinde başarısızlık küçük yenileme ikonuyla gösterilir.
- Cihaz/SMP ekranında eksik varsa `N eksik` düğmesi çıkar; dokununca düşük
  öncelikli ve en fazla 2 eşzamanlı istekle yeniden denenir.
- Uygulama `ON_RESUME` olduğunda otomatik tamamlama denenir.
- Bir görsel en fazla 3 kez denenir; sonra kullanıcıya bırakılır.
- `MissingPhotoWorker` uygulama kapalıyken de 12 saatte bir, yalnızca WiFi ile,
  en fazla 50 eksik kaydı yeniden dener.
- Tüm eksikler tamamlandığında Snackbar ile bilgi verilir.

## Yedekleme mantığı

- Her fotoğraf için `SHA-256(displayName | size | dateModified)` anahtarı üretilir.
- Room'da `(mappingId, localKey)` birincil anahtardır. Aynı anahtar `SUCCESS`
  durumundaysa dosya atlanır; dosya değişmişse hash değişir ve yeniden yüklenir.
- Yükleme önce `dosya.jpg.part` olarak yapılır, başarılıysa hedef ada taşınır.
  Böylece yarıda kalan aktarım bozuk dosya bırakmaz.
- Yükleme sonrası SMB tarafındaki `lastModified` telefonla eşitlenmeye çalışılır.
- Hatalar kayda yazılır; WorkManager üstel geri çekilme (backoff) ile yeniden dener.
  Bir sonraki periyodik turda başarısız kayıtlar tekrar denenir.

## Tarih sıralaması

- MediaStore `DATE_TAKEN` (ms) kullanılır; boşsa `DATE_MODIFIED` (saniye → ms
  normalize edilir) devreye girer.
- SMB listelemede `SmbFile.lastModified()` gösterilir.

## Derleme

```bash
# Android Studio ile açın veya:
./gradlew assembleDebug
```

- `compileSdk = 35`, `minSdk = 26`, `targetSdk = 35`
- JDK 17+ (gradle.properties'te belirtilen JVM hedefi 17)
- `local.properties` içindeki `sdk.dir` kendi Android SDK yolunuza göre ayarlanmalı.

## İzinler

- Android 13+: `READ_MEDIA_IMAGES`
- Android 14+: `READ_MEDIA_VISUAL_USER_SELECTED` (kısmi erişim)
- Android 12 ve altı: `READ_EXTERNAL_STORAGE`
- `INTERNET`, `ACCESS_NETWORK_STATE`

## SMB notları

- jcifs-ng SMB1 ile SMB3.1.1 arasını destekler; modem USB paylaşımları genelde
  SMB1, NAS cihazları SMB2/3 kullanır.
- Varsayılan sunucu adresi boştur (kurulum sırasında kullanıcı yazar), port `445`.
- Paylaşım adı örneği: `USB` veya `sda1`. Kullanıcı adı/şifre boş bırakılırsa
  misafir (guest) oturumu denenir.
- Telefon ve modem aynı yerel ağda olmalıdır.
- "Bağlantıyı Test Et" paylaşım kökünü listeleyerek kimlik doğrulamayı doğrular.

## Proje yapısı

```
app/src/main/java/com/smpmedia/
├── SmpMediaApp.kt              # Hilt + WorkManager yapılandırması
├── MainActivity.kt
├── data/
│   ├── local/                  # Room entity / dao / database
│   ├── model/                  # PhotoItem, SmbEntry, SmbSettings, ...
│   ├── prefs/                  # EncryptedSharedPreferences
│   └── repository/             # SmbRepository, PhotoRepository, BackupRepository
├── di/                         # Hilt modülleri
├── ui/
│   ├── components/             # Izgara hücresi, görüntüleyici, hareketli önizleme
│   ├── screens/                # Compose ekranları
│   ├── theme/
│   └── viewmodel/
├── util/                       # Hash, izinler, tarih/biçim, Anılar yardımcıları
└── work/                       # BackupWorker + BackupScheduler
```

## Bilinen sınırlar

- Yükleme sırasıyla (paralel değil) yapılır; modem USB paylaşımlarında bu daha
  güvenilirdir.
- SMB dosyaları görüntüleyiciden silinebilir; ancak SMP ızgarasında videolar
  listelenmez ve hareketli önizleme yalnızca yerel medya içindir (ağ maliyeti).
- Yedekleme yalnızca resimleri kapsar; MediaStore'daki videolar ızgarada
  görünür ama SMB'ye yedeklenmez (`getPhotosForBucket` resim süzgeci).
- Çok büyük galerilerde ilk yedekleme uzun sürebilir; WorkManager işi uygulama
  kapatılsa da devam eder.
