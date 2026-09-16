# Kage (影)

Clone Shizuku dari nol: **server privileged yang jalan sebagai shell/root** + **manager app** +
**client library**, semuanya di repo ini.

```
server/   dex yang dijalankan lewat app_process (uid 2000 kalau dari adb, 0 kalau dari su)
common/   protokol, JSON, kripto, BinderContainer - dipakai server & client
provider/ client library (Kage, KageProvider, KageServiceProxy) untuk app pihak ketiga
manager/  app Android: dashboard, manajemen izin, shell, toolbox, log
scripts/  build-server-dex.sh (javac + d8)
```

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

```xml
<uses-permission android:name="dev.kage.permission.API" />
<provider
    android:name="dev.kage.provider.KageProvider"
    android:authorities="${applicationId}.kage"
    android:exported="true" />
```

```java
if (!Kage.checkSelfPermission(this)) Kage.requestPermission(this);   // minta izin ke manager
Kage.addBinderReceivedListener(() -> {
    try {
        KageRemoteProcess p = Kage.newProcess(new String[]{"id"});
        // baca stdout lewat p.getInputStream()
    } catch (RemoteException e) { }
});
```

## Lisensi

Kode di repo ini ditulis dari nol dengan referensi perilaku (bukan kode) dari proyek Shizuku
(Apache-2.0). Kalau kamu memakai / memodifikasi proyek Shizuku, ikuti lisensinya.
