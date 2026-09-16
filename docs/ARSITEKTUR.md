# Arsitektur Kage

## Kenapa app_process?

Aplikasi Android biasa tidak bisa menjadi uid 2000/0 dan tidak bisa mendaftarkan Binder ke
`servicemanager`. Satu-satunya cara resmi (tanpa patch ROM) adalah menjalankan sebuah dex lewat
`/system/bin/app_process` dari shell — di situlah uid yang dipakai adalah uid pemanggil:
`adb shell` → 2000, `su` → 0. Dex itu adalah "server" kita.

```
adb shell ─┐
           ├─► app_process -Djava.class.path=server.dex dev.kage.server.KageServer
su      ───┘        │
                    ├─ Binder  (IKageService)  ──► push ke provider client
                    └─ File    (req/res JSON)   ──► fallback
```

## Transport

### 1. Binder
- `KageBinderService extends Binder` dengan `onTransact` manual memakai konstanta di `Protocol`.
- Client menerima Binder lewat `IContentProvider.call("sendBinder", Bundle berisi BinderContainer)`.
- `BinderContainer` (Parcelable) adalah trik penting: Android >= 12 membuang binder telanjang dari
  Bundle yang dikirim antarproses, tetapi binder yang ditulis lewat `writeToParcel` tetap aman.
- Server menemukan proses client dengan memindai `/proc` (cmdline + `Uid:`) dan hanya menghubungi
  uid yang ada di daftar izin.
- `RemoteProcessImpl` mengubah proses menjadi Binder: stdout/stderr dikirim sebagai pipe
  `ParcelFileDescriptor`, `waitFor()` di sisi client berupa polling (Binder thread server tidak
  boleh diblokir).

### 2. File (fallback)
- Direktori bersama: `Android/data/dev.kage.manager/files/kage`.
- `req/<id>.json` berisi `{id, cmd, payload, sig}`; `sig` = HMAC-SHA256(token, "id:cmd:payload").
- Token hanya diketahui app dan server (dikirim sebagai argumen command line, tersimpan di
  `SharedPreferences` app).
- Jawaban: `res/<id>.json` (sinkron / ack berisi pid) dan `res/<id>.done` (exit code).
- Output streaming: `out/<id>.log`, `out/<id>.err` dibaca dengan `RandomAccessFile` (polling 130 ms).
- Heartbeat: `status.json` ditulis tiap detik → UI tahu server hidup tanpa perlu koneksi.

## Sistem izin

- Manager adalah pihak yang selalu dipercaya (uid-nya di-resolve server saat start).
- Manager menyimpan daftar di SQLite, lalu menyinkronkannya ke server (`TX_SYNC_GRANTS`).
- Grant = `pm grant <pkg> dev.kage.permission.API` dijalankan oleh server (punya uid shell/root),
  diikuti sinkronisasi daftar dan push binder ulang ke app tersebut.
- Server juga memverifikasi sendiri lewat PackageManager tiap 15 detik, jadi izin yang diberikan
  manual dari adb (`pm grant`) tetap dikenali meski app manager belum pernah dibuka.

## Panggilan ke system service

`TX_TRANSACT` memungkinkan manager memakai layanan sistem dengan identitas server:
Parcel di-marshal di sisi client, direplay server (`PrivilegedTransact.transact`), hasilnya
dikembalikan sebagai byte. `TX_RESOLVE_TRANSACTION` mencari nama transaksi (`TRANSACTION_xxx`)
lewat refleksi supaya client tidak perlu menulis AIDL untuk setiap service.
Batasan: tidak bisa mengirim binder/FD melalui jalur ini.
