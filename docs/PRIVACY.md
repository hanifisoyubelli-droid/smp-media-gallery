# Privacy

## Short version

SMP Media does not collect, upload or sell anything. It has no account system, no analytics SDK, no ad
SDK, no crash reporter and no server of ours. The only network traffic the app produces is between
your phone and the SMB share or Nextcloud instance **you** typed into Settings.

If your phone is offline, the app still works with your cached photos and your device library.

## What leaves the device

| Traffic | Destination | Why |
|---|---|---|
| SMB session setup and file reads | The SMB host you configured | Browsing the remote timeline, thumbnails, playback |
| WebDAV `PROPFIND` / `GET` / `HEAD` / `COPY` / `MKCOL` / `DELETE` | The Nextcloud URL you configured | Same, over HTTP |
| Uploads of your phone photos | The SMB target folder you mapped | The backup feature you started |
| Android system geocoding request | Android's geocoding provider (device-level service) | Only when you open the photo details panel for a photo that has GPS coordinates, to show a human-readable address |

There is no other outbound connection. The dependency list is small and well known: AndroidX,
Jetpack Compose, Hilt, Room, WorkManager, Coil, OkHttp, `jcifs-ng`, SLF4J-noop. Nothing in that list
phones home, and no analytics library is included.

The one honest caveat is the geocoder: address lookup uses the Android platform `Geocoder`, which is
implemented by whatever geocoding provider your device has (often Google Play services). The
coordinates come from your photo's EXIF. If you do not want that, decline the location permission /
disable on-device geocoding in system settings, or simply do not open the details panel for geotagged
photos. The app itself never sends coordinates anywhere on its own.

## Credentials

Server passwords are **not** written to disk in plain text.

- SMB settings (host, share, domain, user, password, port, target folder) and Nextcloud settings
  (address, user, app password, scan folder, TLS switch) are stored in `EncryptedSharedPreferences`.
- The master key lives in the **Android Keystore** (`AES256_GCM`); keys are `AES256_SIV` /
  values `AES256_GCM`.
- SMB and Nextcloud use two separate storage files on purpose, so clearing one cannot wipe the other.

Honest edge cases, because they matter for a security claim:

- If the Keystore entry is broken — a known side effect of restoring an app onto a different device —
  the encrypted file is deleted once and recreated. If that still fails, the app falls back to ordinary
  preferences so it does not become permanently unusable. In that rare state credentials are no longer
  encrypted at rest, though they still never leave the device.
- `android:allowBackup="false"` is set, so Android's cloud backup does not carry your credentials to
  another device.
- A device with root access, or a full backup of app storage, can read whatever the app can read. This
  is normal Android threat-model stuff: the app protects against casual reading and network theft, not
  against a rooted phone in an attacker's hands.

## Data stored on the device

| Where | What | Notes |
|---|---|---|
| `EncryptedSharedPreferences` | Server addresses, user names, passwords, TLS switch | Encrypted, see above |
| Room database | Folder mappings, backup records (file name, size, hash key, status), failed-thumbnail queue | Needed to avoid re-uploading the same photo |
| Regular preferences | Folder hide filters, grid warming progress, memory cards, gallery settings | No credentials |
| `Android/data/com.smpmedia/files/media_cache/` | WebP thumbnails, full-size image cache, downloaded originals, extracted Motion Photo videos | Size-capped (10 GB / 30 GB / 40 GB), oldest removed first. Lives here on purpose: `cacheDir` gets emptied silently by the system, which would make the gallery slow again overnight |

File names and folder paths from your archive do appear in the app's own log output, because "which
file failed" is the only useful debugging information. Logs are not uploaded anywhere; read them with
`adb logcat -s SmpMedia` if you want.

When you accept a self-signed certificate, its subject and SHA-256 fingerprint are logged once, so you
can compare it with the certificate on your server.

## Deleting your data

- **Remove a connection:** Settings → clear the fields and save, or just disable the source.
- **Delete caches and history:** system Settings → Apps → SMP Media → Storage → Clear storage / Clear
  cache.
- **Remove everything:** uninstall. The app-specific cache directory and databases go with it.
- **Revoke app-side access:** delete the app password in Nextcloud (Settings → Security). That is the
  cleanest "undo" and works even if the phone is lost — which is why the app asks for an app password
  rather than your account password.

## Permissions, and why

| Permission | Why it is needed |
|---|---|
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` | Show your library (Android 13+) |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Support Android 14 partial access |
| `READ_EXTERNAL_STORAGE` (`maxSdkVersion 32`) | Same, on Android 12 and older |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Talk to your server |
| `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | The opt-in "keep SMP alive" service that refreshes the timeline and keeps the SMB session warm |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Only used to open the system battery-optimization exemption screen from Settings; you confirm it yourself |

No camera, no contacts, no location permission, no background data collection.

## Changes to this page

If you add a dependency that performs network calls of its own, this page becomes wrong. Contributors:
update it in the same pull request.
