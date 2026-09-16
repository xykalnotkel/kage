# Memakai Kage di APK lain

Dokumen ini menjelaskan **apa nama mekanismenya**, **bagaimana cara memakainya dari app lain**,
dan **apa saja yang harus ada** supaya benar-benar jalan. Contoh nyata ada di modul
[`sample/`](../sample) - app `dev.kage.sample` yang dibangun di CI bersama app manager.

---

## 1. Nama mekanismenya: Binder + binder push lewat ContentProvider

Istilah-istilah yang sering bikin bingung:

| Istilah | Artinya di Kage |
| --- | --- |
| **Binder** | Sistem IPC (antar-proses) bawaan Android. Objek `IBinder` bisa dipanggil dari proses lain; kernel Binder yang mengantar datanya. |
| **Binder proxy** | Objek di sisi pemanggil yang "mewakili" objek asli di proses lain. Di Kage: `KageServiceProxy`, `KageRemoteProcess`. |
| **AIDL** | Cara resmi membuat Binder terketik (typed) - file `.aidl` digenerate jadi stub. **Kage tidak memakai AIDL**: transaksi ditulis manual di `onTransact`/`transact` dengan kode transaksi numerik dari `Protocol.java`. Efeknya: server tidak butuh stub, dan library klien tidak perlu file `.aidl` sama sekali. |
| **Binder push** | Masalah utamanya: app biasa tidak boleh mendaftarkan Binder ke `servicemanager`, jadi **server yang harus mengirim** Binder-nya ke app. Caranya: server memanggil `ContentProvider` milik app klien (`method="sendBinder"`) sambil menitipkan `IBinder` di dalam `Bundle`. |
| **BinderContainer** | `Parcelable` pembungkus Binder. Ini trik penting: di Android 12+ framework **membuang** Binder telanjang yang lewat `Bundle#putBinder`, sedangkan Binder yang ditulis sendiri lewat `writeToParcel` tetap selamat. |
| **Deskriptor** | Nama antarmuka (`dev.kage.server.IKageService`) yang ditulis di awal setiap transaksi (`writeInterfaceToken`) supaya dua sisi yakin berbicara protokol yang sama. |
| **User service (Shizuku)** | Belum ada di Kage: pola di mana app menyediakan service-nya sendiri yang dijalankan server. Kage memakai satu server bersama untuk semua app. |

Alur lengkap satu panggilan perintah:

```
app klien                      server Kage (uid 2000/0)              kernel
   │                                    │
   │  1. ContentResolver.call("requestBinder") ──► manager ──► server
   │  2. server scan /proc, temukan pid app klien
   │  3. server call("sendBinder", Bundle{BinderContainer}) ──► provider app klien
   │  ◄── IBinder diterima, Kage.onBinderReceived()
   │  4. transact(TX_NEW_PROCESS, ["/system/bin/sh","-c","id"])
   │                                    │── fork/exec shell sebagai uid server
   │  ◄── IBinder RemoteProcess (stdout/stderr = pipe ParcelFileDescriptor)
   │  5. baca stdout sampai EOF, waitFor() (polling exitValue)
```

## 2. Integrasi: 5 langkah

### Langkah 1 - manifest

```xml
<uses-permission android:name="dev.kage.permission.API" />

<provider
    android:name="dev.kage.provider.KageProvider"
    android:authorities="${applicationId}.kage"
    android:exported="true" />
```

`android:exported="true"` wajib (server harus bisa memanggilnya), dan authority **harus**
`<package> + ".kage"` - itu alamat yang dipakai server untuk mengirim Binder.

### Langkah 2 - masukkan library

Tiga cara, pilih salah satu:

1. **Modul sumber** (paling fleksibel): copy folder `provider/` dan `common/` ke project kamu, lalu:
   ```groovy
   implementation project(':provider')
   implementation project(':common')
   ```
2. **AAR siap pakai**: unduh `kage-provider-<versi>.aar` dari halaman **Releases** repo ini, taruh di
   `app/libs/`, lalu:
   ```groovy
   implementation files('libs/kage-provider-1.1.0.aar')
   ```
3. **git submodule** kalau kamu ikut mengembangkan Kage.

### Langkah 3 - minta izin

```kotlin
if (!Kage.checkSelfPermission(this)) {
    Kage.requestPermission(this, REQUEST_CODE)   // membuka dialog di app manager
}
```

Manager akan menampilkan dialog berisi nama & package app kamu. Kalau pengguna menekan
**Allow**, manager menjalankan `pm grant <package> dev.kage.permission.API` lewat server, lalu
menyinkronkan daftar izin. Hasilnya dikembalikan ke `onActivityResult` (`extra "granted"`).

> Izin ini bertipe *dangerous* dan hanya manager yang bisa memberikannya. Server **menolak**
> permintaan izin yang datang dari Binder (`TX_REQUEST_PERMISSION` tidak melakukan grant sama
> sekali) supaya app lain tidak bisa menaikkan hak aksesnya sendiri.

### Langkah 4 - tunggu Binder

```kotlin
Kage.addBinderReceivedListener { /* siap dipakai */ }
Kage.addBinderDeadListener { /* server mati / restart -> UI ke state "menunggu" */ }
Kage.requestBinder(this)   // opsional: minta push lebih cepat, tanpa menunggu scan 2 detik
```

Server mengirim Binder otomatis begitu proses app kamu terlihat (scan `/proc` setiap 2 detik,
hanya untuk uid yang sudah diizinkan). `requestBinder()` mempercepat dengan memberi tahu manager.
Kedua jalur itu bekerja **tanpa** app kamu harus jalan sebagai proses latar.

### Langkah 5 - pakai

```kotlin
val process = Kage.newProcess(arrayOf("/system/bin/sh", "-c", "pm list packages -3"))
val stdout = process.inputStream          // InputStream biasa
Thread {
    stdout.copyTo(System.out)
    val code = process.waitFor()          // blocking di thread sendiri
}.start()
```

## 3. Referensi API

| API | Kegunaan |
| --- | --- |
| `Kage.checkSelfPermission(context)` | cek izin lokal (tanpa server) |
| `Kage.checkRemotePermission(context)` | cek izin versi server (otoritatif bila Binder hidup) |
| `Kage.requestPermission(activity, requestCode)` | buka dialog manager, hasil di `onActivityResult` |
| `Kage.requestPermission(context)` | versi tanpa hasil |
| `Kage.requestBinder(context)` | minta server push Binder sekarang |
| `Kage.pingBinder()` / `Kage.isBinderAlive()` | cek Binder hidup |
| `Kage.getBinder()` | `IBinder` mentah (untuk kasus lanjutan / pembungkus binder sistem) |
| `Kage.service()` | `KageServiceProxy` - akses transaksi tingkat rendah |
| `Kage.newProcess(cmd[, env, dir])` | jalankan perintah di server, dapat `KageRemoteProcess` |
| `KageRemoteProcess.getInputStream()` / `getErrorStream()` / `getOutputStream()` | stdout / stderr / stdin |
| `KageRemoteProcess.waitFor()` / `exitValue()` / `isAlive()` / `destroy()` | kontrol proses |
| `Kage.getServerStatus()` | Bundle status server (uid, mode, pid, sdk, uptime) |
| `Kage.transact(service, code, data)` | jalankan transaksi system service dengan uid server |
| `Kage.resolveTransaction(iface, method)` | cari kode transaksi system service (refleksi di server) |
| `KageProvider.setRequestBinderHandler {}` | hanya untuk manager: tangani permintaan dari klien |

## 4. Hal yang sering bikin gagal

| Gejala | Penyebab |
| --- | --- |
| `checkSelfPermission` = false terus | izin belum diberikan manager; minta lewat `requestPermission` |
| Binder tidak pernah datang | app belum diizinkan, atau server mati (cek app Kage → Beranda), atau pakai `Kage.requestBinder()` untuk mempercepat |
| `newProcess` mengembalikan null | izin dicabut setelah Binder dikirim → server menolak; panggil `requestPermission` lagi |
| Binder hilang setelah app di-background lama | proses app di-freeze lalu dibunuh; pakai `addBinderReceivedListener` untuk re-init |
| Multi-proses (app kamu punya `:remote` process) | aktifkan `Kage.enableMultiProcessSupport(true)` lalu `KageProvider.requestBinder(context, 3, 700)` di proses non-provider |
| Binder mati setelah HP reboot | normal: server harus dijalankan lagi lewat adb/root (lihat `docs/PANDUAN.md`) |

## 5. Keamanan

- Server hanya melayani uid: **manager** (selalu) dan **uid yang ada di daftar izin**.
- `getBinder` hanya boleh dipanggil proses dari app itu sendiri (mencegah app lain mencuri Binder).
- `sendBinder` hanya menerima kiriman dari uid 0 / 2000 / manager / app itu sendiri.
- Permintaan izin via Binder **tidak** menghasilkan grant; hanya manager yang bisa menjalankan `pm grant`.
- Token transport file hanya diketahui manager dan server.
