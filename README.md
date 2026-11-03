# AudioCast

Kirim audio internal Android ke iPhone lewat hotspot Android.
Android jadi server (capture audio + HTTP/WebSocket), iPhone cukup buka Safari.

## Build
1. Buka folder ini di Android Studio (File > Open), tunggu Gradle sync.
   Kalau diminta upgrade AGP/Gradle, setujui aja.
2. Colok HP Android (USB debugging nyala), tekan Run.
   Atau Build > Build APK(s), lalu install APK-nya.
   Syarat: Android 10 (API 29) ke atas.

## Pakai
1. Nyalain hotspot di Android.
2. Sambungin iPhone ke hotspot itu.
3. Buka app AudioCast di Android, tekan Mulai.
   Kasih izin rekam audio, lalu izin capture (pilih Mulai/Start now).
4. App nampilin alamat, misalnya http://192.168.43.1:8080.
5. Di iPhone, buka alamat itu pakai Safari, tap "Mulai dengar".
6. Putar lagu/video apa aja di Android. Suaranya keluar di iPhone.

## Catatan
- Audio dari app yang memblokir capture bakal hening.
- Matiin mode silent di iPhone. Biarin Safari tetap di depan.
- Suara putus-putus: naikin slider Buffer. Delay kerasa: turunin.
- Port bisa diganti di CastService.PORT.
