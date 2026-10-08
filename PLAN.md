# Plan: MangaDex followed-feed (OAuth) + inline video

Two features from `TODO.md`: a MangaDex followed-manga feed behind OAuth, and inline
video playback for Reddit/4chan (Android). Both are code-complete and build green
(shared desktop + Android, both app modules) with unit tests passing. What remains is
on-device/desktop runtime verification.

## Status

| Area | State |
| --- | --- |
| MangaDex followed feed (auth, adapter, login UI, wiring) | Done, unit-tested |
| Inline video data layer (Reddit DASH/HLS, `MediaItem.streamUrl`) | Done, unit-tested |
| Inline video player (Android Media3; reader + muted feed autoplay) | Done, compiles |
| Desktop inline video | Intentionally not done — keeps the "Play in browser" button |
| Runtime verification (device/emulator, desktop run) | **Remaining** |

## Feature 1 — MangaDex followed-manga feed

MangaDex personal-client OAuth is a Keycloak password grant (client id/secret +
username/password → refresh token), no browser redirect. A `mangadex:follows`
pseudo-feed is subscribed to once; login/logout run from Settings and persist a
refresh token; the adapter only reads secrets at fetch time, like `TumblrAdapter`.

Done:
- Secret keys in `shared/.../algofeed/Platform.kt` (`SecretStore.Companion`):
  `mangadex.clientId`, `mangadex.clientSecret`, `mangadex.refreshToken`, `mangadex.user`.
- `shared/.../fetch/Mangadex.kt`: `MangadexAuth` (password grant on login, in-memory
  access-token refresh), `MangadexFollowsAdapter` (type `mangadex-follows`, Bearer-auth
  fetch of `/user/follows/manga/feed`), and a shared `parseMangadexChapters` reused by
  both the per-title and followed adapters.
- Registered in `defaultSources` (`fetch/Adapters.kt`) before `MangadexAdapter`, each
  built with its own `MangadexAuth(client, secrets)`.
- `Repository`: `hasMangadex`, `mangadexUser`, `mangadexLogin`, `mangadexLogout`
  (writes secrets through the existing `secrets ?: settings-table` bridge).
- `AlgofeedViewModel`: `hasMangadex`, `mangadexUser`, `mangadexLogin`, `mangadexLogout`.
- `ui/Dialogs.kt`: `MangadexAccountActions` + `MangadexAccountSection` (client id,
  client secret, username, password; logged-in state); wired in `ui/App.kt`.
- Entry points `desktopApp/.../Main.kt` and `androidApp/.../AlgofeedApplication.kt` pass a
  `MangadexAuth` to `Repository`.

Tests (`shared/src/desktopTest/.../AdaptersTest.kt`): `mangadexFollowsNeedsLogin`
(resolve + clean failure when logged out) and `mangadexFollowsWithLogin` (mock token
endpoint + feed → chapters parse).

Limit: refresh-token rotation isn't persisted from the adapter, so an expired refresh
token surfaces as a "Log in to MangaDex" error and the user logs in again.

## Feature 2 — Inline video (Android)

Android plays inline with Media3/ExoPlayer; desktop keeps the browser button. Video
plays in the reader (tap, with sound and controls) and autoplays muted for the single
most-visible card in the feed. Reddit stores the HLS/DASH manifest so playback has audio.

Done:
- `data/Media.kt`: `MediaItem.streamUrl` (nullable, backward-compatible under
  `MediaCodec`); `MediaCodec.firstVideo`.
- `fetch/Adapters.kt` `redditMedia`: captures `reddit_video.hls_url` (else `dash_url`)
  into `streamUrl`; `url` stays the browser-openable `fallback_url`. 4chan stays
  progressive (`streamUrl` null).
- `ui/Platform.kt`: `expect val inlineVideoSupported` + `expect fun InlineVideo(...)`.
  Android actual `ui/Video.kt` (ExoPlayer in a `PlayerView`, OkHttp datasource with the
  shared `USER_AGENT`, muted/autoplay/controls, thumbnail poster until first frame,
  lifecycle pause, release on dispose). Desktop actual in `desktopMain/.../Windows.kt`
  (`inlineVideoSupported = false`, no-op player).
- `ui/Reader.kt`: VIDEO items play inline when supported, else the old thumbnail +
  "Play the video in the browser".
- `ui/Stream.kt`: feed autoplay. `EntryList` computes the active video id from
  `LazyListState.layoutInfo` via the pure `activeVideoKey(videos, viewportCenter)`;
  `EntryRow` renders `InlineVideo` (muted, tap opens the reader) for the active video
  card, else `CardImage`.
- Media3 deps (`gradle/libs.versions.toml` `media3 = 1.8.0`;
  `shared/build.gradle.kts` androidMain: exoplayer, exoplayer-dash, exoplayer-hls,
  datasource-okhttp, ui).

Test (`shared/src/commonTest/.../ActiveVideoTest.kt`): `activeVideoKey` picks the
straddling card, else the nearest, and null when empty. `redditVideo` in `AdaptersTest`
asserts the HLS `streamUrl` is captured.

## Remaining — runtime verification

- Android device/emulator (`./gradlew :androidApp:installDebug` or the `run` skill):
  create a MangaDex Personal API Client, log in via Settings → MangaDex, subscribe to
  `mangadex:follows`, refresh, confirm followed chapters. Open a Reddit video post and a
  4chan `.webm` in the reader (sound + controls); scroll Home and confirm the centred
  video card autoplays muted while others show thumbnails; confirm Reddit audio.
- Desktop (`./gradlew :desktopApp:run`): MangaDex login + followed feed work; videos
  still show the "Play the video in the browser" button.
- Full `:androidApp:assembleDebug` to confirm packaging (so far only `compileDebugKotlin`
  has run).

## Build/test commands used

```
./gradlew :shared:desktopTest
./gradlew :shared:compileAndroidMain
./gradlew :androidApp:compileDebugKotlin
./gradlew :desktopApp:compileKotlin
```
