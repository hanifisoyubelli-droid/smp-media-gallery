# SMP Media

A Google Photos style gallery for Android that shows the photos that live on **your own servers**:
an SMB share (for example a USB disk plugged into your modem/router) or a self-hosted Nextcloud
instance over WebDAV.

There is no account, no cloud of ours, no analytics. The app is a client for hardware you already own.

<p align="center">
  <!-- SCREENSHOT PLACEHOLDER — replace this comment with real images before publishing.
       Suggested files (put them in docs/screenshots/):
         timeline.jpg   -> timeline grid with day/month headers and the scrubber on the right
         viewer.jpg     -> full-screen viewer, details sheet open (EXIF + map link)
         settings.jpg   -> Settings screen: SMB section and Nextcloud section
         editor.jpg     -> built-in crop/rotate editor
       Example markup:
         <img src="docs/screenshots/timeline.jpg" width="270" alt="Timeline grid">
         <img src="docs/screenshots/viewer.jpg"   width="270" alt="Photo viewer">
       Do not publish screenshots that contain real share names, user names or server addresses. -->
</p>

---

## What it does

- **One timeline, many sources.** Device photos and the remote archive appear in the same scrolling
  grid, grouped by day with month headers. A photo that is on the phone and also on the server is
  listed once. A side scrubber lets you jump through years quickly.
- **Two remote sources** (you pick one as the active source in Settings):
  - **SMB** share via `jcifs-ng` — typical for USB storage shared by a modem/router or a small NAS.
  - **Nextcloud** over WebDAV via OkHttp — PROPFIND for listing, GET (with HTTP `Range`) for pixels.
- **Real capture dates, not transfer dates.** Batch copies on a NAS often destroy file dates, so the
  app reads EXIF `DateTimeOriginal` while it decodes a photo and feeds that date back into the
  timeline.
- **Correct orientation.** JPEG EXIF orientation and the MP4 `tkhd` display matrix are read in one
  place, so grid cell, live preview and player agree on the angle.
- **Live photos.** A JPEG that has a same-named `.mp4` next to it (Google Takeout style), or a Google
  Motion Photo with an embedded MP4 trailer, plays a muted loop while you keep your finger on the
  cell.
- **Built-in editor.** Rotate in 90° steps and crop, without leaving the app. The result is **always
  saved as a new file on the phone** (`Pictures/SMP Düzenlenen`); the original in the cloud is never
  modified.
- **Server-friendly copying.** You can copy a photo into another folder on the server. On Nextcloud
  this is a WebDAV `COPY`, so the file moves disk-to-disk and never travels through the phone.
- **Viewer actions.** Share (local URIs directly, remote files via a `FileProvider` cache copy),
  details panel with EXIF camera, size, GPS coordinates and a `geo:` map link (no API key), delete on
  the server after confirmation.
- **Backup of phone photos to SMB.** Folder-to-folder mappings, run by WorkManager on a schedule you
  choose (1/3/6/12/24 h) or on demand. Uploads go to `name.jpg.part` first and are renamed only after
  success, so an interrupted transfer never leaves a broken file.
- **Grid warming (`GridWarmer`).** An optional background pass that downloads thumbnails ahead of
  time and stores them as WebP on disk, so scrolling hits the disk instead of the network. It walks
  the newest photos first, stops after five consecutive errors and resumes where it stopped.
- **Memory protection.** A large archive can produce `OutOfMemoryError` while decoding thumbnails.
  The fetcher catches it, clears Coil's memory cache and shows a placeholder for that one cell instead
  of crashing the app.
- **Cache size limits.** Cache lives in app-specific storage (`Android/data/com.smpmedia/files/media_cache`)
  so Android does not silently empty it: 30 GB for full-size images (Coil), 10 GB for WebP thumbnails,
  40 GB cap for downloaded originals, oldest-first cleanup.
- **Smooth scrolling.** The app asks the display for its highest refresh-rate mode (120 Hz on hardware
  that supports it) and falls back to 60 Hz quietly when it does not.
- **Folder filters.** Hide a folder from the timeline; hiding a parent folder hides everything below it.
- **"Anılar" (Memories).** "N months/years ago" cards at the top of the grid, opening as a slideshow.
- **Missing-photo repair.** Thumbnails that failed are recorded in a Room table and retried later with
  low priority — including a WorkManager pass while the app is closed.

### The transport layer never fans out

This is the most important design rule in the app, and it comes from measurements, not theory:

- On Nextcloud, **one request at a time** is allowed in flight (a second gate of one permit guards
  long streams such as full-size images and video downloads). Real measurement on a small
  PHP/PHP-FPM server: 3-8 concurrent requests took PHP-FPM down and nginx answered `502`; many fast
  authenticated requests triggered Nextcloud's brute-force protection (`412`).
- On SMB, the app keeps **one shared session** and never opens a second one. Some modem/router Samba
  builds refuse a second connection, and eleven parallel requests meant eleven refused connections and
  a grid full of red badges.
- On `412` / `429` / `502` / `503` the app does **not** retry harder. It backs off and lets the server
  recover.
- Visible cells always win over background work: foreground and prefetch traffic run in two separate
  queues, so warming never blocks what you are looking at.

If your self-hosted server is small, this app should keep it alive. See [docs/SELF_HOSTED.md](docs/SELF_HOSTED.md).

## What it does **not** do

- It is **not** a cloud. It uploads nothing to any server except the one you configured.
- It is **not** a two-way sync tool. It does not mirror folders, it does not delete local files after
  upload, and it does not reconcile edits back to the server.
- Scheduled backup is **images to SMB only**. Nextcloud is read for browsing, plus server-side copy
  and small uploads from the viewer.
- It does not upload videos in the background.
- Video cells in the remote grid show a real first frame only if that video was already downloaded
  once; the app will not stream a whole video over the network just to make a thumbnail.
- It does not do on-device machine learning (no face, text or object search).
- It is not on Google Play, and there is no auto-update.

## Screens

| Screen | What is there |
|---|---|
| **Fotoğraflar** (Photos) | The timeline: device + active remote source, day/month headers, side scrubber, memories cards, multi-select for share/delete |
| **Koleksiyonlar** (Collections) | Local folders, backup state per folder, opens a folder grid |
| **Yedekleme** (Backup) | Status card, progress, "backup now", mapping list, last operations log |
| **Klasör Eşleştirme** (Folder mapping) | Pair a local folder with a folder on the SMB share |
| **Ayarlar** (Settings) | SMB connection, Nextcloud connection, active source, grid warming, auto-backup interval, background protection |

The interface text is currently Turkish only.

## Supported servers

| Source | Works with | Notes |
|---|---|---|
| SMB | Anything `jcifs-ng` speaks (SMB1 up to SMB3.1.1) — modem/router USB shares, small NAS boxes | Default port 445. Empty user/password tries a guest session. Phone and server must be on the same network. |
| Nextcloud | Any instance that serves standard WebDAV under `/remote.php/dav/files/<user>` | HTTPS strongly recommended. Uses an **app password**, not your login password. |

## Install

1. Download the latest `SMPMedia-*.apk` from [Releases](../../releases).
2. On Android 8+ allow "install unknown apps" for your browser, then install.
3. Open the app and allow photo access. On Android 14 you can grant partial access.
4. Settings → enter your SMB server, or enable Nextcloud and fill the fields.

Minimum Android version: **8.0 (API 26)**.

Permissions used: `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`
(Android 14 partial access), `READ_EXTERNAL_STORAGE` (Android 12 and older), `INTERNET`,
`ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, and a foreground service of type `dataSync` for the
opt-in "keep SMP alive in my pocket" switch.

## Build from source

Requirements: JDK 17, Android SDK 35. Gradle 8.9 and Android Gradle Plugin 8.7.3 come from the wrapper
and the root build file; Kotlin is 2.0.21.

```bash
# Point local.properties at your SDK (Android Studio does this for you)
#   sdk.dir=/path/to/Android/Sdk

./gradlew assembleDebug      # debug APK, signed with the debug key
./gradlew assembleRelease    # release APK
```

Both APKs land in `app/build/outputs/apk/<variant>/`.

The `release` build type has **no signing config in the repository**, so `assembleRelease` produces an
unsigned APK on purpose. Add your own `signingConfig` (and keep the keystore out of git) if you want a
distributable release build, or just install the debug build.

## Nextcloud in three steps

1. In Nextcloud: **Settings → Security → "Add app-specific password"** (Turkish:
   *Ayarlar → Güvenlik → Üstüme bir uygulama şifresi ekle*). Copy the generated password.
2. In the app: enable Nextcloud, set the server address (for example `https://nextcloud.example.com`),
   your user name (`alice`), and paste the **app password**. Your login password will usually be
   rejected or will make the brute-force protection angry.
3. Optionally set the folder to scan; empty means the whole account.

The app builds its WebDAV root as `<server>/remote.php/dav/files/<user>`. Full guide, including
reverse-proxy notes and what happens with a self-signed certificate:
[docs/SELF_HOSTED.md](docs/SELF_HOSTED.md).

## Security notes

- **Passwords are stored encrypted.** SMB and Nextcloud settings live in `EncryptedSharedPreferences`
  with an AES-256-GCM master key from the Android Keystore. If the Keystore is broken (a common result
  of restoring a device backup), the file is deleted once and rebuilt; only if that also fails does the
  app fall back to plain preferences so it does not stay unusable.
- **TLS is strict by default.** System trust anchors plus hostname verification. A self-signed,
  expired or wrong-name certificate fails, and the error message tells you what to do.
- **"Trust my own certificate" is off by default.** Turning it on for a home server that cannot get a
  public certificate relaxes *chain trust only*:
  - the last certificate in the chain must genuinely be **self-signed** (your own leaf cert or your own
    small CA such as mkcert),
  - validity dates are still checked,
  - weak keys and signatures are rejected (RSA < 2048 bits, EC < 224 bits, MD5/SHA-1),
  - **hostname verification is always on** — a different server with the same certificate is still refused,
  - the certificate fingerprint (SHA-256) is logged once so you can compare it with the one on your server.
- **No certificate pinning, on purpose.** Shipping one person's certificate would break the app for
  everybody else.
- Cleartext HTTP is allowed, because many home Nextcloud installs speak plain HTTP on the LAN. With
  plain HTTP your app password travels unencrypted inside your network — use HTTPS if you can.
- `android:allowBackup="false"`: server credentials are kept out of Android's cloud backup.

More: [docs/PRIVACY.md](docs/PRIVACY.md).

## Privacy

The app has no telemetry, no analytics, no crash reporting and no ad SDK. Logs stay on the device
(`adb logcat`). The only network traffic is between the app and the server you typed in.
Details: [docs/PRIVACY.md](docs/PRIVACY.md).

## Known limits

- Because requests are serial on purpose, the **first** scan of a very large tree takes time. Later
  visits are cached.
- Big archives need warm-up: use Settings → Grid warming before you expect Google Photos speed.
- Only images are covered by scheduled backup.
- A remote video shows a real thumbnail only after it was downloaded once.
- The UI is Turkish only.
- Foldable and tablet layouts were tested on a small set of devices; report problems, do not assume.
- This is a personal project driven by one real archive. Expect rough edges.

## Tech stack

Kotlin 2.0.21 · Jetpack Compose + Material 3 · MVVM + Hilt · Room · Coil (+ `coil-video`) ·
`jcifs-ng` 2.1.10 for SMB · OkHttp for WebDAV · WorkManager for scheduled jobs ·
`EncryptedSharedPreferences` for credentials.

```
app/src/main/java/com/smpmedia/
├── MainActivity.kt              # edge-to-edge + highest refresh rate
├── data/
│   ├── image/                   # Coil fetchers, WebP thumbnail cache, memory guard
│   ├── local/                   # Room entities and DAOs
│   ├── model/                   # shared models for both sources
│   ├── net/                     # NextcloudTls
│   ├── prefs/                   # encrypted settings, folder filters
│   ├── repository/              # SmbRepository, NextcloudRepository, CloudPhotoStore, backup
│   ├── sync/                    # opt-in foreground service
│   └── warm/                    # GridWarmer
├── di/                          # Hilt modules (Coil sizing lives here)
├── ui/                          # screens, components, view models
├── util/                        # EXIF/date helpers, MP4 rotation, memories
└── work/                        # WorkManager workers
```

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) first — the code comments are Turkish, and there are a few
performance rules that are easy to break by accident.

## License

MIT — see [LICENSE](LICENSE).

Third-party libraries keep their own licenses (Apache 2.0 for the AndroidX/Coil/OkHttp stack,
LGPL for `jcifs-ng`).

## Disclaimer

This is a personal project that grew out of one real 1 TB family archive. It is provided **as is**,
without any warranty. Always keep a backup of your photos somewhere else before letting any app touch
your library, including this one. The author is not responsible for data loss.

---

## Kısa Türkçe Özet

SMP Media, fotoğraflarını kendi sunucusunda (SMB paylaşımı veya kendi kurduğun Nextcloud) tutanlar için
yazılmış, Google Photos benzeri bir Android galeri uygulaması. Bulut hesabı yok, analiz yok; uygulama
yalnızca senin yazdığın adrese bağlanır.

- **Zaman tüneli:** Cihaz + sunucu aynı ızgarada; gün/ay başlıkları, sağda yıl kaydırıcı.
- **Kaynaklar:** SMB (modem USB paylaşımı, NAS) veya Nextcloud WebDAV. Aktif kaynağı Ayarlar'dan seçersin.
- **Tarih:** NAS'a toplu kopyalama dosya tarihlerini bozduğu için gerçek çekim tarihi EXIF'ten okunur.
- **Canlı fotoğraf:** Basılı tutunca oynar; düzenleyici döndür+kırp yapar ve sonucu **her zaman telefona
  kopya** olarak kaydeder — buluttaki özgün dosyaya dokunmaz.
- **Sunucuya saygı:** İstekler SERİ gider (3+ eşzamanlı PHP isteği 502 üretiyor), hata varsa geri çekilir;
  ızgara ısıtma diske WebP yazar, bellek sıkışırsa uygulama çökmez.
- **Kurulum:** Releases'ten APK. Android 8.0 ve üzeri.
- **Nextcloud:** Şifre olarak **hesap şifreni değil**, `Ayarlar → Güvenlik → Üstüme bir uygulama şifresi ekle`
  ile ürettiğin uygulamaya özel şifreyi yaz. WebDAV kökü: `https://sunucu/remote.php/dav/files/kullanici`.
- **Kendinden imzalı sertifika:** Varsayılan kapalı; açarsan yalnızca gerçekten kendi imzaladığın
  sertifikalar kabul edilir, ana makine adı doğrulaması her zaman açıktır.
- **Yedekleme:** Telefon → SMB, WorkManager ile periyodik; videolar kapsamdışı.
- **Lisans:** MIT. Bu kişisel bir projedir ve **herhangi bir garanti vermez**; arşivinin yedeğini ayrıca tut.
