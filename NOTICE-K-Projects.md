# K-Projects — notice / bildirim

K-Projects is a modified version of **[PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)**,
which is based on **[Boardwalk](https://github.com/zhuowei/Boardwalk)**.
The original work is licensed under the **GNU Lesser General Public License v3.0** (see [`LICENSE`](./LICENSE)).
This modified version is distributed under the same license.

## What was changed

Only the look of the app:

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

No launcher logic, authentication or game-launching code was changed.
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

K-Projects, LGPLv3 lisanslı PojavLauncher'ın (Boardwalk tabanlı) yalnızca arayüzü değiştirilmiş hâlidir:
renkler, düğme grafikleri, ikonlar, uygulama adı ve animasyonlu logo. Başlatıcı, giriş ve oyun açma kodlarına dokunulmadı.
Paket adı ve `applicationId` aynı kaldı. PojavLauncher artık geliştirilmiyor; bu sürüm upstream düzeltmelerini almaz.
Bir sürümü dağıtırsan `LICENSE` dosyasını ve bu bildirimi koru, PojavLauncher/Boardwalk atfını silme ve
dağıttığın sürümün kaynak kodunu LGPLv3 altında açık tut. Bu bir hukuki tavsiye değildir.
