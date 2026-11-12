## 4.6.0 (fork)

**Background play, rebuilt.** TikTok frees its own player the moment its screen stops, and it could not be kept alive.
Now, once TikTok is put away, the module plays the same video with a player of its own (its sound), from where TikTok was.

- Auto-scroll in the background: when a video ends, TikTok's feed is moved on and the next video plays.
- The notification shows the real state: play/pause, next, stop, progress and seeking; also on the lock screen and headset buttons.
- Back in TikTok, its own player continues from where the sound was.
- A call, another app's audio or headphones coming out pause it.

**Arka planda oynatma yeniden yazıldı.** TikTok ekranı durur durmaz kendi oynatıcısını kapatıyor ve bu engellenemedi.
Artık TikTok arka plana geçince modül aynı videoyu kendi oynatıcısıyla (sesini) TikTok'un kaldığı yerden çalıyor.

- Arka planda otomatik kaydırma: video bitince akış ilerletilir ve sıradaki video çalar.
- Bildirim gerçek durumu gösterir: oynat/duraklat, sonraki, durdur, ilerleme çubuğu; kilit ekranı ve kulaklık tuşları da.
- TikTok'a dönünce kendi oynatıcısı sesin kaldığı yerden devam eder.
- Arama, başka uygulamanın sesi ya da kulaklığın çıkması duraklatır.

## 4.5.1

**Fix attempt:** when the screen goes off, Android removes the video's surface and TikTok stops its player. The playing video is now moved to a hidden surface just before that, and TikTok is not told, so the video should keep playing.

**Düzeltme denemesi:** ekran kapanınca Android videonun görüntü yüzeyini kaldırıyor ve TikTok oynatıcıyı durduruyordu. Artık video, yüzey kalkmadan hemen önce görünmeyen bir yüzeye aktarılıyor; TikTok'a haber verilmiyor.

## 4.5.0

Diagnostics for background play: where TikTok's player release comes from, the video engine's state, and when video surfaces go away. Restart attempts are capped.

Arka planda oynatma için teşhis: TikTok'un oynatıcıyı neden kapattığı, video motorunun durumu ve görüntü yüzeyinin ne zaman yok olduğu kaydedilir.

## 4.4.9

**Fix:** TikTok frees its video player about a second after it is put away, so background play stopped right away.
While background play runs, that release is held back and made once TikTok is open again or the feed moves on.

**Düzeltme:** TikTok arka plana atılınca yaklaşık bir saniye sonra oynatıcıyı tamamen kapatıyordu, bu yüzden video hemen duruyordu.
Arka planda oynatırken bu kapatma erteleniyor; TikTok açılınca ya da sonraki videoya geçilince yapılıyor.

## 4.4.8

Diagnostics: the bug report traces background play step by step (player position, engine state, each move), to find why auto-scroll stops in the background.

Teşhis: hata raporu arka planda oynatmayı adım adım kaydeder (oynatıcı konumu, motor durumu, her geçiş).

## 4.4.7

**Fix:** auto-scroll in the background also moves on when TikTok stops a video at its end instead of looping it.
The bug report (Settings → More → Bug report) now shows what auto-scroll did in the background.

**Düzeltme:** arka planda TikTok videoyu döngüye almak yerine sonunda durdurursa otomatik kaydırma yine de geçer.
Hata raporu artık arka planda otomatik kaydırmanın ne yaptığını gösteriyor.

## 4.4.6

**New: Media controls** — while background play runs, a notification with play/pause, next and stop.
The same controls work on the lock screen and with headset buttons. Can be turned off under Playback → *Media controls*.

**New: Skip photo posts** — Playback → *Skip photo posts* (off by default). Auto-scroll moves past photo posts
right away, in the background too.

**Background play** (4.4.5) — videos keep playing with the screen off or TikTok in the background; auto-scroll keeps going.

---

**Yeni: Bildirim kontrolleri** — arka planda oynatırken bildirimde oynat/duraklat, sonraki ve durdur.
Kilit ekranında ve kulaklık tuşlarıyla da çalışır. Oynatma → *Bildirim kontrolleri* ile kapatılabilir.

**Yeni: Fotoğraf postlarını atla** — Oynatma → *Fotoğraf postlarını atla* (varsayılan kapalı). Otomatik kaydırma
fotoğraf postlarını hemen geçer, arka planda da.

This build is signed with the fork's own key: uninstall the original module once before installing it.
