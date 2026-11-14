# ljud for Android

A small native Android app (Kotlin + Jetpack Compose) for Spotify:

- Browse Liked songs and playlists, search, tap to play
- Now-playing bar with play/pause, next and previous
- Spotify Connect speakers with custom names and a volume slider
- **Phone speaker:** a built-in player (librespot, in Rust) makes the phone itself a
  Spotify Connect speaker. No official Spotify app needed. It runs as a foreground
  service with a notification, keeps the phone awake only while playing, and
  pauses on calls or when another app takes the audio.

Requires Spotify Premium. Desktop counterpart: the `ljud` TUI.

## Layout

| Path | What |
|---|---|
| `app/` | Android app (Kotlin, Compose) |
| `app/src/main/java/app/ljud/Auth.kt` | PKCE login via a loopback redirect the app serves itself |
| `app/src/main/java/app/ljud/Api.kt` | Spotify Web API client |
| `app/src/main/java/app/ljud/PlayerService.kt` | Foreground service + JNI bridge for the phone speaker |
| `player/` | Rust library `libljudplayer.so`: librespot + AAudio output, JNI exports |
| `player/vendor/librespot-core/` | librespot-core with a one-line patch, see `LJUD_PATCH.md` |

## Spotify setup

- App login uses client id `33f4456f57d24de0ae5cadc7cca095b6` (`CLIENT_ID` in `Auth.kt`),
  whose Redirect URIs must include `http://127.0.0.1:8989/login`.
- The phone speaker logs in once with librespot's own client id on
  `http://127.0.0.1:5588/login`; librespot then caches reusable credentials in the
  app's private storage.

## Building

Needs: JDK 17+, Gradle 9, the Android SDK (platform 37, build-tools) and NDK in
`~/Android/Sdk`, Rust via rustup with the `aarch64-linux-android` target, and
`cargo-ndk` (`cargo install cargo-ndk`).

```sh
./build.sh            # player library + release APK
./build.sh install    # ... and install with adb
```

The release build is signed with the local debug key (personal sideloading only).

### Installing over Wi-Fi

On the phone: Developer options → Wireless debugging. Then:

```sh
adb pair <ip>:<pairing-port> <code>     # "Pair device with pairing code" dialog
adb connect <ip>:<port>                 # address on the Wireless debugging screen
```

## Notes

- `player/Cargo.lock` pins `vergen` 9.0.6; newer versions break librespot-core's build script.
- The `ndk` crate's `AudioStream::write` reports the written frame count as an error, so
  `player/src/lib.rs` calls AAudio through `ndk-sys` directly.
