# Kage (影)

Clone Shizuku dari nol: **server privileged yang jalan sebagai shell/root** + **manager app** +
**client library**, semuanya di repo ini.

```
server/   dex yang dijalankan lewat app_process (uid 2000 kalau dari adb, 0 kalau dari su)
common/   protokol, JSON, kripto, BinderContainer - dipakai server & client
provider/ client library (Kage, KageProvider, KageServiceProxy) untuk app pihak ketiga
manager/  app Android: dashboard, manajemen izin, shell, toolbox, log, About
sample/   APK contoh yang memakai library Kage - bukti "dipakai app lain" + referensi integrasi
scripts/  build-server-dex.sh (javac + d8)
docs/     PANDUAN.md, DIPAKAI-APP-LAIN.md, ARSITEKTUR.md
```

Setiap rilis menyertakan tiga berkas: **manager-release.apk** (app utama),
**sample-release.apk** (app klien contoh), dan **kage-provider-<versi>.aar** (library siap pakai).

## Cara kerja singkat

1. Manager mengekstrak `server.dex` ke `Android/data/dev.kage.manager/files/kage/` dan menulis
   `start.sh` di folder yang sama.
2. Kamu menjalankan **satu** perintah lewat adb (atau root):

   ```sh
   adb shell sh /storage/emulated/0/Android/data/dev.kage.manager/files/kage/start.sh
   ```

   Script itu menjalankan `app_process` dengan dex tadi. Server hidup dengan uid 2000 (adb) /
   0 (root) dan menulis heartbeat ke folder bersama.
3. Server mengirim Binder-nya ke app lewat provider (`<pkg>.kage` → method `sendBinder`);
   kalau tidak memungkinkan, manager tetap bisa memakai **transport file** (request/response JSON
   yang ditandatangani token) di folder bersama itu.
4. Semua fitur manager (shell, grant/revoke izin, appops, debloat, toolbox) berjalan lewat server
   sehingga memakai hak akses shell/root.

## Fitur manager

- **Beranda** – status server (uid, mode, uptime, transport), perintah start siap-copy, diagnosa,
  sync izin, log.
- **Aplikasi** – daftar app yang meminta izin `dev.kage.permission.API`; toggle izin = `pm grant`/
  `pm revoke` + push ulang binder ke app itu.
- **Shell** – terminal interaktif ke server: output streaming, cancel, riwayat, snippet.
- **Alat** – freeze/unfreeze app, uninstall untuk user 0, force stop, clear data, appops,
  grant/revoke izin, doze whitelist, screenshot, screenrecord, trim cache, dan lain-lain.
- **Setelan** – debug log, paksa transport file, buat ulang token, info path.
- **Dialog izin** – app pihak ketiga minta izin → manager menampilkan dialog → grant via server.

## Build

Lokal:

```sh
export ANDROID_HOME=/path/ke-sdk
bash scripts/build-server-dex.sh              # opsional, dex sudah ikut di repo
./gradlew :manager:assembleDebug              # APK debug
./gradlew :manager:assembleRelease            # APK rilis (butuh keystore, lihat bawah)
```

CI: `.github/workflows/android.yml` membangun debug + release dan mengunggah APK sebagai artifact
(pada tag `v*` sekalian ditempel ke GitHub Release).

### Keystore rilis

- File: `keystore/kage-release.jks` (PKCS12, RSA 4096, berlaku ~30 tahun).
- Password & alias: `keystore/keystore.properties` (tidak di-commit).
- Untuk CI, isi Secrets repo:
  `KEYSTORE_BASE64` (base64 dari file .jks), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Pakai library-nya di app lain

Panduan lengkap: [docs/DIPAKAI-APP-LAIN.md](docs/DIPAKAI-APP-LAIN.md) - termasuk penjelasan nama
mekanismenya (Binder, binder proxy, binder push lewat ContentProvider, kenapa bukan AIDL),
referensi API, dan daftar penyebab gagal yang paling sering.

```xml
<uses-permission android:name="dev.kage.permission.API" />
<provider
    android:name="dev.kage.provider.KageProvider"
    android:authorities="${applicationId}.kage"
    android:exported="true" />
```

```java
if (!Kage.checkSelfPermission(this)) Kage.requestPermission(this, 1001);  // dialog di manager
Kage.addBinderReceivedListener(() -> {
    try {
        KageRemoteProcess p = Kage.newProcess(new String[]{"id"});
        // baca stdout lewat p.getInputStream(), tunggu lewat p.waitFor()
    } catch (RemoteException e) { }
});
Kage.requestBinder(this);   // opsional: minta push lebih cepat
```

Contoh app klien yang jalan: modul [`sample/`](sample) (APK `dev.kage.sample`).

## Lisensi dan atribusi

- Kode Kage: **Apache License 2.0** - lihat [LICENSE](LICENSE).
- Atribusi & komponen pihak ketiga: [NOTICE](NOTICE) (AndroidX, Material Components, Kotlin,
  kotlinx-coroutines - semuanya Apache 2.0).
- Terima kasih untuk **Shizuku** (RikkaApps, Apache 2.0) yang mempopulerkan pola *binder lewat
  ContentProvider* dan *dex lewat app_process*. Kage ditulis dari nol dan tidak memakai kode
  Shizuku; kalau kamu memakai Shizuku, ikuti lisensi proyek itu.
- Di dalam app: **Setelan → Tentang Kage** berisi versi, lisensi, atribusi, dan tautan dokumentasi.
