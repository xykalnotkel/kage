# Panduan pakai Kage

## 1. Pasang aplikasinya

Unduh APK rilis dari **GitHub Releases** repo ini (`manager-release.apk`), lalu install di HP
(aktifkan "Install unknown apps" untuk browser/file manager yang kamu pakai).

## 2. Nyalakan servernya (sekali saja per reboot)

Kage butuh satu perintah ADB (atau root). Buka app → menu **Beranda** → tombol **Nyalakan server**,
di sana ada perintah siap-copy.

### Cara A — lewat PC (paling umum)

1. Di HP: Settings → About phone → tap **Build number** 7×, lalu Developer options → nyalakan
   **USB debugging**. Sambungkan ke PC.
2. Di PC (butuh adb, mis. dari Android Platform Tools):

   ```sh
   adb devices                       # pastikan HP terdeteksi (authorize di HP)
   adb shell sh /storage/emulated/0/Android/data/dev.kage.manager/files/kage/start.sh
   ```

3. Terminal akan mencetak log server (`Kage server 2 starting`, `uid=2000 (adb)`, `server ready`).
   Selama terminal itu hidup, server hidup. Ctrl-C = server mati.

### Cara B — ADB wireless (tanpa PC, Android 11+)

1. Developer options → **Wireless debugging** → Pair device with pairing code (butuh app ADB
   wireless di HP, mis. "adb shell" dari Termux dengan `adb pair`).
2. Setelah terhubung: `adb shell sh /storage/emulated/0/Android/data/dev.kage.manager/files/kage/start.sh`

### Cara C — root

```sh
su -c "sh /storage/emulated/0/Android/data/dev.kage.manager/files/kage/start.sh"
```

Server akan jalan dengan uid 0 (mode **root**), jadi semua perintah punya hak penuh.

### Cara D — tanpa ADB sama sekali

Jalankan `app_process` langsung sebagai root dari terminal di HP (mis. Termux dengan akses root)
memakai perintah "manual" yang ditampilkan di layar **Nyalakan server**.

## 3. Pakai fiturnya

- **Beranda** — status server (uid, mode, transport binder/file, uptime), Diagnosa, Sync izin, Log.
- **Shell** — terminal. Contoh:
  - `pm list packages -3` → daftar app pihak ketiga
  - `pm disable-user --user 0 com.example.bloatware` → freeze app bawaan
  - `pm uninstall --user 0 com.example.bloatware` → hapus untuk user 0 (bisa dibalikin: `pm install-existing ...`)
  - `appops set com.example.app OP_CAMERA deny` → kunci akses kamera
  - `dumpsys battery`, `dumpsys deviceidle whitelist +com.example.app`
- **Alat** — versi sekali-tap dari perintah di atas (freeze/unfreeze, uninstall user 0, force stop,
  clear data, appops, grant/revoke izin, doze whitelist, screenshot, screenrecord, trim cache, dll).
- **Aplikasi** — daftar app yang minta izin `dev.kage.permission.API`. Toggle di sini menjalankan
  `pm grant`/`pm revoke` lewat server dan langsung mengirim ulang binder ke app itu.

## 4. Untuk pengembang app

Lihat README (bagian "Pakai library-nya di app lain"). Alur lengkapnya:

1. Tambahkan permission + provider di manifest app kamu.
2. `Kage.checkSelfPermission()` → `Kage.requestPermission()` (membuka dialog manager).
3. Setelah diizinkan, `Kage.addBinderReceivedListener()` dan pakai
   `Kage.newProcess(new String[]{"id"})`, `Kage.transact(...)`, `Kage.getServerStatus()`.

## 5. Kalau ada masalah

| Gejala | Sebab & solusi |
| --- | --- |
| "Server belum jalan" padahal terminal masih hidup | Transport binder belum masuk. Cek **Diagnosa**; kalau `push gagal`, app otomatis pakai **transport file** — fitur tetap jalan, hanya shell streaming yang lebih lambat. |
| Android 14+: `app_process cannot load writable dex` | Script start sudah otomatis `chmod 400` dex-nya. Kalau tetap gagal, copy dex ke `/data/local/tmp/kage` dan jalankan perintah manual dari layar setup. |
| Server mati sendiri setelah HP restart | Normal. Jalankan lagi perintah start (bisa dibuat otomatis dengan app automation/macro). |
| `pm grant` gagal padahal server jalan | Izin `dev.kage.permission.API` harus dideklarasikan di manifest app target. |
| Ingin lihat log detail | Setelan → Debug log → restart server, lalu buka **Log** → tab **Server**. |
