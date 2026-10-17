# tidal-googletv-mod

A patch for the TIDAL Android app that turns on TIDAL Connect when the app runs on Google
TV, so the TV can play to a Connect receiver like the phone or desktop app does.

Only tested on TIDAL 2.214.0. Other versions are refused by default and most likely need
changes to the patch; `--force` tries anyway, at your own risk.

## Why

For fully wireless hi-res setups. For example: a Google TV stick on a projector at the
back of the room, and a Raspberry Pi running
[tidal-connect-ng](https://github.com/resurekto/tidal-connect-ng) as the hi-res receiver
next to the amplifier at the front. You browse and control playback on the big screen with the TV
remote, while the receiver streams from TIDAL itself, so no audio goes through the stick.

The stock app has the whole Connect sender built in but switches it off on TV.

## What it adds

- **Output button** in the fullscreen player, right of Lyrics. It opens the device picker
  and shows the connecting/connected state (the phone app's own button).
- **Remembered receiver**: after an app restart it reconnects to the last one.
- **Volume keys**: the remote's volume keys set the receiver's volume, which is synced
  with the TV's volume on connect.

Everything else is the stock app.

## Run

Needs a JDK, `kotlin` and `dex2jar` (`brew install kotlin dex2jar`), and the Android SDK
(build-tools, a platform, platform-tools; `ANDROID_HOME` or `~/Library/Android/sdk`).

1. Enable adb on the TV: Settings → System → About, select "Android TV OS build" 7 times,
   then Settings → System → Developer options → USB debugging. Connect once and allow the
   prompt on the TV:
   ```
   adb connect <tv-ip>:5555
   ```
2. Pull the APK set of the unmodified Play Store TIDAL 2.214.0 into `apk/stock/` (once;
   after installing the patched build, the device no longer has the stock one):
   ```
   mkdir -p apk/stock
   adb shell pm path com.aspiro.tidal
   adb pull <each listed .apk> apk/stock/
   ```
3. Build to `apk/patched/` (base.apk plus the re-signed splits); the bracketed options
   install it on the TV. The patched build is signed with your Android debug key, so the
   first time remove the Play Store app (this signs you out); later builds install over it
   and keep the login:
   ```
   adb uninstall com.aspiro.tidal
   ./src/patcher.main.kts \
       --in apk/stock/base.apk \
       --out apk/patched/base.apk \
       [--install] \
       [--serial <tv-ip>:5555]
   ```

`--help` lists all options.

## How it works

`src/patcher.main.kts` compiles the classes in `src/patch/` against the stock APK and swaps
them in for TIDAL's classes of the same name; the rest of the app is untouched.

| File | Replaces | What it does |
|------|----------|--------------|
| `j32.java` | `j32` | Installs the real Connect broadcast manager at startup (stock: no-op on TV). |
| `bxh.kt` | `bxh` | Player control bar: adds the output button and the D-pad order favorite → Lyrics → output. |
| `TcBroadcastProviderFactory.kt` | `TcBroadcastProviderFactory` | Reports TIDAL Connect as available (stock: not on TV). |
| `TcVolumeSync.kt` | (new) | Mirrors the TV's volume to the receiver, on every change and on connect. |

## License

[0BSD](LICENSE): use it for anything, no conditions.
