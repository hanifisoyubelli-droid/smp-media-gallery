# Running Nextcloud at home

This page is for people who host Nextcloud themselves — on a small box, a NAS, a Raspberry Pi or a
Termux install — and want to browse it with SMP Media. All examples use documentation addresses
(`nextcloud.example.com`, `192.0.2.10`, user `alice`). Replace them with your own.

> Short version: use an **app password**, use **HTTPS**, and do not be surprised that the app is slow
> on purpose. It is protecting your PHP server from itself.

---

## 1. What the app expects

SMP Media speaks plain, standards-based WebDAV:

| Operation | HTTP method |
|---|---|
| List a folder | `PROPFIND` with `Depth: 1` |
| Read properties of one path | `PROPFIND` with `Depth: 0` |
| Read image bytes (and the first block of a file for thumbnails) | `GET`, with `Range: bytes=…` |
| File size for a download progress bar | `HEAD` |
| Copy a photo to another folder | `COPY` (server-side, the file never comes to the phone) |
| Create folders | `MKCOL`, one path segment at a time |
| Delete | `DELETE` |

The WebDAV root is:

```
https://nextcloud.example.com/remote.php/dav/files/alice
```

In the app you do **not** type that whole path. You type:

- **Server address:** `https://nextcloud.example.com`
- **User name:** `alice`
- **App password:** the generated password from the next section

The app builds `<server>/remote.php/dav/files/<user>` itself. If you paste the full `/remote.php/...`
path into the address field it will be treated as part of the base URL and requests will 404.

If you installed Nextcloud in a subdirectory, include that subdirectory in the server address
(for example `https://example.com/nextcloud`). The app keeps the path as written.

**Folder to scan** (optional): if your photos live in `Photos`, type `Photos`. Empty means "walk the
whole account". Walking a huge account is the slowest thing this app can do — set this field.

## 2. Create an app password (not your account password)

Your Nextcloud login password is a bad idea here, and Nextcloud may block the app for using it:

- it is your master credential for the web UI, sync clients and everything else;
- it is protected by brute-force detection, which is exactly what an image grid can trip by accident;
- you cannot revoke it without changing your whole account.

An **app password** is a separate token that only works for this client and can be revoked alone.

How to create one:

1. Sign in to Nextcloud in a browser as the user the app will use (`alice`).
2. Open **Settings → Security**.
   Turkish UI: **Ayarlar → Güvenlik**.
3. Find **"Add app-specific password"** (Turkish: *"Üstüme bir uygulama şifresi ekle"*).
4. Give it a name such as `SMP Media` and confirm. Two-factor confirmation may be requested.
5. Copy the generated password immediately — it is shown once. It looks like a random string, not
   like a word.
6. Paste it into the app's **App password** field and save.

To revoke access later: same page, delete the token. The app will start returning `401` and nothing
else on your account is affected.

## 3. Use HTTPS (and a reverse proxy)

The app works over plain `http://`, because many home installs never leave the LAN and have no
certificate. But with HTTP your app password crosses the network in cleartext (HTTP Basic
authentication). On Wi-Fi that is a real risk: anyone on the same network can read it.

Recommended options, cheapest first:

- **LAN-only with a local name and a real certificate.** If you can use DNS-01 validation (many
  providers support it), you can get a Let's Encrypt certificate for `nextcloud.example.com` without
  exposing port 80/443 to the internet, and point that name at `192.0.2.10` on your router's DNS.
- **Reverse proxy in front of Nextcloud.** nginx, Caddy or Traefik on the same box terminate TLS and
  forward to PHP-FPM / the Nextcloud container. Keep the internal hop on localhost.
- **VPN instead of exposure.** WireGuard/Tailscale into your LAN, then HTTP inside the tunnel is much
  less scary. Still prefer HTTPS when it is easy.
- **Reverse proxy must forward WebDAV correctly.** Do not let it rewrite or buffer away `Range`,
  `Depth` and `Destination` headers. `client_max_body_size` matters for uploads from the viewer.
  Long reads: set `proxy_read_timeout` well above 120 seconds (the app's read timeout).

After changing the certificate or proxy, use Settings → "Test connection" (SMB) or simply reopen the
Nextcloud timeline.

## 4. Self-signed certificates

### What happens by default

The app trusts the Android system roots and verifies the hostname. A self-signed certificate — or a
private CA such as mkcert that Android does not know — fails with:

```
Trust anchor for certification path not found
```

This is intentional. Silently disabling verification on a local network means a man-in-the-middle can
collect the app password, and then your photos.

### Turning on "trust my own certificate"

Open **Settings → Nextcloud** and enable **"Kendi imzalı sertifikaya güven"** (trust self-signed
certificate). The switch is off by default.

What it does — and does not do:

| Check | When the switch is on |
|---|---|
| Last certificate in the chain is self-signed | **Required.** A chain rooted in a public CA that Android simply does not know is still rejected. |
| Certificate dates | Checked. Expired is expired. |
| Key strength | RSA ≥ 2048 bits, EC ≥ 224 bits. Weaker keys are rejected. |
| Signature algorithm | MD5 and SHA-1 are rejected. |
| **Hostname / SAN match** | **Always enforced.** |
| Fingerprint | SHA-256 written once to logcat so you can compare it with the server. |

So the switch does not mean "accept anything". It means "accept a chain that ends in a certificate you
signed yourself, if everything else is still valid".

> Security warning: only enable this for a server you control, on a network you trust. If an attacker
> can answer for `nextcloud.example.com` with their own self-signed certificate, hostname verification
> still protects you — but a compromised private CA key does not. Keep your CA key offline.

### How to see the fingerprint

```bash
adb logcat -s SmpMedia | findstr /i "sertifikasi SHA-256"
```

Compare it with:

```bash
openssl s_client -connect nextcloud.example.com:443 </dev/null 2>/dev/null \
  | openssl x509 -noout -fingerprint -sha256
```

## 5. PHP / PHP-FPM concurrency: why this client is serial

This deserves its own section because it is the most common source of confusion.

**SMP Media sends one WebDAV request at a time.** Not "a few", not "a pool of eight": one gate with one
permit, plus a second single-permit gate for long streams (full-size image, video download). Grid
warming also runs as a single loop with a pause between files.

That is not laziness. On a small home Nextcloud running PHP-FPM, measurements showed:

- every request costs roughly **0.7–4.5 s**, even unauthenticated ones;
- **3+ concurrent requests** were enough to exhaust PHP-FPM workers; nginx then answered **`502 Bad
  Gateway`**;
- a burst of authenticated requests tripped Nextcloud's brute-force protection and returned **`412`**.

The app reacts to `412 / 429 / 502 / 503` by **backing off**, not by retrying harder. Grid warming
stops after five consecutive errors and resumes on a later run.

### What this means in practice

- The first scan of a large folder is slow. That is the price of not knocking the server over.
- Scrolling can feel slow while the server is busy. Grid warming is the fix, not more concurrency.
- Do not add a second client that hammers the same install at the same time.

### Server-side tuning that helps

These are general Nextcloud/PHP suggestions, not app settings:

- **Raise `pm.max_children` modestly.** If your box has 1–2 GB RAM, `pm.max_children` of 4–6 with
  `pm = ondemand` is usually enough for a personal cloud. More workers than RAM allows will swap and
  make everything worse.
- **Enable OPcache** and keep Nextcloud/PHP patched. Much of the per-request cost is bootstrap.
- **Use a fast disk for the Nextcloud data directory.** Thumbnail and metadata reads dominate.
- **Keep preview generation out of the hot path.** The app deliberately does not use Nextcloud's
  `/core/preview` API for grid thumbnails; it reads a byte range of the original file and scales it on
  the phone. That is cheaper for the server but costs phone bandwidth — which is why the WebP cache
  exists.
- **Set the scan root** in the app to the photo folder instead of the whole account.
- If you run Nextcloud in Docker with a low `memory_limit`, 502s under load are usually PHP workers
  dying, not nginx.

## 6. Troubleshooting

| Symptom in the app | Likely cause | What to do |
|---|---|---|
| `401 Unauthorized` | Wrong user name, or account password used instead of an app password, or token revoked | Recreate an app password; check the user name is the Nextcloud login, not an e-mail alias |
| `412 Precondition Failed` / `429` | Brute-force protection or rate limit after a burst | Wait several minutes; the app backs off automatically. Check that the clock on the server is correct. |
| `502` / `503` | PHP-FPM workers exhausted, or the server is restarting | Raise `pm.max_children` a little; close other clients; retry later |
| `Trust anchor for certification path not found` | Self-signed or private CA | Install a proper certificate, or enable the self-signed switch (section 4) |
| `Hostname ... not verified` / certificate name mismatch | Certificate SAN does not match the address you typed | Use the name that is in the certificate, or reissue the certificate |
| "Server not found" instantly | Address missing `https://` for a TLS-only server, or wrong port | Include scheme and port, e.g. `https://192.0.2.10:8443` |
| Empty timeline, folders exist | Scan root points at an empty or wrong folder | Clear the folder field temporarily, or enter the exact path |
| Timeouts on video/large images | Reverse proxy read timeout too short | Raise `proxy_read_timeout` above 120 s |
| Everything is slow, no errors | Large account, cold cache | Set a narrower scan root; run Settings → grid warming |

If you open an issue, include the status code and the relevant logcat lines — **never** your password
or a URL containing a token.
