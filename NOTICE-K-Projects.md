# K-Projects — notice / bildirim

K-Projects is a modified version of **[PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)**,
which is based on **[Boardwalk](https://github.com/zhuowei/Boardwalk)**.
The original work is licensed under the **GNU Lesser General Public License v3.0** (see [`LICENSE`](./LICENSE)).
This modified version is distributed under the same license.

## What was changed

### The look of the app

- Color palette sampled from the K-Projects logo (`values/colors.xml`, theme items in `values/styles.xml`).
- Neon recolor of the pixel button graphics (`drawable/mine_button_focused.9.png`, `drawable/mine_button_unfocused.9.png`),
  a magenta-to-violet progress bar (`drawable/kp_progress_fill.xml`, used by `drawable/view_text_progressbar.xml`),
  and the menu and line drawables.
- App name (all visible mentions in the locale string files), launcher icons and adaptive icon background,
  default profile icon, notification icon (density PNGs replace the old vector), `branding/` logo.
- New views `AnimatedLogoView` and `NeonStripView` (package `net.kdt.pojavlaunch.kprojects`) and the header / strips that use them
  (`layout/fragment_launcher.xml`, `layout-land/fragment_launcher.xml`, both `activity_pojav_launcher.xml`).
- A credit line to PojavLauncher on the main menu.
- Background color of `MineEditText`.
- Build workflow (`.github/workflows/android.yml`): the Java 8/17/21 runtimes come from the last official PojavLauncher release APK
  (the old JRE CI artifacts have expired), the Google Play steps were removed, and the debug APK is attached to a pre-release.
  Optional emulator test workflows were added (`.github/workflows/kp-*.yml`, `.github/kp/`); they do not touch the app.

### Download and account repair (functional changes)

Pressing PLAY with a Local account ended with `java.lang.RuntimeException: Download failed. Please make sure you are logged in with a Microsoft Account`.
The cause is a deliberate rule of upstream PojavLauncher (`isLocalProfile` in `MinecraftDownloader`): a Local account never downloads game files.
That rule was **kept** (it is the licence rule; nothing imitates a Microsoft account or its rights). What changed is everything around it:

- `tasks/MinecraftDownloader.java`: a Local account is told *before* any work starts which files are missing
  (`LocalAccountDownloadException`, a dialog with a "Microsoft login" button instead of a stack trace); optional files that do not exist are
  skipped for a Local account instead of blocking it; only one download can run at a time; the same file is never scheduled twice;
  an optional file is skipped only when the server says it does not exist (HTTP 404/410), every other failure stops the download;
  a cancelled download no longer reports "done"; progress values are guarded; the version file errors have their own messages.
- `utils/DownloadUtils.java`, `utils/HttpStatusException.java`: HTTP status handling (404/410 stay `FileNotFoundException`, 401/403/429/5xx have their own
  exceptions), a limited number of retries with back-off and `Retry-After` for timeouts, lost connections, 408/425/429/5xx; files are written to
  `<name>.part` and moved into place only when complete (an interrupted download never leaves a half file that looks finished, and a failed
  re-download keeps the old file); the received length is compared with `Content-Length`; a file whose SHA-1 stays wrong after several downloads
  is deleted and reported; a file that cannot be read for the SHA-1 check no longer counts as verified; the HEAD request no longer disables input
  (the Java class library refuses to read its status otherwise).
- `mirrors/DownloadMirror.java`: any failure of a mirror (not only "not found") falls back to the official server.
- `utils/DownloadErrors.java`, `lifecycle/ContextAwareDoneListener.java`, `values*/strings.xml` (English and Turkish): the dialog shows what happened and what to do
  (no internet, file not found, verification failed, no space, Java runtime missing, unsupported version, rate limit, server error, Local account) plus one
  line of technical details; the stack trace stays behind "Show more".
- `utils/LauncherLog.java`: technical details of failed downloads and logins go to `launcher-log.txt` in the game folder (rotated at 512 KB, never contains tokens).
- `authenticator/microsoft/MicrosoftBackgroundLogin.java`: connect/read time-outs (a dead network no longer blocks the login forever), no tokens or codes in
  the logs, a Minecraft profile that cannot be read because of a temporary error or an expired token is **not** treated as "does not own the game" any more
  (only HTTP 404 means that), the token lifetime is taken from the server, clear messages for no internet / session no longer valid / server error / rate limit.
- `com/kdt/mcgui/mcAccountSpinner.java`, `LauncherActivity.java`, `value/MinecraftAccount.java`: the selected account keeps the renewed session, PLAY renews an expired
  Microsoft session first (and does not wait for a renewal that already failed, so an installed game can still be played offline), and a second PLAY press
  cannot start a second download.
- Tests: JUnit tests under `app_pojavlauncher/src/test` (a local HTTP server checks the status handling, retries, truncated bodies and SHA-1 logic) run in
  the Android CI workflow before the APK is built.

The code that starts the game (Java runtime launch, renderers, controls, input) was not changed.
The package name and `applicationId` (`net.kdt.pojavlaunch`) are unchanged.

## Upstream status

PojavLauncher's own README says the project is discontinued (last commit: "Discontinued", 2025-09-23).
This build does not receive upstream fixes.

## Your obligations when you distribute a build

- Keep `LICENSE` and this notice, and keep the credit to PojavLauncher and Boardwalk.
- Publish the complete source of the version you distribute under LGPLv3 (this repository is that source).
- Do not present the app as an official Minecraft, Mojang, Microsoft or PojavLauncher product.
  *Minecraft is a trademark of Mojang AB / Microsoft; K-Projects is not affiliated with them.*

This is a summary, not legal advice. Read the license text yourself.

## Türkçe özet

K-Projects, LGPLv3 lisanslı PojavLauncher'ın (Boardwalk tabanlı) yeniden tasarlanmış hâlidir:
renkler, düğme grafikleri, ikonlar, uygulama adı ve animasyonlu logo. Oyunu başlatan kod (Java ortamı, çizim motorları, kontroller) değişmedi.
İndirme ve hesap/giriş kodu onarıldı: Local hesapla Oyna'ya basınca çıkan "Download failed" hatasının nedeni, upstream'in bilerek koyduğu bir kural
(Local hesap oyun dosyası indiremez). Bu kural korundu; artık anlaşılır bir pencere çıkıyor ve Microsoft ile giriş yapmayı öneriyor.
Ayrıca indirme motoru (HTTP durum kodları, yeniden deneme, `.part` ile güvenli yazma, SHA-1 doğrulama), Microsoft oturum yenileme ve
`launcher-log.txt` günlüğü eklendi; testler CI'da çalışıyor. Derleme ayarı (CI) da güncellendi: Java ortamları resmî PojavLauncher sürüm APK'sından alınıyor.
Paket adı ve `applicationId` aynı kaldı. PojavLauncher artık geliştirilmiyor; bu sürüm upstream düzeltmelerini almaz.
Bir sürümü dağıtırsan `LICENSE` dosyasını ve bu bildirimi koru, PojavLauncher/Boardwalk atfını silme ve
dağıttığın sürümün kaynak kodunu LGPLv3 altında açık tut. Bu bir hukuki tavsiye değildir.
